package dev.kizuna.meridian.healing.injury;

import java.util.Map;

/**
 * 伤势落到一个人身上的全部后果。倍数都是乘在原值上的，1 表示不变。
 *
 * @param speed 移速倍数（腿、胯、两肺全坏）
 * @param jump 跳跃高度倍数（腿归零）
 * @param noSprint 不能疾跑（腿或胯归零）
 * @param attack 攻击伤害倍数（惯用手那条手臂、颈）
 * @param mining 挖掘速度倍数（同上）
 * @param hunger 饥饿消耗倍数（腹）
 * @param sprintHunger 疾跑时饥饿消耗再乘的倍数（气短）
 * @param air 水下氧气消耗倍数（气短）
 * @param dropLeft 左手拿不住东西（左手归零）
 * @param dropRight 右手拿不住东西
 * @param blind 两眼都坏了，挂原版失明
 * @param qiIntake 真元摄入倍数（头晕）
 * @param signs 挂哪几个伤势状态效果，各是几级（放大值 0、1）
 */
public record InjuryBurden(double speed, double jump, boolean noSprint, double attack, double mining,
                           double hunger, double sprintHunger, double air, boolean dropLeft, boolean dropRight,
                           boolean blind, double qiIntake, Map<InjurySign, Integer> signs) {
    public static final InjuryBurden NONE =
            new InjuryBurden(1, 1, false, 1, 1, 1, 1, 1, false, false, false, 1, Map.of());

    public InjuryBurden {
        signs = Map.copyOf(signs);
    }

    public boolean dizzy() {
        return signs.containsKey(InjurySign.DIZZINESS);
    }
}
