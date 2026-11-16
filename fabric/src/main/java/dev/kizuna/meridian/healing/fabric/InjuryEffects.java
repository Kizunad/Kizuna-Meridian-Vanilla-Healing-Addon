package dev.kizuna.meridian.healing.fabric;

import dev.kizuna.meridian.healing.injury.InjurySign;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.EnumMap;
import java.util.Map;

/**
 * 伤势状态效果：只负责给人看（图标、名字、等级），本身不带属性修正，数值由 {@link InjuryFabric} 按配置另挂。
 * 两端都注册：客户端靠身上有没有这些效果来判断能不能疾跑。
 */
public final class InjuryEffects {
    public static final String MOD_ID = "meridian_vanilla_healing";
    /** 牛奶和「清除全部效果」解不了的状态效果；别的附属要解不了的效果也往这个标签里加。 */
    public static final TagKey<MobEffect> INCURABLE =
            TagKey.create(Registries.MOB_EFFECT, Identifier.fromNamespaceAndPath("meridian_vanilla", "incurable"));
    /** 等级 Ⅱ 的放大值：腿伤、胯伤到了这一级不能疾跑。 */
    private static final int SECOND_LEVEL = 1;
    private static final int COLOR = 0x7A1F1F;
    private static final Map<InjurySign, Holder<MobEffect>> SIGNS = new EnumMap<>(InjurySign.class);

    private InjuryEffects() {
        // 只在模组初始化时注册一次。
    }

    static void register() {
        for (InjurySign sign : InjurySign.values()) {
            SIGNS.put(sign, Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
                    Identifier.fromNamespaceAndPath(MOD_ID, sign.path()), new Sign()));
        }
    }

    public static Holder<MobEffect> holder(InjurySign sign) {
        return SIGNS.get(sign);
    }

    /** 腿或胯归零（腿伤 Ⅱ、胯伤 Ⅱ）的人不能疾跑；服务端、客户端都按身上的效果判。 */
    public static boolean blocksSprint(LivingEntity entity) {
        return atSecondLevel(entity, InjurySign.LEG_INJURY) || atSecondLevel(entity, InjurySign.HIP_INJURY);
    }

    private static boolean atSecondLevel(LivingEntity entity, InjurySign sign) {
        MobEffectInstance effect = entity.getEffect(SIGNS.get(sign));
        return effect != null && effect.getAmplifier() >= SECOND_LEVEL;
    }

    private static final class Sign extends MobEffect {
        private Sign() {
            // 有害类，颜色只用在药水粒子上；这些效果不出粒子。
            super(MobEffectCategory.HARMFUL, COLOR);
        }
    }
}
