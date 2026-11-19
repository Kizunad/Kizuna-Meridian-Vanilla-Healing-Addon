package dev.kizuna.meridian.healing.test;

import dev.kizuna.meridian.healing.HealingBridge;
import dev.kizuna.meridian.healing.HealingRules;
import dev.kizuna.meridian.healing.fabric.HealingEntityAccess;
import dev.kizuna.meridian.healing.fabric.HealingFabric;
import dev.kizuna.meridian.healing.fabric.InjuryEffects;
import dev.kizuna.meridian.healing.fabric.InjuryFabric;
import dev.kizuna.meridian.healing.injury.InjurySign;
import dev.kizuna.meridian.healing.pb.HealingEffects.EffectState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;

public final class InjuryUnbindGameTest {
    private static final BlockPos SPOT = new BlockPos(1, 2, 1);
    private static final double FULL_HP = 100;
    private static final double NECK_HP = 40;
    private static final float VANILLA_HP = 7;
    private static final int EFFECT_TICKS = 200;
    private static final int BIRTH_TICKS = 5;
    private static final double WOUNDED_LEG_RATIO = 0.4;

    @GameTest
    public void unboundMobLosesModifiersAndRebindReadsCurrentWounds(GameTestHelper context) {
        var mob = context.spawn(EntityTypes.IRON_GOLEM, SPOT);
        double speed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        double attack = mob.getAttributeValue(Attributes.ATTACK_DAMAGE);
        bindInjured(mob);
        double injuredSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        double injuredAttack = mob.getAttributeValue(Attributes.ATTACK_DAMAGE);
        context.assertTrue(injuredSpeed < speed && injuredAttack < attack, "重伤须先产生移速与攻击修正");
        checkBrokenLegAndHand(context, mob);
        context.assertTrue(hasSign(mob, InjurySign.NECK_INJURY), "颈伤须先出现");

        HealingGameTest.unclaim(mob);
        try {
            HealingFabric.refresh(mob);
            context.assertTrue(mob.getAttributeValue(Attributes.MOVEMENT_SPEED) == speed, "未绑定后移速修正还在");
            context.assertTrue(mob.getAttributeValue(Attributes.ATTACK_DAMAGE) == attack, "未绑定后攻击修正还在");
            checkVanillaActions(context, mob);
        } finally {
            HealingGameTest.reclaim(mob);
        }
        // 宿主先把颈伤治好，下一次同步只能从此刻的身体重建；归零的腿和手仍不能治。
        var bridge = new HealingBridge(List.of(new HealingGameTest()), List.of());
        var binding = bridge.inspect(mob.getStringUUID()).orElseThrow();
        var doses = HealingRules.distribute(binding.body(), FULL_HP);
        bridge.apply(binding, BodyCommand.newBuilder().setHealBody(doses));
        HealingFabric.refresh(mob);
        mob.tick();
        context.assertTrue(mob.getAttributeValue(Attributes.MOVEMENT_SPEED) == injuredSpeed, "重绑后腿伤没有回来");
        context.assertTrue(!hasSign(mob, InjurySign.NECK_INJURY), "重绑沿用了旧颈伤");
        checkBrokenLegAndHand(context, mob);
        context.assertTrue(mob.getHealth() != VANILLA_HP, "重绑后血量没有重新投影");
        context.succeed();
    }

    private static void bindInjured(IronGolem mob) {
        mob.setNoAi(true /* noAi */);
        HealingGameTest.bindNamed(mob, Map.of("head", FULL_HP, "left_thigh", 0.0, "right_hand", 0.0, "neck", NECK_HP));
        InjuryFabric.tick(mob);
    }

    private static void checkBrokenLegAndHand(GameTestHelper context, IronGolem mob) {
        var leg = mob.getEffect(InjuryEffects.holder(InjurySign.LEG_INJURY));
        context.assertTrue(leg != null && !leg.isVisible() && leg.getAmplifier() == 1, "缺少无粒子的二级腿伤");
        mob.setSprinting(true /* sprinting */);
        context.assertTrue(!mob.isSprinting(), "腿归零时仍能疾跑");
        context.assertTrue(InjuryFabric.cannotHold(mob, EquipmentSlot.MAINHAND), "主手归零时仍能捡武器");
        context.assertTrue(mob.getMainHandItem().isEmpty(), "主手归零时持物没有掉落");
    }

