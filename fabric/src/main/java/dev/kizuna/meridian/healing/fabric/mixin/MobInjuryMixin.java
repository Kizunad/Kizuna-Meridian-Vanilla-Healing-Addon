package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.InjuryFabric;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 手归零的生物不往那只手里捡东西：掉在地上的兵器就留在地上。 */
@Mixin(Mob.class)
public abstract class MobInjuryMixin {
    @Inject(method = "equipItemIfPossible", at = @At("HEAD"), cancellable = true)
    private void meridian$crippledHandTakesNothing(ServerLevel level, ItemStack stack,
            CallbackInfoReturnable<ItemStack> callback) {
        // 原版捡东西先决定装进哪个槽；这件东西要进的是一只归零的手，就当没捡到。护甲照常能捡。
        Mob mob = (Mob) (Object) this;
        if (InjuryFabric.cannotHold(mob, mob.getEquipmentSlotForItem(stack))) {
            callback.setReturnValue(ItemStack.EMPTY);
        }
    }
}
