package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.HealingFabric;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MobEffect.class)
public abstract class HealthBoostMixin {
    @Inject(method = {"addAttributeModifiers", "removeAttributeModifiers"}, at = @At("HEAD"), cancellable = true)
    private void meridian$bodyHealthBoost(LivingEntity entity, AttributeMap attributes, int amplifier, CallbackInfo callback) {
        // 生命提升由身体上限配置接管，避免原版属性制造没有身体依据的额外红心。
        if ((Object) this == MobEffects.HEALTH_BOOST && HealingFabric.blocksVanillaHealth(entity)) {
            callback.cancel();
        }
    }
}
