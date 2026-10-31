package dev.kizuna.meridian.healing.test;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyStructure;
import dev.kizuna.meridian.core.BodyTemplate;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.core.VitalityState;
import dev.kizuna.meridian.healing.HealingRules;
import dev.kizuna.meridian.healing.fabric.HealingEntityAccess;
import dev.kizuna.meridian.healing.fabric.HealingFabric;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvanceMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** 真实原版事件、伤害附属与核心账本的整体回归，样例内容不进入发布包。 */
public final class HealingGameTest implements BodyHealthHost {
    private static final InMemoryBodyStore STORE = new InMemoryBodyStore(List.of(), List.of());
    private static final Set<String> REJECTED_SUBJECTS = new HashSet<>();
    private static final Set<String> PAUSED_SUBJECTS = new HashSet<>();
    private static final AtomicInteger HOST_QUERIES = new AtomicInteger();
    private static final int PAUSED_TICKS = 41;
    private static final int EFFECT_TICKS = 100;
    private static final double DAMAGED_PART_HP = 40;
    private static final double DAMAGED_RATIO = 0.4;
    private static final BodyCommandHandler<String> COMMANDS = new BodyCommandHandler<>(
            (caller, command) -> Optional.of(caller), (caller, command, body) -> true,
            (caller, body) -> TimeAdvancePlan.newBuilder().setTarget(BodyProtobuf.toTime(body.settledAt()))
                    .setMode(TimeAdvanceMode.ACTIVE).setBiologicalSecondsPerSimulationSecond(1).build(),
            STORE, (caller, receipt, previous, updated) -> { /* 断言读取实际已提交身体。 */ });

    @GameTest
    public void workerNbtDefersHostAccessUntilEntityEntersWorld(GameTestHelper context) {
        // 下界生成会在工作线程构造炽足兽；构造期初始血量必须完整建立。
        int beforeConstruction = HOST_QUERIES.get();
        var strider = CompletableFuture.supplyAsync(
                () -> EntityTypes.STRIDER.create(context.getLevel(), EntitySpawnReason.CHUNK_GENERATION)).join();
        context.assertTrue(strider != null, "实际构造炽足兽");
        close(context, strider.getHealth(), strider.getMaxHealth(), "工作线程构造保留初始满血");
        context.assertTrue(!((HealingEntityAccess) strider).meridian$internalHealth(), "构造后恢复权威健康拦截");
        context.assertTrue(HOST_QUERIES.get() == beforeConstruction, "构造期不能查询宿主");
        verifyDeferredGolem(context);
        context.succeed();
    }

