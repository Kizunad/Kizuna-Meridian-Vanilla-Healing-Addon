package dev.kizuna.meridian.healing.fabric;

import dev.kizuna.meridian.healing.TemporaryHealthState;

/** 只向本附属暴露实体临时效果，不提供身体状态写入入口。 */
public interface HealingEntityAccess {
    TemporaryHealthState meridian$effects();
    boolean meridian$internalHealth();
    void meridian$internalHealth(boolean internal);
    boolean meridian$loadingEffects();
    void meridian$displayAbsorption(float amount);
    boolean meridian$refreshPending();
    void meridian$markRefreshPending();
    void meridian$clearRefreshPending();
    boolean meridian$healthPaused();
    void meridian$pauseHealth();
    boolean meridian$failureReported();
    void meridian$markFailureReported();
    /** 最近一次宿主查询是否成功给出了归属；从未查询或上次查询失败时为 false。 */
    boolean meridian$bindingKnown();
    /** 最近一次成功查询时实体是否已绑定身体；只在 bindingKnown 为 true 时有意义。 */
    boolean meridian$lastBound();
    void meridian$rememberBinding(boolean bound);
    void meridian$forgetBinding();
}
