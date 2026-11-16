package dev.kizuna.meridian.healing.fabric;

import dev.kizuna.meridian.healing.injury.Injuries;
import dev.kizuna.meridian.healing.injury.InjuryBurden;
import dev.kizuna.meridian.healing.injury.InjuryConfig;
import dev.kizuna.meridian.healing.injury.InjuryGroup;
import dev.kizuna.meridian.healing.injury.InjurySign;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 伤势落到实体上：每次同步身体时重新算一遍（{@link #observe}），下一个实体 tick 再挂到实体上（{@link #tick}），
 * 这样挂效果、掉东西都不会发生在一次伤害的结算中途。跟着身体走，不存倒计时。
 */
public final class InjuryFabric {
    static final Identifier SPEED = id("injury_speed");
    static final Identifier JUMP = id("injury_jump");
    static final Identifier ATTACK = id("injury_attack");
    static final Identifier MINING = id("injury_mining");
    /** 手归零掉下来的东西，过这么久才能再捡：站着不动的人不至于一直捡了又掉、响个不停。 */
    static final int DROP_PICKUP_DELAY_TICKS = 40;

    private InjuryFabric() {
        // 只有静态接线。
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(InjuryEffects.MOD_ID, path);
    }

    static void observe(LivingEntity entity, BodySnapshot body) {
        // 只算、只记；头的 HP 比上次看到的少了，就是头又挨了一下。
        InjuryConfig config = HealingFabric.config().injury();
        InjuryTracker tracker = ((InjuryEntityAccess) entity).meridian$injuries();
        tracker.burden = Injuries.assess(body, config).burden(config, entity.getMainArm() == HumanoidArm.LEFT);
        tracker.body = body;
        tracker.dirty = true;
        double head = headHp(body, config);
        if (config.enabled() && !body.getVitality().getDead() && head < tracker.headHp) {
            tracker.nauseaPending = true;
        }
        tracker.headHp = head;
    }

    private static double headHp(BodySnapshot body, InjuryConfig config) {
        List<String> head = config.parts().get(InjuryGroup.HEAD);
        return body.getPartStatesList().stream().filter(part -> head.contains(part.getId()))
                .mapToDouble(part -> part.getHp()).sum();
    }

    /** 每个实体 tick 调一次（只在服务端做事）：有新算出来的伤势就挂上；拿不住东西的手每 tick 都查。 */
    public static void tick(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel)) {
            return;
        }
        InjuryTracker tracker = ((InjuryEntityAccess) entity).meridian$injuries();
        if (tracker.dirty) {
            tracker.dirty = false;
            apply(entity, tracker);
        }
        InjuryBurden burden = tracker.burden;
        if (burden == InjuryBurden.NONE || !entity.isAlive()) {
            return;
        }
        if (burden.noSprint() && entity.isSprinting()) {
            entity.setSprinting(false);
        }
        boolean mainArmLeft = entity.getMainArm() == HumanoidArm.LEFT;
        if (burden.dropLeft()) {
            drop(entity, mainArmLeft ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
        }
        if (burden.dropRight()) {
            drop(entity, mainArmLeft ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        }
    }

    private static void apply(LivingEntity entity, InjuryTracker tracker) {
        // 属性倍数、给人看的效果、失明、头挨打的反胃、头晕的真元修正，都对着刚算出来的这一份落一遍。
        InjuryBurden burden = tracker.burden;
        scale(entity, Attributes.MOVEMENT_SPEED, SPEED, speedFactor(entity, burden.speed()));
        scale(entity, Attributes.JUMP_STRENGTH, JUMP, burden.jump());
        scale(entity, Attributes.ATTACK_DAMAGE, ATTACK, burden.attack());
        scale(entity, Attributes.BLOCK_BREAK_SPEED, MINING, burden.mining());
        for (InjurySign sign : InjurySign.values()) {
            show(entity, InjuryEffects.holder(sign), burden.signs().get(sign));
        }
        blind(entity, burden.blind());
        int nausea = HealingFabric.config().injury().headHitNauseaTicks();
        if (tracker.nauseaPending && nausea > 0 && entity.isAlive()) {
            entity.addEffect(new MobEffectInstance(MobEffects.NAUSEA, nausea));
        }
        tracker.nauseaPending = false;
        InjuryQi.reconcile(entity, tracker);
    }

    private static double speedFactor(LivingEntity entity, double speed) {
        // 配置里的移速倍数说的是「实际走得多快」。玩家的实际速度跟移速属性成正比，照倍数改属性就行；
        // 原版生物走路时属性被用了两次（既当输入的大小，又当加速度），实际速度跟属性的平方走，
        // 所以给生物的属性乘的是倍数的平方根，走出来才是配置里写的倍数。
        return entity instanceof Mob ? Math.sqrt(speed) : speed;
    }

    private static void scale(LivingEntity entity, Holder<Attribute> attribute, Identifier id, double multiplier) {
        // 倍数是乘在总值上的临时修正，不进存档；倍数为 1 就摘掉。没有这项属性的实体不管。
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        if (multiplier == 1) {
            instance.removeModifier(id);
            return;
        }
        instance.addOrUpdateTransientModifier(
                new AttributeModifier(id, multiplier - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static void show(LivingEntity entity, Holder<MobEffect> effect, Integer amplifier) {
        // 不该有的撤掉；该有而没有、或者等级不对的，换成对的。时长无限，有图标，不出粒子。
        MobEffectInstance current = entity.getEffect(effect);
        if (current != null && (amplifier == null || current.getAmplifier() != amplifier)) {
            entity.removeEffect(effect);
            current = null;
        }
        if (amplifier != null && current == null) {
            entity.addEffect(standing(effect, amplifier));
        }
    }

    private static void blind(LivingEntity entity, boolean blind) {
        // 两眼都坏了挂原版失明。只认自己挂的那种（无限时长、环境效果、不出粒子），别处来的失明不动。
        MobEffectInstance current = entity.getEffect(MobEffects.BLINDNESS);
        boolean ours = current != null && current.isInfiniteDuration() && current.isAmbient() && !current.isVisible();
        if (blind && !ours) {
            entity.addEffect(standing(MobEffects.BLINDNESS, 0));
        } else if (!blind && ours) {
            entity.removeEffect(MobEffects.BLINDNESS);
        }
    }

    private static MobEffectInstance standing(Holder<MobEffect> effect, int amplifier) {
        return new MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION, amplifier,
                true /* ambient */, false /* visible */, true /* showIcon */);
    }

    private static void drop(LivingEntity entity, InteractionHand hand) {
        // 这只手归零了，拿不住东西：手里的掉在脚下。之后再放进这只手的也一样掉。
        ItemStack held = entity.getItemInHand(hand);
        if (held.isEmpty()) {
            return;
        }
        entity.setItemInHand(hand, ItemStack.EMPTY);
        ItemEntity dropped = entity.spawnAtLocation((ServerLevel) entity.level(), held);
        if (dropped != null) {
            dropped.setPickUpDelay(DROP_PICKUP_DELAY_TICKS);
        }
    }

    /** 这个装备槽是不是一只归零了的手：是的话什么也拿不住，生物也不往里捡东西。 */
    public static boolean cannotHold(LivingEntity entity, EquipmentSlot slot) {
        if (slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND) {
            return false;
        }
        InjuryBurden burden = ((InjuryEntityAccess) entity).meridian$injuries().burden;
        boolean leftHand = (slot == EquipmentSlot.MAINHAND) == (entity.getMainArm() == HumanoidArm.LEFT);
        return leftHand ? burden.dropLeft() : burden.dropRight();
    }

    /** 饥饿消耗：腹伤让它变多，气短的人疾跑时再变多。 */
    public static float exhaustion(Player player, float exhaustion) {
        InjuryBurden burden = ((InjuryEntityAccess) player).meridian$injuries().burden;
        double scaled = exhaustion * burden.hunger();
        if (player.isSprinting()) {
            scaled *= burden.sprintHunger();
        }
        return (float) scaled;
    }

    /** 水下掉氧气：气短的人掉得更快。before、after 是原版这一 tick 扣之前和扣之后的氧气。 */
    public static int air(LivingEntity entity, int before, int after) {
        InjuryBurden burden = ((InjuryEntityAccess) entity).meridian$injuries().burden;
        if (burden.air() == 1 || after >= before) {
            return after;
        }
        return after - (int) Math.round((before - after) * (burden.air() - 1));
    }
}
