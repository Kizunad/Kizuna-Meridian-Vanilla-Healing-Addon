package dev.kizuna.meridian.healing.fabric;

import dev.kizuna.meridian.healing.injury.InjuryBurden;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;

/** 一个实体身上的伤势现状；不进存档，进世界后第一次同步身体时重新算出来。 */
public final class InjuryTracker {
    public final InjuryStatusEffects effects = new InjuryStatusEffects();
    /** 还没看到过这个实体的头部 HP。 */
    static final double UNSEEN = Double.NaN;
    InjuryBurden burden = InjuryBurden.NONE;
    /** 最近一次同步时的身体；头晕的真元修正要对着它核对。 */
    BodySnapshot body;
    /** 伤势重新算过，还没落到实体上。 */
    boolean dirty;
    double headHp = UNSEEN;
    boolean nauseaPending;

    public InjuryBurden burden() {
        return burden;
    }

    void clear() {
        burden = InjuryBurden.NONE;
        body = null;
        dirty = false;
        headHp = UNSEEN;
        nauseaPending = false;
    }
}
