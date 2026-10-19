package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.HealingFabric;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.AttributeContainer;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StatusEffect.class)
public abstract class HealthBoostMixin {
    @Inject(method = {"onApplied", "onRemoved"}, at = @At("HEAD"), cancellable = true)
    private void meridian$bodyHealthBoost(LivingEntity entity, AttributeContainer attributes, int amplifier, CallbackInfo callback) {
        // 生命提升由身体上限配置接管，避免原版属性制造没有身体依据的额外红心。
        if ((Object) this == StatusEffects.HEALTH_BOOST && HealingFabric.inspect(entity).isPresent()) {
            callback.cancel();
        }
    }
}
