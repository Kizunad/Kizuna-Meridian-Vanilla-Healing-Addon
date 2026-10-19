package dev.kizuna.meridian.healing.fabric;

import dev.kizuna.meridian.healing.HealingRules;
import dev.kizuna.meridian.protocol.DamageIntegrationProtobuf;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.DamageIntegrationPlan;
import dev.kizuna.meridian.server.BodyDamageIntegration;
import net.minecraft.item.Items;

/** 伤害目标已固定后，提供局部黄心和原版图腾的单次效果方案。 */
public final class HealingDamageIntegration implements BodyDamageIntegration {
    @Override
    public byte[] prepare(byte[] payload) {
        // 准备阶段只读取余额和持有物，不先扣黄心或消费图腾。
        var context = DamageIntegrationProtobuf.decodeContext(payload);
        var entity = HealingFabric.damageEntity(context.getSubjectId());
        var state = ((HealingEntityAccess) entity).meridian$effects();
        state.bind(context.getBody());
        double shield = state.shield(context.getDamage().getHit().getPartId());
        double remaining = Math.max(0, context.getDamage().getDamageHp() - shield);
        var plan = DamageIntegrationPlan.newBuilder().setHandled(true).setRemainingDamageHp(remaining);

        // 图腾只有在本次伤害最终致死时才被核心使用；虚空等来源仍遵守原版禁用标签。
        boolean holdingTotem = entity.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)
                || entity.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        var config = HealingFabric.config();
        if (remaining > 0 && config.totemEnabled() && holdingTotem && !context.getBypassesDeathProtection()) {
            plan.setDeathProtection(HealingRules.totemProtection(context.getBody(), config.totemMinimumRatio()));
        }
        return DamageIntegrationProtobuf.encodePlan(plan.build());
    }

    @Override
    public void committed(byte[] payload) {
        // 身体成功提交后才扣本部位黄心；死亡时清除余量，图腾副作用由伤害接线复用原版。
        var commit = DamageIntegrationProtobuf.decodeCommit(payload);
        var entity = HealingFabric.damageEntity(commit.getContext().getSubjectId());
        var state = ((HealingEntityAccess) entity).meridian$effects();
        double absorbed = commit.getContext().getDamage().getDamageHp() - commit.getPlan().getRemainingDamageHp();
        state.consumeShield(commit.getContext().getDamage().getHit().getPartId(), absorbed);
        if (commit.getBody().getVitality().getDead()) {
            state.clearAbsorption();
        }
        HealingFabric.sync(entity, commit.getBody());
    }
}
