package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.fabric.InjuryEffects;
import dev.kizuna.meridian.healing.fabric.InjuryEntityAccess;
import dev.kizuna.meridian.healing.fabric.InjuryFabric;
import dev.kizuna.meridian.healing.fabric.InjuryTracker;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/** 伤势在实体上的接线：每 tick 落一次、腿坏了不能疾跑、气短掉氧气快、牛奶解不了。 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityInjuryMixin implements InjuryEntityAccess {
    @Shadow @Final private Map<Holder<MobEffect>, MobEffectInstance> activeEffects;
    @Unique private final InjuryTracker meridian$injuries = new InjuryTracker();

    @Shadow
    public abstract boolean removeEffect(Holder<MobEffect> effect);

    @Override
    public InjuryTracker meridian$injuries() {
        return meridian$injuries;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void meridian$applyInjuries(CallbackInfo callback) {
        // 同步身体时只算不挂，等到这里再挂：不在一次伤害的结算中途改效果、掉东西。
        InjuryFabric.tick((LivingEntity) (Object) this);
    }

    @ModifyVariable(method = "setSprinting", at = @At("HEAD"), argsOnly = true)
    private boolean meridian$noSprintOnBrokenLegs(boolean sprinting) {
        // 腿伤 Ⅱ、胯伤 Ⅱ 的人起不了疾跑。客户端自己决定疾跑，所以两端都按身上的效果拦。
        return sprinting && !InjuryEffects.blocksSprint((LivingEntity) (Object) this);
    }

    @Inject(method = "decreaseAirSupply", at = @At("RETURN"), cancellable = true)
    private void meridian$shortBreath(int current, CallbackInfoReturnable<Integer> callback) {
        // 气短的人水下掉氧气更快。
        callback.setReturnValue(InjuryFabric.air((LivingEntity) (Object) this, current, callback.getReturnValue()));
    }

    @Inject(method = "removeAllEffects", at = @At("HEAD"), cancellable = true)
    private void meridian$keepIncurable(CallbackInfoReturnable<Boolean> callback) {
        // 牛奶、「清除全部效果」：标签里解不了的效果留着，其余逐个照常移除。身上没有这类效果时不插手原版。
        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity.level().isClientSide()
                || activeEffects.keySet().stream().noneMatch(effect -> effect.is(InjuryEffects.INCURABLE))) {
            return;
        }
        boolean removed = false;
        for (Holder<MobEffect> effect : List.copyOf(activeEffects.keySet())) {
            if (!effect.is(InjuryEffects.INCURABLE)) {
                removed |= removeEffect(effect);
            }
        }
        callback.setReturnValue(removed);
    }
}
