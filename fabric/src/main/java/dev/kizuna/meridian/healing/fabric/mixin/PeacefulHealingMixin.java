package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.HealingFabric;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
public abstract class PeacefulHealingMixin {
    @Inject(method = "tickMovement", at = @At("TAIL"))
    private void meridian$additionalPeacefulHealing(CallbackInfo callback) {
        // 原版路径保留；配置只补充原版条件拒绝的回血，不改变难度或自动补食。
        PlayerEntity player = (PlayerEntity) (Object) this;
        if (HealingFabric.inspect(player).isEmpty()) {
            return;
        }
        boolean peaceful = player.getWorld().getDifficulty() == Difficulty.PEACEFUL;
        boolean vanillaRule = player.getWorld().getGameRules().getBoolean(GameRules.NATURAL_REGENERATION);
        if (peaceful && vanillaRule) {
            return;
        }
        boolean difficultyAllows = peaceful || !HealingFabric.config().respectPeaceful();
        if (difficultyAllows && HealingFabric.naturalRegeneration(player.getWorld().getGameRules())
                && player.age % 20 == 0 && player.getHealth() < player.getMaxHealth()) {
            player.heal(1);
        }
    }
}
