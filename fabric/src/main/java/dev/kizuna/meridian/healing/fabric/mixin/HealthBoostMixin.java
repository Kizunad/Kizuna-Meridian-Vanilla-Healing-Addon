package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.HealingFabric;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// 效果的属性入口不再携带实体，在实体侧的调用点按归属拦截。
@Mixin(LivingEntity.class)
public abstract class HealthBoostMixin {
    @Redirect(method = {"onEffectAdded", "onEffectUpdated"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/effect/MobEffect;addAttributeModifiers"
                    + "(Lnet/minecraft/world/entity/ai/attributes/AttributeMap;I)V"))
    private void meridian$addBoost(MobEffect effect, AttributeMap attributes, int amplifier) {
        // 生命提升由身体上限配置接管，避免原版属性制造没有身体依据的额外红心。
        if (!meridian$bodyHealthBoost(effect)) {
            effect.addAttributeModifiers(attributes, amplifier);
        }
    }

    @Redirect(method = {"onEffectUpdated", "onEffectsRemoved"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/effect/MobEffect;removeAttributeModifiers"
                    + "(Lnet/minecraft/world/entity/ai/attributes/AttributeMap;)V"))
    private void meridian$removeBoost(MobEffect effect, AttributeMap attributes) {
        // 添加与移除成对跳过；残留的原版修正由身体刷新统一清除。
        if (!meridian$bodyHealthBoost(effect)) {
            effect.removeAttributeModifiers(attributes);
        }
    }

    @Unique
    private boolean meridian$bodyHealthBoost(MobEffect effect) {
        // 只有生命提升需要查询归属，其他效果与未绑定实体保持原版属性。
        if (effect != MobEffects.HEALTH_BOOST.value()) {
            return false;
        }
        return HealingFabric.blocksVanillaHealth((LivingEntity) (Object) this);
    }
}
