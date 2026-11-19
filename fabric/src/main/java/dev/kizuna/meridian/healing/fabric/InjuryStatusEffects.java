package dev.kizuna.meridian.healing.fabric;

import dev.kizuna.meridian.healing.injury.InjurySign;
import dev.kizuna.meridian.healing.pb.HealingEffects.EffectState;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 记录伤势实际施加的实例。同名效果只占一个原版槽，其他来源优先使用它。 */
public final class InjuryStatusEffects {
    private final Map<Holder<MobEffect>, MobEffectInstance> owned = new HashMap<>();

    void show(LivingEntity entity, Holder<MobEffect> type, Integer amplifier) {
        MobEffectInstance current = entity.getEffect(type);
        if (current != null && current != owned.get(type)) {
            return;
        }
        if (amplifier == null) {
            removeOwned(entity, type);
        } else if (current == null || current.getAmplifier() != amplifier) {
            var standing = new MobEffectInstance(type, MobEffectInstance.INFINITE_DURATION, amplifier,
                    true /* ambient */, false /* visible */, true /* showIcon */);
            apply(entity, standing);
        }
    }

    void nausea(LivingEntity entity, int ticks) {
        MobEffectInstance current = entity.getEffect(MobEffects.NAUSEA);
        if (current == null || current == owned.get(MobEffects.NAUSEA)) {
            var nausea = new MobEffectInstance(MobEffects.NAUSEA, ticks, 0,
                    false /* ambient */, false /* visible */, true /* showIcon */);
            apply(entity, nausea);
        }
    }

    private void apply(LivingEntity entity, MobEffectInstance effect) {
        var type = effect.getEffect();
        if (entity.addEffect(effect) && entity.getEffect(type) == effect) {
            owned.put(type, effect);
        }
    }

    public void removeOwned(LivingEntity entity, Holder<MobEffect> type) {
        MobEffectInstance previous = owned.remove(type);
        if (previous != null && entity.getEffect(type) == previous) {
            entity.removeEffect(type);
        }
    }

    void clear(LivingEntity entity) {
        for (var type : List.copyOf(owned.keySet())) {
            removeOwned(entity, type);
        }
    }

    public List<String> savedIds(LivingEntity entity) {
        return owned.entrySet().stream()
                .filter(entry -> entity.getEffect(entry.getKey()) == entry.getValue())
                .map(entry -> entry.getKey().getRegisteredName())
                .sorted()
                .toList();
    }

    public void restore(LivingEntity entity, EffectState state) {
        owned.clear();
        List<String> ids = state.getInjuryEffectsList();
        if (ids.isEmpty()) {
            if (!state.hasDetached()) {
                claimLegacyEffects(entity);
            }
            return;
        }
        for (InjurySign sign : InjurySign.values()) {
            restoreActive(entity, InjuryEffects.holder(sign), ids);
        }
        restoreActive(entity, MobEffects.BLINDNESS, ids);
        restoreActive(entity, MobEffects.NAUSEA, ids);
    }

    private void restoreActive(LivingEntity entity, Holder<MobEffect> type, List<String> ids) {
        if (ids.contains(type.getRegisteredName())) {
            claimActive(entity, type);
        }
    }

    private void claimLegacyEffects(LivingEntity entity) {
        for (InjurySign sign : InjurySign.values()) {
            claimActive(entity, InjuryEffects.holder(sign));
        }
        var blindness = entity.getEffect(MobEffects.BLINDNESS);
        if (blindness != null && blindness.isInfiniteDuration() && blindness.isAmbient() && !blindness.isVisible()) {
            owned.put(MobEffects.BLINDNESS, blindness);
        }
    }

    private void claimActive(LivingEntity entity, Holder<MobEffect> type) {
        MobEffectInstance active = entity.getEffect(type);
        if (active != null) {
            owned.put(type, active);
        }
    }
}
