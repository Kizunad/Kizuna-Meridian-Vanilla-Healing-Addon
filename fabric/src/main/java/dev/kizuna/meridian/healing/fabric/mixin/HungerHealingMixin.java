package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.HealingFabric;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FoodData.class)
public abstract class HungerHealingMixin {
    @Unique private ServerPlayer meridian$player;
    @Unique private double meridian$healed;

    @Inject(method = "tick", at = @At("HEAD"))
    private void meridian$startUpdate(ServerPlayer player, CallbackInfo callback) {
        // 仅保存本次调用的玩家，便于同一方法里的规则与饥饿消耗重定向使用。
        meridian$player = player;
        meridian$healed = -1;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void meridian$finishUpdate(ServerPlayer player, CallbackInfo callback) {
        // FoodData 不在事件结束后持有实体引用。
        meridian$player = null;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;heal(F)V"))
    private void meridian$naturalHeal(ServerPlayer player, float amount) {
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

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;addExhaustion(F)V"))
    private void meridian$actualExhaustion(FoodData manager, float vanillaExhaustion) {
        // 饱食回血每实际恢复 1 点原版单位消耗 6 exhaustion，过量无效治疗不收费。
        if (meridian$healed >= 0) {
            manager.addExhaustion((float) (meridian$healed * 6));
            return;
        }
        manager.addExhaustion(vanillaExhaustion);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/gamerules/GameRules;"
            + "get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"))
    private Object meridian$naturalRule(GameRules rules, GameRule<?> rule) {
        // 未绑定仍尊重原版，配置覆盖只适用于身体实体的自然回血。
        // 这里每 tick 都会进来，归属用实体上记住的结论，真正回血时 naturalHeal 会再查一次宿主。
        if (rule == GameRules.NATURAL_HEALTH_REGENERATION) {
            try {
                if (HealingFabric.boundForRule(meridian$player)) {
                    return HealingFabric.naturalRegeneration(rules);
                }
            } catch (RuntimeException failure) {
                // 归属查询失败时暂停这次回血判断，不能当作未绑定继续原版结算。
                HealingFabric.report(meridian$player, failure);
                return false;
            }
        }
        return rules.get(rule);
    }
}
