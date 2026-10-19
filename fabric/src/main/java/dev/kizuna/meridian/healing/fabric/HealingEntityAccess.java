package dev.kizuna.meridian.healing.fabric;

import dev.kizuna.meridian.healing.TemporaryHealthState;

/** 只向本附属暴露实体临时效果，不提供身体状态写入入口。 */
public interface HealingEntityAccess {
    TemporaryHealthState meridian$effects();
    boolean meridian$internalHealth();
    void meridian$internalHealth(boolean internal);
    boolean meridian$loadingEffects();
}
