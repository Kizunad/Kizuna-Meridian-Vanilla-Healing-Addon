package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.HealingFabric;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 吸收效果类只在包内可见，按类名指定目标。
@Mixin(targets = "net.minecraft.world.effect.AbsorptionMobEffect")
public abstract class AbsorptionEffectMixin {
    @Inject(method = "onEffectStarted", at = @At("HEAD"), cancellable = true)
    private void meridian$skipGlobalShield(LivingEntity entity, int amplifier, CallbackInfo callback) {
        // 每次施加都会抬高原版全局黄心；已绑定实体的局部余额只在效果应用、升级与移除时结算。
        if (HealingFabric.blocksVanillaHealth(entity)) {
            callback.cancel();
        }
    }
}
