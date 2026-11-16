package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.InjuryFabric;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 腹伤、气短让玩家的饥饿消耗变多。 */
@Mixin(Player.class)
public abstract class PlayerInjuryMixin {
    @ModifyVariable(method = "causeFoodExhaustion", at = @At("HEAD"), argsOnly = true)
    private float meridian$injuredExhaustion(float exhaustion) {
        // 原版所有消耗饥饿的动作（跑、跳、挖、挨打）都从这里走。
        return InjuryFabric.exhaustion((Player) (Object) this, exhaustion);
    }
}
