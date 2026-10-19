package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.HealingFabric;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerEntity.class)
public abstract class PlayerAbsorptionMixin {
    @Redirect(method = "applyDamage", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;getAbsorptionAmount()F"))
    private float meridian$skipPlayerGlobalShield(PlayerEntity player) {
        // 玩家覆盖了伤害实现，必须独立跳过全局黄心，保留部位局部结算。
        if (HealingFabric.inspect(player).isPresent()) {
            return 0;
        }
        return player.getAbsorptionAmount();
    }

    @Redirect(method = "applyDamage", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;setAbsorptionAmount(F)V"))
    private void meridian$preservePlayerLocalShield(PlayerEntity player, float amount) {
        // 命中提交成功前不能提前消费效果余额。
        if (HealingFabric.inspect(player).isEmpty()) {
            player.setAbsorptionAmount(amount);
        }
    }
}
