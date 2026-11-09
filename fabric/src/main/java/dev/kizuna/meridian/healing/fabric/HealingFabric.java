package dev.kizuna.meridian.healing.fabric;

import dev.kizuna.meridian.healing.HealingBridge;
import dev.kizuna.meridian.healing.HealingConfig;
import dev.kizuna.meridian.healing.HealingErrors;
import dev.kizuna.meridian.healing.HealingRules;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.HealthMessages.PartHealthLimit;
import dev.kizuna.meridian.protocol.pb.HealthMessages.SetPartHealthLimits;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Optional;

/** 可信实体事件编排；每次治疗只向唯一身体宿主提交一次。 */
public final class HealingFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_vanilla_healing");
    private static final ThreadLocal<Deque<LivingEntity>> DAMAGE_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static HealingBridge bridge;
    private static HealingConfig config;

    @Override
    public void onInitialize() {
        // 与伤害附属共用公开宿主发现，单机与专服使用相同服务端规则。
        config = HealingConfig.load(FabricLoader.getInstance().getConfigDir().resolve("meridian-vanilla-healing.toml"));
        bridge = new HealingBridge(FabricLoader.getInstance().getEntrypoints("meridian_health_host", BodyHealthHost.class));
        VanillaDamageFabric.registerDamageScope(new VanillaDamageFabric.DamageScope() {
            @Override
            public void enter(LivingEntity entity) {
                // 直接伤害复用原版 hurtServer 的局部黄心和图腾上下文。
                beginDamage(entity);
            }

            @Override
            public void exit(LivingEntity entity) {
                // 无论伤害提交成功与否，退出当前层级的实体上下文。
                endDamage(entity);
            }
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            // 等其他宿主完成入世认领，再由实体首次 tick 执行同步。
            if (entity instanceof LivingEntity living) {
                ((HealingEntityAccess) living).meridian$markRefreshPending();
            }
        });
    }

    public static HealingConfig config() {
        // 配置只在启动时载入，避免一次伤害前后采用不同分配规则。
        return config;
    }

    public static Optional<HealingBridge.Binding> inspect(LivingEntity entity) {
        // 世界生成和未入世实体只保留本地 NBT，不能触碰主线程身体系统。
        if (!canSynchronize(entity)) {
            return Optional.empty();
        }
        if (((HealingEntityAccess) entity).meridian$healthPaused()) {
            throw new HealingErrors.HostRejected(FailureCode.BODY_PAUSED);
        }
        return bridge.inspect(entity.getStringUUID());
    }

    private static boolean canSynchronize(LivingEntity entity) {
        // 先检查线程再查询实体索引，避免工作线程读取服务器集合。
        if (!(entity.level() instanceof ServerLevel world) || !world.getServer().isSameThread()) {
            return false;
        }
        return !((HealingEntityAccess) entity).meridian$loadingEffects()
                && world.getEntity(entity.getUUID()) == entity && !entity.isRemoved();
    }

    public static boolean interceptHeal(LivingEntity entity, float vanillaHp) {
        // 已绑定即截断原版 heal，满血、死体或无效剂量也不能回落到原版加血。
        try {
            Optional<HealingBridge.Binding> binding = inspect(entity);
            if (binding.isEmpty()) {
                return false;
            }
            heal(entity, binding.get(), vanillaHp);
        } catch (RuntimeException failure) {
            report(entity, failure);
        }
        return true;
    }

    public static boolean blocksVanillaHealth(LivingEntity entity) {
        // 原版拦截只需判断归属；宿主拒绝时也封住旁路，错误仍明确记录。
        try {
            return inspect(entity).isPresent();
        } catch (RuntimeException failure) {
            report(entity, failure);
            return true;
        }
    }

    public static double heal(LivingEntity entity, HealingBridge.Binding binding, double vanillaHp) {
        // 先计算可治疗部分，再请求核心；饥饿消耗只使用已提交的实际回血量。
        if (!canSynchronize(entity)) {
            return 0;
        }
        BodySnapshot body = binding.body();
        if (!Double.isFinite(vanillaHp) || vanillaHp <= 0 || body.getVitality().getDead()) {
            sync(entity, body);
            return 0;
        }
        var treatment = HealingRules.distribute(body, vanillaHp * config.hpPerVanillaHp());
        BodySnapshot updated = body;
        if (treatment.getDosesCount() > 0) {
            updated = bridge.apply(binding, BodyCommand.newBuilder().setHealBody(treatment));
        }
        sync(entity, updated);
        return Math.max(0, HealingRules.partHp(updated) - HealingRules.partHp(body)) / config.hpPerVanillaHp();
    }

    public static boolean interceptSetHealth(LivingEntity entity) {
        // 原版 HP 只是投影；包括第三方直接 setHealth 在内都不能绕过身体治疗或伤害。
        HealingEntityAccess access = (HealingEntityAccess) entity;
        if (access.meridian$internalHealth()) {
            return false;
        }
        try {
            Optional<HealingBridge.Binding> binding = inspect(entity);
            if (binding.isEmpty()) {
                return false;
            }
            sync(entity, binding.get().body());
        } catch (RuntimeException failure) {
            report(entity, failure);
        }
        return true;
    }

    public static void sync(LivingEntity entity, BodySnapshot body) {
        // 血条按全身部位 HP 比例显示；临时黄心总量只作为视觉汇总。
        if (!canSynchronize(entity)) {
            return;
        }
        var access = (HealingEntityAccess) entity;
        access.meridian$effects().bind(body);
        double maximum = HealingRules.partMaximum(body);
        double ratio = 0;
        if (!body.getVitality().getDead() && maximum > 0) {
            ratio = Math.min(1, HealingRules.partHp(body) / maximum);
        }
        float displayedHp = (float) (entity.getMaxHealth() * ratio);
        if (!body.getVitality().getDead() && displayedHp == 0 && ratio > 0) {
            displayedHp = Float.MIN_NORMAL;
        }
        access.meridian$internalHealth(true);
        try {
            entity.setHealth(displayedHp);
            double shield = access.meridian$effects().totalShield() / config.hpPerVanillaHp();
            access.meridian$displayAbsorption((float) shield);
        } finally {
            access.meridian$internalHealth(false);
        }
    }

    public static void refresh(LivingEntity entity) {
        // 每秒及效果边界对齐显示；没有差异时只查询，不产生核心 apply 或回执。
        if (!canSynchronize(entity) || ((HealingEntityAccess) entity).meridian$healthPaused()) {
            return;
        }
        try {
            Optional<HealingBridge.Binding> binding = inspect(entity);
            ((HealingEntityAccess) entity).meridian$clearRefreshPending();
            if (binding.isEmpty()) {
                return;
            }
            var state = ((HealingEntityAccess) entity).meridian$effects();
            state.bind(binding.get().body());
            var effect = entity.getEffect(MobEffects.ABSORPTION);
            int level = 0;
            if (effect != null) {
                level = effect.getAmplifier() + 1;
            }
            if (state.absorptionLevel() != level) {
                state.grantAbsorption(binding.get().body(), level, config);
            }
            BodySnapshot body = refreshBoost(entity, binding.get());
            if (body.getVitality().getDead()) {
                state.clearAbsorption();
            }
            sync(entity, body);
        } catch (RuntimeException failure) {
            report(entity, failure);
        }
    }

    private static BodySnapshot refreshBoost(LivingEntity entity, HealingBridge.Binding binding) {
        // 用本附属旧增量还原基准；新增容量不送血，撤销时由核心裁剪超额 HP。
        BodySnapshot body = binding.body();
        // 原版 NBT 可以直接载入属性修正；仅移除此效果的修正，保留装备与其他模组来源。
        // 修正标识与等级无关，按零级模板取出即可覆盖任意等级的残留。
        MobEffects.HEALTH_BOOST.value().createModifiers(0, (attribute, modifier) -> {
            var instance = entity.getAttribute(attribute);
            if (instance != null) {
                instance.removeModifier(modifier.id());
            }
        });
        if (body.getVitality().getDead()) {
            return body;
        }
        var state = ((HealingEntityAccess) entity).meridian$effects();
        var effect = entity.getEffect(MobEffects.HEALTH_BOOST);
        double totalGrant = 0;
        if (config.healthBoostChangesLimits() && effect != null) {
            totalGrant = 4.0 * (effect.getAmplifier() + 1) * config.hpPerVanillaHp();
        }
        var grants = new LinkedHashMap<String, Double>();
        var command = SetPartHealthLimits.newBuilder().setSourceId("meridian_vanilla:health_boost");
        var previousGrants = state.boostGrants();
        for (var part : body.getStructure().getPartsList()) {
            double grant = totalGrant / body.getStructure().getPartsCount();
            double previous = previousGrants.getOrDefault(part.getId(), 0.0);
            if (grant > 0) {
                grants.put(part.getId(), grant);
            }
            if (grant != previous) {
                double maximum = part.getMaxHp() - previous + grant;
                command.addLimits(PartHealthLimit.newBuilder().setPartId(part.getId()).setMaxHp(maximum));
            }
        }
        if (command.getLimitsCount() > 0) {
            body = bridge.apply(binding, BodyCommand.newBuilder().setSetPartHealthLimits(command));
        }
        state.recordBoost(grants);
        return body;
    }

    public static boolean absorptionChanged(LivingEntity entity, int level) {
        // 效果应用、刷新和移除都立即生效；存档重载由已保存余额恢复，不能重新补满。
        try {
            Optional<HealingBridge.Binding> binding = inspect(entity);
            if (binding.isEmpty()) {
                return false;
            }
            var state = ((HealingEntityAccess) entity).meridian$effects();
            state.grantAbsorption(binding.get().body(), level, config);
            sync(entity, binding.get().body());
        } catch (RuntimeException failure) {
            report(entity, failure);
        }
        return true;
    }

    public static boolean naturalRegeneration(GameRules rules) {
        // 默认尊重世界规则；关闭此配置仅使已绑定实体的回血忽略该游戏规则。
        return !config.respectNaturalRegeneration() || rules.get(GameRules.NATURAL_HEALTH_REGENERATION);
    }

    public static void beginDamage(LivingEntity entity) {
        // 上下文只在同步伤害调用栈中存活，不缓存世界实体或另建身份注册表。
        if (canSynchronize(entity)) {
            DAMAGE_STACK.get().push(entity);
        }
    }

    public static void endDamage(LivingEntity entity) {
        // 嵌套伤害逐层退出，避免内层覆盖外层的图腾和局部黄心状态。
        if (!entity.level().isClientSide()) {
            Deque<LivingEntity> entities = DAMAGE_STACK.get();
            if (!entities.isEmpty() && entities.peek() == entity) {
                entities.pop();
            }
            if (entities.isEmpty()) {
                DAMAGE_STACK.remove();
            }
        }
    }

    static LivingEntity damageEntity(String subjectId) {
        // 集成请求只能操作当前调用栈里的同一实体，不能借可信入口改动旁人效果。
        return DAMAGE_STACK.get().stream().filter(entity -> entity.getStringUUID().equals(subjectId))
                .findFirst().orElseThrow(() -> HealingErrors.invalid(HealingErrors.HOST));
    }

    public static void report(LivingEntity entity, RuntimeException failure) {
        // 暂停只告警一次并停止自动查询，其他故障也只记录首个堆栈。
        if (!canSynchronize(entity)) {
            return;
        }
        var access = (HealingEntityAccess) entity;
        if (failure instanceof HealingErrors.HostRejected rejected && rejected.code() == FailureCode.BODY_PAUSED) {
            access.meridian$pauseHealth();
            access.meridian$clearRefreshPending();
            if (!access.meridian$failureReported()) {
                access.meridian$markFailureReported();
                LOGGER.warn(HealingErrors.BODY_PAUSED + " subject={}", entity.getStringUUID());
            }
            return;
        }
        if (access.meridian$failureReported()) {
            return;
        }
        access.meridian$markFailureReported();
        LOGGER.error(HealingErrors.HEAL_FAILED + " subject=" + entity.getStringUUID(), failure);
    }
}