    private static void checkVanillaActions(GameTestHelper context, IronGolem mob) {
        context.assertTrue(!hasSign(mob, InjurySign.LEG_INJURY) && !hasSign(mob, InjurySign.CRIPPLED_HAND),
                "未绑定后旧伤势效果还在");
        mob.setSprinting(true /* sprinting */);
        context.assertTrue(mob.isSprinting(), "未绑定后仍拦疾跑");
        mob.setSprinting(false /* sprinting */);
        context.assertTrue(!InjuryFabric.cannotHold(mob, EquipmentSlot.MAINHAND), "未绑定后仍拦主手捡物");
        mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        mob.setHealth(VANILLA_HP);
        mob.tick();
        HealingFabric.refresh(mob);
        context.assertTrue(mob.getMainHandItem().is(Items.STICK), "未绑定后旧伤势还在丢持物");
        context.assertTrue(mob.getHealth() == VANILLA_HP, "未绑定后的原版血量被覆盖");
    }

    private static boolean hasSign(IronGolem mob, InjurySign sign) {
        return mob.hasEffect(InjuryEffects.holder(sign));
    }

    @GameTest
    public void loadWithoutBodyRemovesOnlySavedInjurySources(GameTestHelper context) {
        var original = context.spawn(EntityTypes.IRON_GOLEM, SPOT);
        var foreignNeck = InjuryEffects.holder(InjurySign.NECK_INJURY);
        var external = standing(foreignNeck, 0);
        bindInjured(original);
        var headHit = new VanillaDamageFabric.DirectDamage(original, original.damageSources().generic(), "head", 1);
        VanillaDamageFabric.damageDirect(headHit).orElseThrow();
        InjuryFabric.tick(original);
        var nausea = original.getEffect(MobEffects.NAUSEA);
        context.assertTrue(nausea != null && !nausea.isVisible(), "头受击须先出现无粒子的反胃");
        original.addEffect(external);
        original.addEffect(standing(MobEffects.BLINDNESS, 0));
        HealingFabric.refresh(original);
        InjuryFabric.tick(original);
        context.assertTrue(original.getEffect(foreignNeck) == external, "外来同名颈伤被合并或抢回");

        var saved = HealingGameTest.save(original);
        saved.remove(Entity.TAG_UUID);
        var restored = EntityTypes.IRON_GOLEM.create(context.getLevel(), EntitySpawnReason.LOAD);
        HealingGameTest.load(restored, saved);
        context.assertTrue(hasSign(restored, InjurySign.LEG_INJURY), "读档没有带回待清除的腿伤");
        context.assertTrue(context.getLevel().addFreshEntity(restored), "读档实体未入世");
        restored.tick();
        context.assertTrue(!hasSign(restored, InjurySign.LEG_INJURY), "首次未绑定同步仍残留存档腿伤");
        context.assertTrue(!restored.hasEffect(MobEffects.NAUSEA), "首次未绑定同步仍残留受击反胃");
        context.assertTrue(restored.hasEffect(foreignNeck) && restored.hasEffect(MobEffects.BLINDNESS),
                "误清了同名外来颈伤或外观相同的原版失明");
        context.succeed();
    }

    @GameTest
    public void legacyInjuriesChangeLevelAndClearWhenHealed(GameTestHelper context) {
        var original = context.spawn(EntityTypes.VINDICATOR, SPOT);
        original.setNoAi(true /* noAi */);
        context.runAfterDelay(BIRTH_TICKS, () -> {
            var healthy = VanillaDamageFabric.inspectBody(original).orElseThrow();
            var leg = InjuryEffects.holder(InjurySign.LEG_INJURY);
            var restored = loadLegacyInjuries(context, original, healthy.getId());
            var savedLeg = restored.getEffect(leg);
            context.assertTrue(savedLeg != null && savedLeg.isInfiniteDuration() && savedLeg.getAmplifier() == 1,
                    "旧档应带有无限时长的腿伤 Ⅱ");
            context.assertTrue(restored.hasEffect(MobEffects.BLINDNESS), "旧档缺少伤势失明");

            HealingFabric.sync(restored, woundedLegAndEyes(healthy));
            InjuryFabric.tick(restored);
            var recoveringLeg = restored.getEffect(leg);
            boolean stillBlind = restored.hasEffect(MobEffects.BLINDNESS);
            HealingFabric.sync(restored, healthy);
            InjuryFabric.tick(restored);
            context.assertTrue(!restored.hasEffect(leg), "旧档伤好了图标还在");
            context.assertTrue(!restored.hasEffect(MobEffects.BLINDNESS), "旧档眼伤好了失明还在");
            context.assertTrue(recoveringLeg != null && recoveringLeg.isInfiniteDuration()
                    && recoveringLeg.getAmplifier() == 0, "旧档腿伤 Ⅱ 应随好转降到 Ⅰ");
            context.assertTrue(stillBlind, "两眼仍归零时伤势失明应保留");
            context.succeed();
        });
    }

