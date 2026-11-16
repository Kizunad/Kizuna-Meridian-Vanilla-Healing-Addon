package dev.kizuna.meridian.healing.injury;

import java.util.EnumMap;
import java.util.Map;

import static dev.kizuna.meridian.healing.injury.Injuries.ALL_LUNGS;
import static dev.kizuna.meridian.healing.injury.Injuries.INJURED;
import static dev.kizuna.meridian.healing.injury.Injuries.NONE;
import static dev.kizuna.meridian.healing.injury.Injuries.ZERO;

/** 把伤势换成后果：每一组各算各的倍数，同一项上有几处来源就连乘。 */
final class InjuryBurdens {
    private InjuryBurdens() {
        // 只有静态换算。
    }

    static InjuryBurden of(Injuries injuries, InjuryConfig config, boolean mainArmLeft) {
        Map<InjurySign, Integer> signs = new EnumMap<>(InjurySign.class);
        int leg = injuries.tier(InjuryGroup.LEG);
        int hip = injuries.tier(InjuryGroup.HIP);
        int neck = injuries.tier(InjuryGroup.NECK);
        int abdomen = injuries.tier(InjuryGroup.ABDOMEN);
        int leftArm = arm(injuries, InjuryGroup.LEFT_ARM, InjuryGroup.LEFT_HAND);
        int rightArm = arm(injuries, InjuryGroup.RIGHT_ARM, InjuryGroup.RIGHT_HAND);
        int mainArm = mainArmLeft ? leftArm : rightArm;
        boolean dropLeft = injuries.tier(InjuryGroup.LEFT_HAND) == ZERO;
        boolean dropRight = injuries.tier(InjuryGroup.RIGHT_HAND) == ZERO;
        boolean shortBreath = injuries.tier(InjuryGroup.CHEST) != NONE || injuries.lungsLost() != NONE;
        boolean dizzy = injuries.tier(InjuryGroup.HEAD) != NONE;
        sign(signs, InjurySign.LEG_INJURY, leg);
        sign(signs, InjurySign.HIP_INJURY, hip);
        sign(signs, InjurySign.ARM_INJURY, Math.max(leftArm, rightArm));
        sign(signs, InjurySign.CRIPPLED_HAND, dropLeft || dropRight ? INJURED : NONE);
        sign(signs, InjurySign.ABDOMEN_INJURY, abdomen);
        sign(signs, InjurySign.SHORT_BREATH, shortBreath ? INJURED : NONE);
        sign(signs, InjurySign.NECK_INJURY, neck == NONE ? NONE : INJURED);
        sign(signs, InjurySign.DIZZINESS, dizzy ? INJURED : NONE);
        double speed = pick(config, leg, InjuryStrength.LEG_INJURED_SPEED, InjuryStrength.LEG_ZERO_SPEED)
                * pick(config, hip, InjuryStrength.HIP_INJURED_SPEED, InjuryStrength.HIP_ZERO_SPEED)
                * when(config, injuries.lungsLost() == ALL_LUNGS, InjuryStrength.LUNGS_ZERO_SPEED);
        double attack = pick(config, mainArm, InjuryStrength.ARM_INJURED_ATTACK, InjuryStrength.ARM_ZERO_ATTACK)
                * when(config, neck != NONE, InjuryStrength.NECK_ATTACK);
        double mining = pick(config, mainArm, InjuryStrength.ARM_INJURED_MINING, InjuryStrength.ARM_ZERO_MINING)
                * when(config, neck != NONE, InjuryStrength.NECK_MINING);
        return new InjuryBurden(speed, when(config, leg == ZERO, InjuryStrength.LEG_ZERO_JUMP),
                leg == ZERO || hip == ZERO, attack, mining,
                pick(config, abdomen, InjuryStrength.ABDOMEN_INJURED_HUNGER, InjuryStrength.ABDOMEN_ZERO_HUNGER),
                when(config, shortBreath, InjuryStrength.BREATH_SPRINT_HUNGER),
                when(config, shortBreath, InjuryStrength.BREATH_AIR), dropLeft, dropRight, injuries.blind(),
                when(config, dizzy, InjuryStrength.DIZZY_QI_INTAKE), signs);
    }

    private static int arm(Injuries injuries, InjuryGroup arm, InjuryGroup hand) {
        // 一条手臂的档：上臂、前臂伤到哪档就是哪档；手只要受了伤（含归零）至少算受伤一档，手归零另有「拿不住」。
        int tier = injuries.tier(arm);
        if (injuries.tier(hand) != NONE) {
            tier = Math.max(tier, INJURED);
        }
        return tier;
    }

    private static void sign(Map<InjurySign, Integer> signs, InjurySign sign, int tier) {
        // 档 1、2 对应效果等级 Ⅰ、Ⅱ，也就是放大值 0、1。
        if (tier != NONE) {
            signs.put(sign, tier - 1);
        }
    }

    private static double pick(InjuryConfig config, int tier, InjuryStrength injured, InjuryStrength zero) {
        if (tier == NONE) {
            return 1;
        }
        return config.strength(tier == ZERO ? zero : injured);
    }

    private static double when(InjuryConfig config, boolean applies, InjuryStrength strength) {
        return applies ? config.strength(strength) : 1;
    }
}
