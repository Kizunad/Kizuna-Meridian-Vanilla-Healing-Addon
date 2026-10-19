package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.HealingFabric;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.AttributeContainer;
import net.minecraft.entity.effect.AbsorptionStatusEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbsorptionStatusEffect.class)
public abstract class AbsorptionEffectMixin {
    @Inject(method = "onApplied", at = @At("HEAD"), cancellable = true)
    private void meridian$grantShield(LivingEntity entity, AttributeContainer attributes, int amplifier, CallbackInfo callback) {
        // 应用和升级时授予独立局部余额，原版全局加法不再执行。
        if (HealingFabric.absorptionChanged(entity, amplifier + 1)) {
            callback.cancel();
        }
    }

    @Inject(method = "onRemoved", at = @At("HEAD"), cancellable = true)
    private void meridian$removeShield(LivingEntity entity, AttributeContainer attributes, int amplifier, CallbackInfo callback) {
        // 自然到期、牛奶和指令清除共用此移除入口。
        if (HealingFabric.absorptionChanged(entity, 0)) {
            callback.cancel();
        }
    }
}
