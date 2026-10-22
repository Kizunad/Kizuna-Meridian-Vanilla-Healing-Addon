package dev.kizuna.meridian.healing.test;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.healing.HealingRules;
import dev.kizuna.meridian.healing.fabric.HealingEntityAccess;
import dev.kizuna.meridian.healing.fabric.HealingFabric;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvanceMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.potion.PotionUtil;
import net.minecraft.potion.Potions;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.world.GameRules;
import net.minecraft.world.Difficulty;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 真实原版事件、伤害附属与核心账本的整体回归，样例内容不进入发布包。 */
public final class HealingGameTest implements FabricGameTest, BodyHealthHost {
    private static final InMemoryBodyStore STORE = new InMemoryBodyStore(List.of(), List.of());
    private static final java.util.Set<String> REJECTED_SUBJECTS = new java.util.HashSet<>();
    private static final BodyCommandHandler<String> COMMANDS = new BodyCommandHandler<>(
            (caller, command) -> Optional.of(caller), (caller, command, body) -> true,
            (caller, body) -> TimeAdvancePlan.newBuilder().setTarget(BodyProtobuf.toTime(body.settledAt()))
                    .setMode(TimeAdvanceMode.ACTIVE).setBiologicalSecondsPerSimulationSecond(1).build(),
            STORE, (caller, receipt, previous, updated) -> { /* 断言读取实际已提交身体。 */ });

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void workerConstructionInitializesHealthAndPreservesHostThreadChecks(TestContext context) {
        // 下界生成会在工作线程构造炽足兽；构造期初始血量必须完整建立。
        var entity = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> EntityType.STRIDER.create(context.getWorld())).join();
        context.assertTrue(entity != null, "实际构造炽足兽");
        close(context, entity.getHealth(), entity.getMaxHealth(), "工作线程构造保留初始满血");
        context.assertTrue(!((HealingEntityAccess) entity).meridian$internalHealth(), "构造后恢复权威健康拦截");