    private static Vindicator loadLegacyInjuries(GameTestHelper context, Vindicator original, String bodyId) {
        original.addEffect(standing(InjuryEffects.holder(InjurySign.LEG_INJURY), 1));
        original.addEffect(standing(MobEffects.BLINDNESS, 0));
        var saved = HealingGameTest.save(original);
        // 没有黄心和生命提升时，旧版的治疗记录只写身体 ID。活动效果仍在原版实体存档里。
        var legacy = EffectState.newBuilder().setBodyId(bodyId).build();
        saved.putByteArray("MeridianHealingEffects", legacy.toByteArray());
        original.discard();
        var restored = EntityTypes.VINDICATOR.create(context.getLevel(), EntitySpawnReason.LOAD);
        HealingGameTest.load(restored, saved);
        context.assertTrue(context.getLevel().addFreshEntity(restored), "旧档实体未入世");
        return restored;
    }

    private static BodySnapshot woundedLegAndEyes(BodySnapshot healthy) {
        var body = healthy.toBuilder();
        var leg = body.getPartStatesBuilderList().stream()
                .filter(part -> part.getId().equals("left_thigh"))
                .findFirst().orElseThrow();
        leg.setHp(leg.getHp() * WOUNDED_LEG_RATIO);
        for (var organ : body.getOrganStatesBuilderList()) {
            String id = organ.getId();
            if (id.equals("left_eye") || id.equals("right_eye")) {
                organ.setHp(0);
            }
        }
        return body.build();
    }

    @GameTest
    public void emptySavedOwnershipLeavesForeignInjuriesAlone(GameTestHelper context) {
        var original = context.spawn(EntityTypes.IRON_GOLEM, SPOT);
        HealingGameTest.bindNamed(original, Map.of("left_thigh", FULL_HP));
        var leg = InjuryEffects.holder(InjurySign.LEG_INJURY);
        original.addEffect(standing(leg, 1));
        original.addEffect(standing(MobEffects.BLINDNESS, 0));
        var saved = HealingGameTest.save(original);
        saved.remove(Entity.TAG_UUID);
        var restored = EntityTypes.IRON_GOLEM.create(context.getLevel(), EntitySpawnReason.LOAD);
        HealingGameTest.load(restored, saved);
        context.assertTrue(context.getLevel().addFreshEntity(restored), "空归属清单的实体未入世");
        restored.tick();
        context.assertTrue(restored.hasEffect(leg), "空归属清单误认领了外来腿伤");
        context.assertTrue(restored.hasEffect(MobEffects.BLINDNESS), "空归属清单误认领了外来失明");
        context.succeed();
    }

    private static MobEffectInstance standing(Holder<MobEffect> type, int amplifier) {
        return new MobEffectInstance(type, MobEffectInstance.INFINITE_DURATION, amplifier,
                true /* ambient */, false /* visible */, true /* showIcon */);
    }

    @GameTest
    public void firstUnboundHealthWriteHandsPotionsBackToVanilla(GameTestHelper context) {
        var mob = context.spawn(EntityTypes.IRON_GOLEM, SPOT);
        float maxHp = mob.getMaxHealth();
        bindInjured(mob);
        mob.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, EFFECT_TICKS));
        mob.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, EFFECT_TICKS));
        var state = ((HealingEntityAccess) mob).meridian$effects();
        context.assertTrue(state.totalShield() > 0 && mob.getMaxHealth() == maxHp, "缺少绑定时的药效投影");
        HealingGameTest.unclaim(mob);
        try {
            mob.setHealth(VANILLA_HP);
            context.assertTrue(!hasSign(mob, InjurySign.LEG_INJURY), "直接写血量先发现未绑定时漏清腿伤");
            context.assertTrue(mob.getHealth() == VANILLA_HP && mob.getMaxHealth() > maxHp, "原版生命提升没有接管");
            context.assertTrue(state.totalShield() == 0 && mob.getAbsorptionAmount() == mob.getMaxAbsorption(),
                    "局部黄心没有交回原版上限");
            mob.removeEffect(MobEffects.HEALTH_BOOST);
        } finally {
            HealingGameTest.reclaim(mob);
        }
        HealingFabric.refresh(mob);
        context.assertTrue(state.boostGrants().isEmpty() && mob.getMaxHealth() == maxHp, "重绑后旧的生命提升仍在");
        context.succeed();
    }
}
