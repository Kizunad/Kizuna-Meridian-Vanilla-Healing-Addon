package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.HealingFabric;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Player.class)
public abstract class PlayerAbsorptionMixin {
    @Redirect(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getAbsorptionAmount()F"))
    private float meridian$skipPlayerGlobalShield(Player player) {
        // 玩家覆盖了伤害实现，必须独立跳过全局黄心，保留部位局部结算。
        if (HealingFabric.blocksVanillaHealth(player)) {
            return 0;
        }
        return player.getAbsorptionAmount();
    }

    @Redirect(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setAbsorptionAmount(F)V"))
    private void meridian$preservePlayerLocalShield(Player player, float amount) {
        // 命中提交成功前不能提前消费效果余额。
        if (!HealingFabric.blocksVanillaHealth(player)) {
            player.setAbsorptionAmount(amount);
        }
    }
}
