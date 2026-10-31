package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.HealingFabric;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PeacefulHealingMixin {
    @Inject(method = "aiStep", at = @At("TAIL"))
    private void meridian$additionalPeacefulHealing(CallbackInfo callback) {
        // 原版路径保留；配置只补充原版条件拒绝的回血，不改变难度或自动补食。
        Player player = (Player) (Object) this;
        if (player.level().isClientSide || player.tickCount % 20 != 0 || player.getHealth() >= player.getMaxHealth()) {
            return;
        }
        boolean peaceful = player.level().getDifficulty() == Difficulty.PEACEFUL;
        boolean vanillaRule = player.level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
        if (peaceful && vanillaRule) {
            return;
        }
        boolean difficultyAllows = peaceful || !HealingFabric.config().respectPeaceful();
        if (difficultyAllows && HealingFabric.naturalRegeneration(player.level().getGameRules())) {
            // 补充治疗只走有异常边界的身体入口，失败停止本次效果，未绑定也不额外回血。
            HealingFabric.interceptHeal(player, 1);
        }
    }
}