    private static void verifyDeferredGolem(GameTestHelper context) {
        // 带实际临时效果的铁傀儡存档在工作线程读取，余额只在入世后的主线程投影。
        var original = context.spawn(EntityTypes.IRON_GOLEM, 1, 2, 1);
        bind(original, DAMAGED_PART_HP, DAMAGED_PART_HP);
        original.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, EFFECT_TICKS, 0));
        CompoundTag saved = save(original);
        // 存档带原实体 UUID；读入的新实体保留自己的身份，不与仍在世界中的原铁傀儡重复。
        saved.remove(Entity.TAG_UUID);
        int beforeRead = HOST_QUERIES.get();
        var restored = CompletableFuture.supplyAsync(() -> {
            var golem = EntityTypes.IRON_GOLEM.create(context.getLevel(), EntitySpawnReason.LOAD);
            load(golem, saved);
            HealingFabric.refresh(golem);
            if (HealingFabric.inspect(golem).isPresent()) {
                throw new AssertionError("工作线程不能认领身体");
            }
            return golem;
        }).join();
        HealingFabric.refresh(restored);
        context.assertTrue(HOST_QUERIES.get() == beforeRead, "异步或未入世 NBT 不得查询宿主");
        var access = (HealingEntityAccess) restored;
        context.assertTrue(access.meridian$refreshPending(), "NBT 未保留待刷新标记");
        registerBody(restored, DAMAGED_PART_HP, DAMAGED_PART_HP);
        context.assertTrue(context.getLevel().addFreshEntity(restored), "铁傀儡成功加入世界");
        restored.tick();
        context.assertTrue(!access.meridian$refreshPending(), "首次主线程 tick 未完成同步");
        close(context, restored.getHealth(), restored.getMaxHealth() * DAMAGED_RATIO, "入世后投影身体比例");
        close(context, restored.getAbsorptionAmount(), original.getAbsorptionAmount(), "NBT 保留临时黄心余额");
        restored.setHealth(restored.getMaxHealth());
        close(context, hp(restored, 0), DAMAGED_PART_HP, "运行期 setter 不得绕过身体");
    }

    @GameTest
    public void pausedBodyStopsQueriesAndBlocksVanillaHealing(GameTestHelper context) {
        // 暂停只查询和报告一次，后续 tick、治疗与 setter 都不能回落原版账本。
        var player = TestPlayers.detached(context.getLevel(), "healing_paused");
        bind(player, DAMAGED_PART_HP, DAMAGED_PART_HP);
        float displayedHp = player.getHealth();
        long revision = body(player).revision();
        double exhaustion = exhaustion(player.getFoodData());
        PAUSED_SUBJECTS.add(player.getStringUUID());
        int beforePause = HOST_QUERIES.get();
        try {
            HealingFabric.refresh(player);
            for (int tick = 0; tick < PAUSED_TICKS; tick++) {
                HealingFabric.refresh(player);
                player.heal(1);
                player.setHealth(player.getMaxHealth());
                player.doTick();
            }
            var access = (HealingEntityAccess) player;
            context.assertTrue(access.meridian$healthPaused(), "暂停状态未保留");
            context.assertTrue(access.meridian$failureReported(), "暂停没有首次告警");
            context.assertTrue(HOST_QUERIES.get() == beforePause + 1, "暂停后仍查询宿主");
            close(context, player.getHealth(), displayedHp, "暂停不能回落原版回血");
            close(context, body(player).revision(), revision, "暂停不能提交身体变化");
            close(context, exhaustion(player.getFoodData()), exhaustion, "未回血不收费");
        } finally {
            PAUSED_SUBJECTS.remove(player.getStringUUID());
        }
        context.succeed();
    }

    @GameTest
    public void healingAndEffectsUseBodyHpWithoutRestoringDestroyedParts(GameTestHelper context) {
        // heal、瞬间治疗和生命恢复都走同一身体治疗入口，过量重新分配。
        var player = context.makeMockPlayer(GameType.SURVIVAL);
        bind(player, 99, 50, 0);
        player.heal(11);
        close(context, hp(player, 0), 100, "小缺口封顶");
        close(context, hp(player, 1), 60, "溢出重新均分");
        close(context, hp(player, 2), 0, "毁坏部位不治疗");
        player.setHealth(player.getMaxHealth());
        close(context, hp(player, 1), 60, "直接 setter 不能绕过身体账本");
        close(context, player.getHealth(), player.getMaxHealth() * 160.0 / 300, "血条只显示身体比例");
        MobEffects.INSTANT_HEALTH.value().applyInstantaneousEffect(context.getLevel(), null, null, player, 0, 1);
        close(context, hp(player, 1), 64, "瞬间治疗总量4");
        MobEffects.REGENERATION.value().applyEffectTick(context.getLevel(), player, 0);
        close(context, hp(player, 1), 65, "生命恢复每跳总量1");
        player.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, 100, 1));
        double boost = 0;
        if (HealingFabric.config().healthBoostChangesLimits()) {
            boost = 8.0 / 3;
        }
        close(context, body(player).structure().parts().get(0).maxHp(), 100 + boost, "生命提升依配置即时改变身体上限");
        close(context, hp(player, 1), 65, "扩大上限不赠送HP");
        close(context, player.getMaxHealth(), 20, "生命提升不能制造额外原版红心");
        load(player, save(player));
        close(context, body(player).structure().parts().get(0).maxHp(), 100 + boost, "重载不重复增加上限");
        close(context, player.getMaxHealth(), 20, "重载不恢复原版生命提升属性");
        player.removeEffect(MobEffects.HEALTH_BOOST);
        close(context, body(player).structure().parts().get(0).maxHp(), 100, "效果移除即时撤销上限");

        // 实际物品消费仍完成，治疗剂量经过同一公开 heal 拦截。
        ItemStack potion = PotionContents.createItemStack(Items.POTION, Potions.HEALING);
        ItemStack remainder = potion.finishUsingItem(player.level(), player);
        context.assertTrue(remainder.is(Items.GLASS_BOTTLE), "治疗药水正常饮用并留下瓶子");
        close(context, hp(player, 1), 69, "实际药水的4HP进入身体");
        ItemStack apple = new ItemStack(Items.GOLDEN_APPLE);
        apple.finishUsingItem(player.level(), player);
        context.assertTrue(apple.isEmpty() && player.hasEffect(MobEffects.REGENERATION)
                && player.hasEffect(MobEffects.ABSORPTION), "金苹果照常食用并授予效果");

        // 普通生物也走 heal，未绑定者以及亡灵瞬间治疗伤害保持原版分支。
        var cow = context.spawn(EntityTypes.COW, 2, 2, 2);
        bind(cow, 40, 40);
        cow.heal(4);
        close(context, hp(cow, 0), 42, "普通生物均分");
        close(context, hp(cow, 1), 42, "普通生物均分");
        var unbound = context.spawn(EntityTypes.COW, 3, 2, 3);
        unbound.setHealth(5);
        unbound.heal(2);
        close(context, unbound.getHealth(), 7, "未绑定保留原版");
        var undead = context.spawn(EntityTypes.HUSK, 4, 2, 4);
        undead.setNoAi(true);
        bind(undead, 100);
        MobEffects.INSTANT_HEALTH.value().applyInstantaneousEffect(context.getLevel(), null, null, undead, 0, 1);
        close(context, hp(undead, 0), 94, "亡灵治疗仍作为6伤害交给伤害附属");
        context.succeed();
    }

    @GameTest
    public void rejectedHostStopsEffectsWithoutCrashingPlayerTickOrVanillaHealing(GameTestHelper context) {
        // 模拟已认领身体在结算时失败，实际执行饥饿、和平回血和效果 mixin。
        var player = TestPlayers.detached(context.getLevel(), "healing_reject");
        bind(player, 40, 40);
        float displayedHp = player.getHealth();
        long revision = body(player).revision();
        double exhaustion = exhaustion(player.getFoodData());
        REJECTED_SUBJECTS.add(player.getStringUUID());
        try {
            player.heal(4);
            player.setHealth(player.getMaxHealth());
            player.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, 100, 1));
            for (int tick = 0; tick < 21; tick++) {
                player.doTick();
            }
            close(context, player.getHealth(), displayedHp, "失败不能回落到原版回血");
            close(context, player.getMaxHealth(), 20, "失败不能添加原版生命上限");
            close(context, body(player).revision(), revision, "失败不提交身体变化");
            close(context, exhaustion(player.getFoodData()), exhaustion, "未回血不收费");
        } finally {
            // 测试失败也释放拒绝夹具，不影响其他真实服务器场景。
            REJECTED_SUBJECTS.remove(player.getStringUUID());
        }
        context.succeed();
    }

    @GameTest
    public void localShieldsPersistAndTotemPreventsBodyDeath(GameTestHelper context) {
        // 黄心先完全吸收一击，不产生身体事务；再次授予后验证局部余额不能互借。
        var player = context.makeMockPlayer(GameType.SURVIVAL);
        bind(player, 100, 100);
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0));
        double perPart = 2;
        if (!HealingFabric.config().sharedAbsorption()) {
            perPart = 4;
        }
        close(context, player.getAbsorptionAmount(), perPart * 2, "黄心分配符合配置");
        long revision = body(player).revision();
        player.hurt(player.damageSources().generic(), 1);
        close(context, body(player).revision(), revision, "完全吸收不产生伪零伤害事务");
        close(context, player.getAbsorptionAmount(), perPart * 2 - 1, "完全吸收也扣局部黄心");
        player.removeEffect(MobEffects.ABSORPTION);
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0));
        player.invulnerableTime = 0;
        player.hurt(player.damageSources().generic(), (float) (perPart + 1));
        close(context, body(player).partStates().stream().mapToDouble(part -> part.hp()).sum(), 199, "局部黄心不跨部位借用");
        close(context, player.getAbsorptionAmount(), perPart, "只扣命中部位黄心");
        load(player, save(player));
        HealingFabric.refresh(player);
        close(context, player.getAbsorptionAmount(), perPart, "存档重载不能补满黄心");
        new ItemStack(Items.MILK_BUCKET).finishUsingItem(player.level(), player);
        close(context, player.getAbsorptionAmount(), 0, "牛奶清空局部黄心");
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 1, 0));
        player.tick();
        close(context, player.getAbsorptionAmount(), 0, "效果自然到期清除所有局部黄心");

        // 图腾先阻止身体清账，再复用原版消费、授予效果和实体状态。
        var fragile = context.spawn(EntityTypes.COW, 2, 2, 2);
        bind(fragile, 1);
        fragile.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        fragile.hurt(fragile.damageSources().generic(), 10);
        if (HealingFabric.config().totemEnabled()) {
            context.assertTrue(!body(fragile).vitality().dead() && fragile.isAlive(), "图腾保住身体和原版实体");
            close(context, hp(fragile, 0), 25, "图腾默认下限25%");
            context.assertTrue(fragile.getOffhandItem().isEmpty(), "图腾恰好消费一个");
            context.assertTrue(fragile.hasEffect(MobEffects.REGENERATION)
                    && fragile.hasEffect(MobEffects.ABSORPTION), "保留原版图腾后续效果");
        } else {
            context.assertTrue(body(fragile).vitality().dead() && !fragile.isAlive(), "禁用图腾时正常死亡");
            context.assertTrue(!fragile.getOffhandItem().isEmpty(), "禁用时不消耗图腾");
        }
        var doomed = context.spawn(EntityTypes.COW, 3, 2, 3);
        bind(doomed, 1);
        doomed.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        doomed.hurt(doomed.damageSources().fellOutOfWorld(), 10);
        context.assertTrue(body(doomed).vitality().dead() && !doomed.isAlive(), "虚空伤害仍绕过图腾");
        context.assertTrue(!doomed.getOffhandItem().isEmpty(), "禁用图腾来源不消耗图腾");
        context.succeed();
    }

    @GameTest
    public void naturalHealingHalvesDoseAndChargesActualRecovery(GameTestHelper context) {
        // 保留原版十tick饱和回血条件，回复0.5而不是1，exhaustion应为3。
        var player = TestPlayers.detached(context.getLevel(), "healing_natural");
        bind(player, 99.9, 99.9);
        var hunger = player.getFoodData();
        hunger.setFoodLevel(20);
        hunger.setSaturation(6);
        CompoundTag initial = foodTag(hunger);
        initial.putFloat("foodExhaustionLevel", 0);
        initial.putInt("foodTickTimer", 0);
        hunger.readAdditionalSaveData(
                TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), initial));
        for (int tick = 0; tick < 10; tick++) {
            hunger.tick(player);
        }
        close(context, hp(player, 0), 100, "自然回血实际填满0.2HP");
        close(context, hp(player, 1), 100, "自然回血均分");
        close(context, exhaustion(hunger), 1.2, "饥饿按实际0.2HP计算，不按名义0.5");

        var slow = TestPlayers.detached(context.getLevel(), "healing_slow");
        bind(slow, 80, 80);
        slow.getFoodData().setFoodLevel(20);
        slow.getFoodData().setSaturation(6);
        for (int tick = 0; tick < 10; tick++) {
            slow.getFoodData().tick(slow);
        }
        close(context, hp(slow, 0) + hp(slow, 1), 160.5, "自然回血50%倍率");
        var hungry = TestPlayers.detached(context.getLevel(), "healing_hungry");
        bind(hungry, 80, 80);
        hungry.getFoodData().setFoodLevel(18);
        hungry.getFoodData().setSaturation(0);
        for (int tick = 0; tick < 80; tick++) {
            hungry.getFoodData().tick(hungry);
        }
        close(context, hp(hungry, 0) + hp(hungry, 1), 160.5, "低饱和80tick路径同样减半");
        var rules = player.level().getGameRules();
        boolean previous = rules.get(GameRules.NATURAL_HEALTH_REGENERATION);
        try {
            rules.set(GameRules.NATURAL_HEALTH_REGENERATION, false, player.level().getServer());
            for (int tick = 0; tick < 100; tick++) {
                slow.getFoodData().tick(slow);
            }
            if (HealingFabric.config().respectNaturalRegeneration()) {
                close(context, hp(slow, 0) + hp(slow, 1), 160.5, "禁用自然恢复规则时停止回血");
            } else {
                context.assertTrue(hp(slow, 0) + hp(slow, 1) > 160.5, "显式配置可覆盖自然恢复规则");
            }
        } finally {
            rules.set(GameRules.NATURAL_HEALTH_REGENERATION, previous, player.level().getServer());
        }
        context.succeed();
    }

    @GameTest
    public void peacefulHealingRespectsDifficultyAndWorldRule(GameTestHelper context) {
        // 全部调用在同一服务器任务内完成，结束前还原世界设置，不影响其他测试。
        var player = TestPlayers.detached(context.getLevel(), "healing_peace");
        bind(player, 80, 80);
        var server = player.level().getServer();
        Difficulty previousDifficulty = server.getWorldData().getDifficulty();
        var rules = player.level().getGameRules();
        boolean previousRule = rules.get(GameRules.NATURAL_HEALTH_REGENERATION);
        try {
            rules.set(GameRules.NATURAL_HEALTH_REGENERATION, true, server);
            server.setDifficulty(Difficulty.PEACEFUL, true);
            player.tickCount = 20;
            player.aiStep();
            close(context, hp(player, 0) + hp(player, 1), 161, "和平回血总量1均分到身体");
            rules.set(GameRules.NATURAL_HEALTH_REGENERATION, false, server);
            player.aiStep();
            double expected = 161;
            if (!HealingFabric.config().respectNaturalRegeneration()) {
                expected++;
            }
            close(context, hp(player, 0) + hp(player, 1), expected, "和平回血遵守自然恢复配置");
            server.setDifficulty(Difficulty.NORMAL, true);
            rules.set(GameRules.NATURAL_HEALTH_REGENERATION, true, server);
            player.getFoodData().setFoodLevel(10);
            player.aiStep();
            if (!HealingFabric.config().respectPeaceful()) {
                expected++;
            }
            close(context, hp(player, 0) + hp(player, 1), expected, "其他难度仅在显式配置时开启和平回血");
            close(context, player.getFoodData().getFoodLevel(), 10, "难度覆盖不顺带开启补食");
        } finally {
            server.setDifficulty(previousDifficulty, true);
            rules.set(GameRules.NATURAL_HEALTH_REGENERATION, previousRule, server);
        }
        context.succeed();
    }

    @Override
    public byte[] exchange(byte[] payload) {
        // 仅认领测试中登记的实体，实际操作经过正式命令处理器和事务存储。
        var request = HealthHostProtobuf.decodeRequest(payload);
        HOST_QUERIES.incrementAndGet();
        var response = HealthHostResponse.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(request.getRequestId());
        if (PAUSED_SUBJECTS.contains(request.getSubjectId())) {
            RequestFailure failure = RequestFailure.newBuilder().setCode(FailureCode.BODY_PAUSED).build();
            return HealthHostProtobuf.encodeResponse(response.setFailure(failure).build());
        }
        if (REJECTED_SUBJECTS.contains(request.getSubjectId())) {
            RequestFailure failure = RequestFailure.newBuilder().setCode(FailureCode.INTERNAL_ERROR).build();
            return HealthHostProtobuf.encodeResponse(response.setFailure(failure).build());
        }
        var found = STORE.find(new BodyId(request.getSubjectId()));
        if (found.isEmpty()) {
            return HealthHostProtobuf.encodeResponse(response.setUnbound(true).build());
        }
        if (request.hasEffect()) {
            var result = MeridianProtocol.decodeResponse(COMMANDS.handleHealthEffect("healing-test", request.getEffect().toByteArray()));
            if (result.hasFailure()) {
                return HealthHostProtobuf.encodeResponse(response.setFailure(result.getFailure()).build());
            }
            dev.kizuna.meridian.protocol.pb.HealthMessages.HealthResult fact = STORE.transact(found.get().id(), transaction ->
                    transaction.receipt("healing-test", request.getRequestId()).orElseThrow().getHealth());
            response.setHealth(fact);
        }
        return HealthHostProtobuf.encodeResponse(response.setBody(BodyProtobuf.toMessage(
                STORE.find(found.get().id()).orElseThrow())).build());
    }

    private static void bind(LivingEntity entity, double... hp) {
        // 使用真实入世实体验证运行期门禁，未加入世界的对象不能触发身体同步。
        registerBody(entity, hp);
        if (entity.level().getEntity(entity.getId()) != entity) {
            entity.level().addFreshEntity(entity);
        }
        HealingFabric.refresh(entity);
    }

    private static void registerBody(LivingEntity entity, double... hp) {
        // 任意部位数的非人体构型，保护框架独立性及宿主协议边界。
        var parts = new ArrayList<Anatomy.PartDefinition>();
        var states = new ArrayList<AnatomyState.Part>();
        for (int index = 0; index < hp.length; index++) {
            var id = new Anatomy.PartId("section_" + index);
            parts.add(new Anatomy.PartDefinition(id, "样例", 100));
            states.add(new AnatomyState.Part(id, hp[index]));
        }
        var structure = new BodyStructure(parts, List.of(), List.of(), List.of(), List.of());
        STORE.registerBody(new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId(entity.getStringUUID()),
                new BodyTemplate.Reference(new DefinitionId("sample:creature"), 1), structure, Realm.WANDERING_QI,
                states, List.of(), List.of(), List.of(), List.of(), new VitalityState(0, 1000, 0, false),
                new TimeCursor(new DefinitionId("sample:world"), "epoch", 0), 0));
    }

    private static CompoundTag save(LivingEntity entity) {
        // 受保护的存档方法只能经公开入口调用；得到的标签与正式实体存档相同。
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
    }

    private static void load(LivingEntity entity, CompoundTag saved) {
        // 与区块读取相同走公开 load，再进入实体自身的附加存档读取。
        entity.load(TagValueInput.create(ProblemReporter.DISCARDING, entity.registryAccess(), saved));
    }

    private static CompoundTag foodTag(FoodData food) {
        // 饥饿数据不公开消耗度，按正式存档字段读写。
        var output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        food.addAdditionalSaveData(output);
        return output.buildResult();
    }

    private static double exhaustion(FoodData food) {
        // 缺字段返回 NaN，使断言失败而不是当作零消耗。
        return foodTag(food).getFloatOr("foodExhaustionLevel", Float.NaN);
    }

    private static BodySnapshot body(LivingEntity entity) {
        // 断言只读权威账本，不以原版血条是否变化代替身体结算证据。
        return STORE.find(new BodyId(entity.getStringUUID())).orElseThrow();
    }

    private static double hp(LivingEntity entity, int part) {
        // 测试样例顺序固定，避免断言绕回被测治疗算法。
        return body(entity).partStates().get(part).hp();
    }

    private static void close(GameTestHelper context, double actual, double expected, String reason) {
        // 原版显示使用float，核心用double，允许正常表示误差。
        context.assertTrue(Math.abs(actual - expected) < 1e-4, reason + ": actual=" + actual + ", expected=" + expected);
    }
}
