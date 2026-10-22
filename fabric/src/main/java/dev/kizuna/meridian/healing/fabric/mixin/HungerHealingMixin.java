package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.HealingFabric;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HungerManager.class)
public abstract class HungerHealingMixin {
    @Unique private PlayerEntity meridian$player;
    @Unique private double meridian$healed;

    @Inject(method = "update", at = @At("HEAD"))
    private void meridian$startUpdate(PlayerEntity player, CallbackInfo callback) {
        // 仅保存本次调用的玩家，便于同一方法里的规则与饥饿消耗重定向使用。
        meridian$player = player;
        meridian$healed = -1;
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void meridian$finishUpdate(PlayerEntity player, CallbackInfo callback) {
        // HungerManager 不在事件结束后持有实体引用。
        meridian$player = null;
    }

    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;heal(F)V"))
    private void meridian$naturalHeal(PlayerEntity player, float amount) {
        // 保留原版触发计时和饱食条件，只将每次治疗剂量乘可配置倍率。
        try {
            var binding = HealingFabric.inspect(player);
            if (binding.isEmpty()) {
                player.heal(amount);
                return;
            }
            meridian$healed = HealingFabric.heal(player, binding.get(), amount * HealingFabric.config().naturalMultiplier());
        } catch (RuntimeException failure) {
            // 治疗没有成功就不扣饥饿消耗，也不能让查询失败打断玩家 tick。
            meridian$healed = 0;
            HealingFabric.report(player, failure);
        }
    }

    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/HungerManager;addExhaustion(F)V"))
    private void meridian$actualExhaustion(HungerManager manager, float vanillaExhaustion) {
        // 饱食回血每实际恢复 1 点原版单位消耗 6 exhaustion，过量无效治疗不收费。
        if (meridian$healed >= 0) {
            manager.addExhaustion((float) (meridian$healed * 6));
            return;
        }
        manager.addExhaustion(vanillaExhaustion);
    }

    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/GameRules;getBoolean(Lnet/minecraft/world/GameRules$Key;)Z"))
    private boolean meridian$naturalRule(GameRules rules, GameRules.Key<GameRules.BooleanRule> key) {
        // 未绑定仍尊重原版，配置覆盖只适用于身体实体的自然回血。
        if (key == GameRules.NATURAL_REGENERATION) {
            try {
                if (HealingFabric.inspect(meridian$player).isPresent()) {
                    return HealingFabric.naturalRegeneration(rules);
                }
            } catch (RuntimeException failure) {
                // 归属查询失败时暂停这次回血判断，不能当作未绑定继续原版结算。
                HealingFabric.report(meridian$player, failure);
                return false;
            }
        }
        return rules.getBoolean(key);
    }
}