        // 运行期查询仍需通过 Human 的主线程校验，不能以构造修复为由吞掉拒绝。
        boolean rejected = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                HealingFabric.inspect(entity);
                return false;
            } catch (IllegalArgumentException expected) {
                return true;
            }
        }).join();
        context.assertTrue(rejected, "运行期异步健康查询仍明确失败");

        // 同一实体完成绑定后，第三方 setter 继续只能投影身体 HP。
        bind(entity, 40, 40);
        entity.setHealth(entity.getMaxHealth());
        close(context, hp(entity, 0) + hp(entity, 1), 80, "初始赋值路径不泄漏到运行期");
        close(context, entity.getHealth(), entity.getMaxHealth() * .4, "血条由身体比例决定");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void healingAndEffectsUseBodyHpWithoutRestoringDestroyedParts(TestContext context) {
        // heal、瞬间治疗和生命恢复都走同一身体治疗入口，过量重新分配。
        var player = context.createMockSurvivalPlayer();
        bind(player, 99, 50, 0);
        player.heal(11);
        close(context, hp(player, 0), 100, "小缺口封顶");
        close(context, hp(player, 1), 60, "溢出重新均分");
        close(context, hp(player, 2), 0, "毁坏部位不治疗");
        player.setHealth(player.getMaxHealth());
        close(context, hp(player, 1), 60, "直接 setter 不能绕过身体账本");
        close(context, player.getHealth(), player.getMaxHealth() * 160.0 / 300, "血条只显示身体比例");
        StatusEffects.INSTANT_HEALTH.applyInstantEffect(null, null, player, 0, 1);
        close(context, hp(player, 1), 64, "瞬间治疗总量4");
        StatusEffects.REGENERATION.applyUpdateEffect(player, 0);
        close(context, hp(player, 1), 65, "生命恢复每跳总量1");
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.HEALTH_BOOST, 100, 1));
        double boost = 0;
        if (HealingFabric.config().healthBoostChangesLimits()) {
            boost = 8.0 / 3;
        }
        close(context, body(player).structure().parts().get(0).maxHp(), 100 + boost, "生命提升依配置即时改变身体上限");
        close(context, hp(player, 1), 65, "扩大上限不赠送HP");
        close(context, player.getMaxHealth(), 20, "生命提升不能制造额外原版红心");
        NbtCompound boosted = new NbtCompound();
        player.writeCustomDataToNbt(boosted);
        player.readCustomDataFromNbt(boosted);
        close(context, body(player).structure().parts().get(0).maxHp(), 100 + boost, "重载不重复增加上限");
        close(context, player.getMaxHealth(), 20, "重载不恢复原版生命提升属性");
        player.removeStatusEffect(StatusEffects.HEALTH_BOOST);
        close(context, body(player).structure().parts().get(0).maxHp(), 100, "效果移除即时撤销上限");

        // 实际物品消费仍完成，治疗剂量经过同一公开 heal 拦截。
        ItemStack potion = PotionUtil.setPotion(new ItemStack(Items.POTION), Potions.HEALING);
        ItemStack remainder = Items.POTION.finishUsing(potion, player.getWorld(), player);
        context.assertTrue(remainder.isOf(Items.GLASS_BOTTLE), "治疗药水正常饮用并留下瓶子");
        close(context, hp(player, 1), 69, "实际药水的4HP进入身体");
        ItemStack apple = new ItemStack(Items.GOLDEN_APPLE);
        Items.GOLDEN_APPLE.finishUsing(apple, player.getWorld(), player);
        context.assertTrue(apple.isEmpty() && player.hasStatusEffect(StatusEffects.REGENERATION)
                && player.hasStatusEffect(StatusEffects.ABSORPTION), "金苹果照常食用并授予效果");

        // 普通生物也走 heal，未绑定者以及亡灵瞬间治疗伤害保持原版分支。
        var cow = context.spawnEntity(EntityType.COW, 2, 2, 2);
        bind(cow, 40, 40);
        cow.heal(4);
        close(context, hp(cow, 0), 42, "普通生物均分");
        close(context, hp(cow, 1), 42, "普通生物均分");
        var unbound = context.spawnEntity(EntityType.COW, 3, 2, 3);
        unbound.setHealth(5);
        unbound.heal(2);
        close(context, unbound.getHealth(), 7, "未绑定保留原版");
        var undead = context.spawnEntity(EntityType.HUSK, 4, 2, 4);
        undead.setAiDisabled(true);
        bind(undead, 100);
        StatusEffects.INSTANT_HEALTH.applyInstantEffect(null, null, undead, 0, 1);
        close(context, hp(undead, 0), 94, "亡灵治疗仍作为6伤害交给伤害附属");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void rejectedHostStopsEffectsWithoutCrashingPlayerTickOrVanillaHealing(TestContext context) {
        // 模拟已认领身体在结算时失败，实际执行饥饿、和平回血和效果 mixin。
        var player = context.createMockSurvivalPlayer();
        bind(player, 40, 40);
        float displayedHp = player.getHealth();
        long revision = body(player).revision();
        float exhaustion = player.getHungerManager().getExhaustion();
        REJECTED_SUBJECTS.add(player.getUuidAsString());
        try {
            player.heal(4);
            player.setHealth(player.getMaxHealth());
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.HEALTH_BOOST, 100, 1));
            for (int tick = 0; tick < 21; tick++) {
                player.tick();
            }
            close(context, player.getHealth(), displayedHp, "失败不能回落到原版回血");
            close(context, player.getMaxHealth(), 20, "失败不能添加原版生命上限");
            close(context, body(player).revision(), revision, "失败不提交身体变化");
            close(context, player.getHungerManager().getExhaustion(), exhaustion, "未回血不收费");
        } finally {
            // 测试失败也释放拒绝夹具，不影响其他真实服务器场景。
            REJECTED_SUBJECTS.remove(player.getUuidAsString());
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void localShieldsPersistAndTotemPreventsBodyDeath(TestContext context) {
        // 黄心先完全吸收一击，不产生身体事务；再次授予后验证局部余额不能互借。
        var player = context.createMockSurvivalPlayer();
        bind(player, 100, 100);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 200, 0));
        double perPart = 2;
        if (!HealingFabric.config().sharedAbsorption()) {
            perPart = 4;
        }
        close(context, player.getAbsorptionAmount(), perPart * 2, "黄心分配符合配置");
        long revision = body(player).revision();
        player.damage(player.getDamageSources().generic(), 1);
        close(context, body(player).revision(), revision, "完全吸收不产生伪零伤害事务");
        close(context, player.getAbsorptionAmount(), perPart * 2 - 1, "完全吸收也扣局部黄心");
        player.removeStatusEffect(StatusEffects.ABSORPTION);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 200, 0));
        player.timeUntilRegen = 0;
        player.damage(player.getDamageSources().generic(), (float) (perPart + 1));
        close(context, body(player).partStates().stream().mapToDouble(part -> part.hp()).sum(), 199, "局部黄心不跨部位借用");
        close(context, player.getAbsorptionAmount(), perPart, "只扣命中部位黄心");
        NbtCompound saved = new NbtCompound();
        player.writeCustomDataToNbt(saved);
        player.readCustomDataFromNbt(saved);
        HealingFabric.refresh(player);
        close(context, player.getAbsorptionAmount(), perPart, "存档重载不能补满黄心");
        Items.MILK_BUCKET.finishUsing(new ItemStack(Items.MILK_BUCKET), player.getWorld(), player);
        close(context, player.getAbsorptionAmount(), 0, "牛奶清空局部黄心");
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 1, 0));
        player.tick();
        close(context, player.getAbsorptionAmount(), 0, "效果自然到期清除所有局部黄心");

        // 图腾先阻止身体清账，再复用原版消费、授予效果和实体状态。
        var fragile = context.spawnEntity(EntityType.COW, 2, 2, 2);
        bind(fragile, 1);
        fragile.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        fragile.damage(fragile.getDamageSources().generic(), 10);
        if (HealingFabric.config().totemEnabled()) {
            context.assertTrue(!body(fragile).vitality().dead() && fragile.isAlive(), "图腾保住身体和原版实体");
            close(context, hp(fragile, 0), 25, "图腾默认下限25%");
            context.assertTrue(fragile.getOffHandStack().isEmpty(), "图腾恰好消费一个");
            context.assertTrue(fragile.hasStatusEffect(StatusEffects.REGENERATION)
                    && fragile.hasStatusEffect(StatusEffects.ABSORPTION), "保留原版图腾后续效果");
        } else {
            context.assertTrue(body(fragile).vitality().dead() && !fragile.isAlive(), "禁用图腾时正常死亡");
            context.assertTrue(!fragile.getOffHandStack().isEmpty(), "禁用时不消耗图腾");
        }
        var doomed = context.spawnEntity(EntityType.COW, 3, 2, 3);
        bind(doomed, 1);
        doomed.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        doomed.damage(doomed.getDamageSources().outOfWorld(), 10);
        context.assertTrue(body(doomed).vitality().dead() && !doomed.isAlive(), "虚空伤害仍绕过图腾");
        context.assertTrue(!doomed.getOffHandStack().isEmpty(), "禁用图腾来源不消耗图腾");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void naturalHealingHalvesDoseAndChargesActualRecovery(TestContext context) {
        // 保留原版十tick饱和回血条件，回复0.5而不是1，exhaustion应为3。
        var player = context.createMockSurvivalPlayer();
        bind(player, 99.9, 99.9);
        var hunger = player.getHungerManager();
        hunger.setFoodLevel(20);
        hunger.setSaturationLevel(6);
        NbtCompound initial = new NbtCompound();
        hunger.writeNbt(initial);
        initial.putFloat("foodExhaustionLevel", 0);
        initial.putInt("foodTickTimer", 0);
        hunger.readNbt(initial);
        for (int tick = 0; tick < 10; tick++) {
            hunger.update(player);
        }
        close(context, hp(player, 0), 100, "自然回血实际填满0.2HP");
        close(context, hp(player, 1), 100, "自然回血均分");
        NbtCompound after = new NbtCompound();
        hunger.writeNbt(after);
        close(context, after.getFloat("foodExhaustionLevel"), 1.2, "饥饿按实际0.2HP计算，不按名义0.5");

        var slow = context.createMockSurvivalPlayer();
        bind(slow, 80, 80);
        slow.getHungerManager().setFoodLevel(20);
        slow.getHungerManager().setSaturationLevel(6);
        for (int tick = 0; tick < 10; tick++) {
            slow.getHungerManager().update(slow);
        }
        close(context, hp(slow, 0) + hp(slow, 1), 160.5, "自然回血50%倍率");
        var hungry = context.createMockSurvivalPlayer();
        bind(hungry, 80, 80);
        hungry.getHungerManager().setFoodLevel(18);
        hungry.getHungerManager().setSaturationLevel(0);
        for (int tick = 0; tick < 80; tick++) {
            hungry.getHungerManager().update(hungry);
        }
        close(context, hp(hungry, 0) + hp(hungry, 1), 160.5, "低饱和80tick路径同样减半");
        var rules = player.getWorld().getGameRules();
        boolean previous = rules.getBoolean(GameRules.NATURAL_REGENERATION);
        try {
            rules.get(GameRules.NATURAL_REGENERATION).set(false, player.getServer());
            for (int tick = 0; tick < 100; tick++) {
                slow.getHungerManager().update(slow);
            }
            if (HealingFabric.config().respectNaturalRegeneration()) {
                close(context, hp(slow, 0) + hp(slow, 1), 160.5, "禁用自然恢复规则时停止回血");
            } else {
                context.assertTrue(hp(slow, 0) + hp(slow, 1) > 160.5, "显式配置可覆盖自然恢复规则");
            }
        } finally {
            rules.get(GameRules.NATURAL_REGENERATION).set(previous, player.getServer());
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void peacefulHealingRespectsDifficultyAndWorldRule(TestContext context) {
        // 全部调用在同一服务器任务内完成，结束前还原世界设置，不影响其他测试。
        var player = context.createMockSurvivalPlayer();
        bind(player, 80, 80);
        var server = player.getServer();
        Difficulty previousDifficulty = server.getSaveProperties().getDifficulty();
        var rules = player.getWorld().getGameRules();
        boolean previousRule = rules.getBoolean(GameRules.NATURAL_REGENERATION);
        try {
            rules.get(GameRules.NATURAL_REGENERATION).set(true, server);
            server.setDifficulty(Difficulty.PEACEFUL, true);
            player.age = 20;
            player.tickMovement();
            close(context, hp(player, 0) + hp(player, 1), 161, "和平回血总量1均分到身体");
            rules.get(GameRules.NATURAL_REGENERATION).set(false, server);
            player.tickMovement();
            double expected = 161;
            if (!HealingFabric.config().respectNaturalRegeneration()) {
                expected++;
            }
            close(context, hp(player, 0) + hp(player, 1), expected, "和平回血遵守自然恢复配置");
            server.setDifficulty(Difficulty.NORMAL, true);
            rules.get(GameRules.NATURAL_REGENERATION).set(true, server);
            player.getHungerManager().setFoodLevel(10);
            player.tickMovement();
            if (!HealingFabric.config().respectPeaceful()) {
                expected++;
            }
            close(context, hp(player, 0) + hp(player, 1), expected, "其他难度仅在显式配置时开启和平回血");
            close(context, player.getHungerManager().getFoodLevel(), 10, "难度覆盖不顺带开启补食");
        } finally {
            server.setDifficulty(previousDifficulty, true);
            rules.get(GameRules.NATURAL_REGENERATION).set(previousRule, server);
        }
        context.complete();
    }

    @Override
    public byte[] exchange(byte[] payload) {
        // 仅认领测试中登记的实体，实际操作经过正式命令处理器和事务存储。
        var request = HealthHostProtobuf.decodeRequest(payload);
        var response = HealthHostResponse.newBuilder().setProtocolVersion(1).setRequestId(request.getRequestId());
        if (REJECTED_SUBJECTS.contains(request.getSubjectId())) {
            return HealthHostProtobuf.encodeResponse(response.setFailure(
                    dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure.newBuilder()
                            .setCode(dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.INTERNAL_ERROR)).build());
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
        // 任意部位数的非人体构型，保护框架独立性及宿主协议边界。
        var parts = new ArrayList<Anatomy.PartDefinition>();
        var states = new ArrayList<AnatomyState.Part>();
        for (int index = 0; index < hp.length; index++) {
            var id = new Anatomy.PartId("section_" + index);
            parts.add(new Anatomy.PartDefinition(id, "样例", 100));
            states.add(new AnatomyState.Part(id, hp[index]));
        }
        var structure = new BodyStructure(parts, List.of(), List.of(), List.of(), List.of());
        STORE.registerBody(new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId(entity.getUuidAsString()),
                new BodyTemplate.Reference(new DefinitionId("sample:creature"), 1), structure, Realm.WANDERING_QI,
                states, List.of(), List.of(), List.of(), List.of(), new VitalityState(0, 1000, 0, false),
                new TimeCursor(new DefinitionId("sample:world"), "epoch", 0), 0));
        HealingFabric.refresh(entity);
    }

    private static BodySnapshot body(LivingEntity entity) {
        // 断言只读权威账本，不以原版血条是否变化代替身体结算证据。
        return STORE.find(new BodyId(entity.getUuidAsString())).orElseThrow();
    }

    private static double hp(LivingEntity entity, int part) {
        // 测试样例顺序固定，避免断言绕回被测治疗算法。
        return body(entity).partStates().get(part).hp();
    }

    private static void close(TestContext context, double actual, double expected, String reason) {
        // 原版显示使用float，核心用double，允许正常表示误差。
        context.assertTrue(Math.abs(actual - expected) < 1e-4, reason + ": actual=" + actual + ", expected=" + expected);
    }
}
