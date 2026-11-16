package dev.kizuna.meridian.healing.injury;

import java.util.Locale;

/**
 * 每条伤势效果的强度，都是乘在原值上的倍数：0.85 就是少一成半，2.0 就是翻倍。
 * 削弱类（移速、跳跃、攻击、挖掘、真元摄入）取 (0, 1]；加重类（饥饿、氧气消耗）取 [1, 10]。
 */
public enum InjuryStrength {
    LEG_INJURED_SPEED(0.85, Kind.REDUCING),
    LEG_ZERO_SPEED(0.60, Kind.REDUCING),
    LEG_ZERO_JUMP(0.50, Kind.REDUCING),
    HIP_INJURED_SPEED(0.90, Kind.REDUCING),
    HIP_ZERO_SPEED(0.70, Kind.REDUCING),
    ARM_INJURED_ATTACK(0.80, Kind.REDUCING),
    ARM_INJURED_MINING(0.70, Kind.REDUCING),
    ARM_ZERO_ATTACK(0.50, Kind.REDUCING),
    ARM_ZERO_MINING(0.30, Kind.REDUCING),
    ABDOMEN_INJURED_HUNGER(1.5, Kind.RAISING),
    ABDOMEN_ZERO_HUNGER(3.0, Kind.RAISING),
    BREATH_SPRINT_HUNGER(2.0, Kind.RAISING),
    BREATH_AIR(2.0, Kind.RAISING),
    LUNGS_ZERO_SPEED(0.80, Kind.REDUCING),
    NECK_ATTACK(0.80, Kind.REDUCING),
    NECK_MINING(0.70, Kind.REDUCING),
    DIZZY_QI_INTAKE(0.70, Kind.REDUCING);

    private static final double MAX_RAISE = 10;
    private final double initial;
    private final Kind kind;

    InjuryStrength(double initial, Kind kind) {
        // 初值和随包配置里写的一样；配置里没写这一条时用它。
        this.initial = initial;
        this.kind = kind;
    }

    public String key() {
        // 配置里用小写名，如 leg_injured_speed。
        return name().toLowerCase(Locale.ROOT);
    }

    public double initial() {
        return initial;
    }

    public boolean accepts(double value) {
        // 削弱类不许写成 0（腿伤不是定身）也不许大于 1；加重类不许小于 1。
        if (!Double.isFinite(value)) {
            return false;
        }
        if (kind == Kind.REDUCING) {
            return value > 0 && value <= 1;
        }
        return value >= 1 && value <= MAX_RAISE;
    }

    private enum Kind { REDUCING, RAISING }
}
