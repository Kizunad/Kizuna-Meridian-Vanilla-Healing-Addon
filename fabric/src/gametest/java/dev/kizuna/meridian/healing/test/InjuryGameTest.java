package dev.kizuna.meridian.healing.test;

import dev.kizuna.meridian.healing.fabric.InjuryEffects;
import dev.kizuna.meridian.healing.fabric.InjuryFabric;
import dev.kizuna.meridian.healing.injury.InjurySign;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CapabilityModifier;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * 伤势落到实体上的整体回归。生物用例用真的人体：卫道士出生时由类人附属绑上人体模板，
 * 部位上限是大腿 25、手 8、头 20、胸 40；伤害走伤害附属的指定部位入口，和功法打在身上是同一条路。
 * 玩家用例用测试宿主按名字搭的样例身体（每个部位上限 100）。
 */
public final class InjuryGameTest {
    private static final int TIMEOUT = 200;
    /** 身体同步后伤势在下一个实体 tick 落到实体上，留两 tick。 */
    private static final int SETTLE_TICKS = 2;
    /** 出生后等类人附属绑好身体、治疗附属第一次同步。 */
    private static final int BIRTH_TICKS = 5;
    /** 效果被人单独清掉后，最迟下一次每秒同步时挂回来。 */
    private static final int RESYNC_TICKS = 25;
    /** 掉下来的东西 40 tick 内捡不起来；再多等一会儿，看会捡东西的生物捡不捡。 */
    private static final int PICKUP_WATCH_TICKS = 60;
    /** 斧子离他脚底不到这么远算在脚下：原版生物捡东西够得着水平一格。 */
    private static final double AT_FEET = 0.5;
    private static final double TOLERANCE = 1e-9;
    private static final String DIZZY_PREFIX = "meridian_vanilla_healing:dizzy/";
    private static final BlockPos SPOT = new BlockPos(1, 2, 1);
    private static final BlockPos OTHER_SPOT = new BlockPos(3, 2, 3);

    @GameTest(maxTicks = TIMEOUT)
    public void legInjurySlowsZeroLegStopsSprintingAndHealingLiftsIt(GameTestHelper context) {
        // 大腿（25）打到一半以下：挂腿伤 Ⅰ，实际速度要到 ×0.85；治回一半以上就撤。
        // 另一个打到零：实际速度 ×0.60、跳跃 ×0.50、腿伤 Ⅱ，叫他疾跑也跑不起来。
        // 原版生物的实际速度跟移速属性的平方走，所以生物的移速属性乘的是配置倍数的平方根。
        Vindicator healed = vindicator(context, SPOT);
        Vindicator broken = vindicator(context, OTHER_SPOT);
        context.runAfterDelay(BIRTH_TICKS, () -> {
            double speed = healed.getAttributeValue(Attributes.MOVEMENT_SPEED);
            double jump = broken.getAttributeValue(Attributes.JUMP_STRENGTH);
            hit(healed, "left_thigh", 13);
            hit(broken, "right_thigh", 25);
            context.runAfterDelay(SETTLE_TICKS, () -> {
                near(context, healed.getAttributeValue(Attributes.MOVEMENT_SPEED), speed * Math.sqrt(0.85),
                        "腿受伤的生物的移速属性");
                level(context, healed, InjurySign.LEG_INJURY, 0);
                near(context, broken.getAttributeValue(Attributes.MOVEMENT_SPEED), speed * Math.sqrt(0.60),
                        "腿归零的生物的移速属性");
                near(context, broken.getAttributeValue(Attributes.JUMP_STRENGTH), jump * 0.50, "腿归零的跳跃");
                level(context, broken, InjurySign.LEG_INJURY, 1);
                broken.setSprinting(true);
                context.assertTrue(!broken.isSprinting(), "腿归零的人还能疾跑");
                healed.setSprinting(true);
                context.assertTrue(healed.isSprinting(), "腿只是受伤的人应当还能疾跑");
                healed.setSprinting(false);
                healed.heal(20);
                context.runAfterDelay(SETTLE_TICKS, () -> {
                    near(context, healed.getAttributeValue(Attributes.MOVEMENT_SPEED), speed, "治好以后的移速");
                    context.assertTrue(!healed.hasEffect(InjuryEffects.holder(InjurySign.LEG_INJURY)),
                            "腿治回一半以上，腿伤没有撤");
                    finish(context, healed, broken);
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void zeroHandDropsWhatItHoldsAndKeepsDropping(GameTestHelper context) {
        // 惯用右手的卫道士拿着铁斧，右手（8）打到零：斧子掉在脚下、一时捡不起来，挂手废和臂伤 Ⅰ，
        // 攻击只按「手臂受伤」减到 ×0.80；再往这只手里塞东西照样掉。左手没事，左手的盾留着。
        // 他是会捡东西的那种生物：过了捡不起来的那两秒，站在斧子上也不往废了的手里捡，斧子留在地上。
        Vindicator vindicator = vindicator(context, SPOT);
        double unarmed = vindicator.getAttributeValue(Attributes.ATTACK_DAMAGE);
        vindicator.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        vindicator.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        vindicator.setCanPickUpLoot(true /* canPickUpLoot */);
        context.runAfterDelay(BIRTH_TICKS, () -> {
            hit(vindicator, "right_hand", 8);
            context.runAfterDelay(SETTLE_TICKS, () -> {
                context.assertTrue(vindicator.getMainHandItem().isEmpty(), "右手归零，斧子还在手里");
                context.assertTrue(vindicator.getOffhandItem().is(Items.SHIELD), "左手没事，盾不该掉");
                List<ItemEntity> dropped = context.getLevel().getEntitiesOfClass(ItemEntity.class,
                        vindicator.getBoundingBox().inflate(2), item -> item.getItem().is(Items.IRON_AXE));
                context.assertTrue(dropped.size() == 1 && dropped.getFirst().hasPickUpDelay(),
                        "脚下应当有一把一时捡不起来的铁斧：" + dropped);
                level(context, vindicator, InjurySign.CRIPPLED_HAND, 0);
                level(context, vindicator, InjurySign.ARM_INJURY, 0);
                near(context, vindicator.getAttributeValue(Attributes.ATTACK_DAMAGE), unarmed * 0.80,
                        "手归零、斧子掉了以后的空手攻击");
                // 测试里他悬在半空，斧子会掉到下面够不着的地方：把它定在他脚下，过了那两秒伸手就够得着。
                ItemEntity axe = dropped.getFirst();
                axe.setNoGravity(true /* noGravity */);
                axe.setDeltaMovement(Vec3.ZERO);
                axe.setPos(vindicator.position());
                vindicator.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                context.runAfterDelay(PICKUP_WATCH_TICKS, () -> {
                    context.assertTrue(vindicator.getMainHandItem().isEmpty(),
                            "再塞进这只手的东西没有掉，或者又捡回来了");
                    context.assertTrue(axe.isAlive() && !axe.hasPickUpDelay(),
                            "两秒过了，斧子应当还在地上、已经能捡：" + dropped);
                    context.assertTrue(axe.distanceTo(vindicator) < AT_FEET, "斧子不在他脚下，这一条没测到捡不捡");
                    dropped.forEach(ItemEntity::discard);
                    context.getLevel().getEntitiesOfClass(ItemEntity.class, vindicator.getBoundingBox().inflate(2),
                            item -> item.getItem().is(Items.STICK)).forEach(ItemEntity::discard);
                    finish(context, vindicator);
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void headHitGivesNauseaAndAStandingDizzinessThatCutsQiIntake(GameTestHelper context) {
        // 头（20）挨一下：5 秒反胃，还没到一半不挂头晕。再挨到一半以下：挂头晕，两边肺各多一条摄入 ×0.70 的能力修正。
        // 治回一半以上：头晕撤掉，修正也撤掉。
        Vindicator vindicator = vindicator(context, SPOT);
        context.runAfterDelay(BIRTH_TICKS, () -> {
            hit(vindicator, "head", 4);
            context.runAfterDelay(SETTLE_TICKS, () -> {
                MobEffectInstance nausea = vindicator.getEffect(MobEffects.NAUSEA);
                context.assertTrue(nausea != null && nausea.getDuration() > 0 && nausea.getDuration() <= 100,
                        "头挨了一下应当有 5 秒以内的反胃：" + nausea);
                context.assertTrue(!nausea.isVisible() && nausea.showIcon(), "反胃要不出粒子、有图标");
                context.assertTrue(!vindicator.hasEffect(InjuryEffects.holder(InjurySign.DIZZINESS))
                        && dizzy(vindicator).isEmpty(), "头还在一半以上，不该头晕");
                hit(vindicator, "head", 7);
                context.runAfterDelay(SETTLE_TICKS, () -> {
                    level(context, vindicator, InjurySign.DIZZINESS, 0);
                    List<CapabilityModifier> modifiers = dizzy(vindicator);
                    context.assertTrue(modifiers.size() == 2 && modifiers.stream().allMatch(modifier ->
                                    modifier.getIntakePeak() == 0.70 && modifier.getOrganId().endsWith("_lung")),
                            "头晕应当给两边肺各挂一条摄入 ×0.70 的修正：" + modifiers);
                    vindicator.heal(20);
                    context.runAfterDelay(SETTLE_TICKS, () -> {
                        context.assertTrue(!vindicator.hasEffect(InjuryEffects.holder(InjurySign.DIZZINESS)),
                                "头治回一半以上，头晕没有撤");
                        context.assertTrue(dizzy(vindicator).isEmpty(), "头晕撤了，肺上的修正还在");
                        finish(context, vindicator);
                    });
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void milkLeavesInjuriesAndASingledOutClearComesBackWithinASecond(GameTestHelper context) {
        // 牛奶（清除全部效果）：腿伤留着，别的效果（这里是速度）照常清掉。单独点名清掉腿伤的，一秒多以后又挂回来。
        Vindicator vindicator = vindicator(context, SPOT);
        context.runAfterDelay(BIRTH_TICKS, () -> {
            hit(vindicator, "left_thigh", 13);
            context.runAfterDelay(SETTLE_TICKS, () -> {
                vindicator.addEffect(new MobEffectInstance(MobEffects.SPEED, TIMEOUT));
                context.assertTrue(vindicator.removeAllEffects(), "身上有能清的效果，清除全部应当返回成功");
                level(context, vindicator, InjurySign.LEG_INJURY, 0);
                context.assertTrue(!vindicator.hasEffect(MobEffects.SPEED), "牛奶没有清掉速度");
                context.assertTrue(!vindicator.removeAllEffects(), "只剩解不了的效果，清除全部应当返回没清到");
                context.assertTrue(vindicator.removeEffect(InjuryEffects.holder(InjurySign.LEG_INJURY)),
                        "点名清除失败");
                context.runAfterDelay(RESYNC_TICKS, () -> {
                    level(context, vindicator, InjurySign.LEG_INJURY, 0);
                    finish(context, vindicator);
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void shortBreathLosesAirTwiceAsFastUnderWater(GameTestHelper context) {
        // 两个卫道士头都泡在水里，一个胸（40）打到一半以下：同样的时间里他掉的氧气是另一个的两倍，挂着气短。
        Vindicator winded = vindicator(context, SPOT);
        Vindicator sound = vindicator(context, OTHER_SPOT);
        context.runAfterDelay(BIRTH_TICKS, () -> {
            hit(winded, "chest", 21);
            context.runAfterDelay(SETTLE_TICKS, () -> {
                level(context, winded, InjurySign.SHORT_BREATH, 0);
                for (BlockPos spot : List.of(SPOT, OTHER_SPOT)) {
                    context.setBlock(spot, Blocks.WATER);
                    context.setBlock(spot.above(), Blocks.WATER);
                }
                winded.setAirSupply(winded.getMaxAirSupply());
                sound.setAirSupply(sound.getMaxAirSupply());
                context.runAfterDelay(20, () -> {
                    int windedLoss = winded.getMaxAirSupply() - winded.getAirSupply();
                    int soundLoss = sound.getMaxAirSupply() - sound.getAirSupply();
                    context.assertTrue(soundLoss > 0 && windedLoss == 2 * soundLoss,
                            "气短的人掉的氧气应当是别人的两倍：" + windedLoss + " / " + soundLoss);
                    finish(context, winded, sound);
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void playerHungerSprintAndHandsFollowTheSameRules(GameTestHelper context) {
        // 玩家：腹伤让饥饿消耗 ×1.5，气短的人疾跑时再 ×2；右手归零手里的剑掉下来；腿归零起不了疾跑，
        // 移速属性照配置的 ×0.60 改（玩家的实际速度跟属性成正比，不开方）。
        var hungry = TestPlayers.detached(context.getLevel(), "injury_hungry");
        hungry.setGameMode(GameType.SURVIVAL);
        HealingGameTest.bindNamed(hungry, Map.of("abdomen", 50.0, "chest", 40.0));
        double before = HealingGameTest.exhaustion(hungry.getFoodData());
        hungry.causeFoodExhaustion(1);
        near(context, HealingGameTest.exhaustion(hungry.getFoodData()) - before, 1.5, "腹伤的饥饿消耗");
        hungry.setSprinting(true);
        hungry.causeFoodExhaustion(1);
        near(context, HealingGameTest.exhaustion(hungry.getFoodData()) - before, 1.5 + 3.0, "腹伤加气短疾跑的饥饿消耗");
        var crippled = TestPlayers.detached(context.getLevel(), "injury_crippled");
        // 另带两个完好的部位：样例身体只有归零的部位的话，投影出来的血条是零，实体就算死了。
        HealingGameTest.bindNamed(crippled,
                Map.of("right_hand", 0.0, "left_thigh", 0.0, "chest", 100.0, "head", 100.0));
        crippled.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        double walking = crippled.getAttributeValue(Attributes.MOVEMENT_SPEED);
        InjuryFabric.tick(crippled);
        near(context, crippled.getAttributeValue(Attributes.MOVEMENT_SPEED), walking * 0.60, "腿归零的玩家的移速属性");
        context.assertTrue(crippled.getMainHandItem().isEmpty(), "玩家右手归零，剑还在手里");
        level(context, crippled, InjurySign.CRIPPLED_HAND, 0);
        level(context, crippled, InjurySign.LEG_INJURY, 1);
        crippled.setSprinting(true);
        context.assertTrue(!crippled.isSprinting(), "腿归零的玩家还能疾跑");
        context.getLevel().getEntitiesOfClass(ItemEntity.class, crippled.getBoundingBox().inflate(2),
                item -> item.getItem().is(Items.IRON_SWORD)).forEach(ItemEntity::discard);
        hungry.discard();
        crippled.discard();
        context.succeed();
    }

    private static Vindicator vindicator(GameTestHelper context, BlockPos spot) {
        // 不开 AI、惯用右手、不消失；出生时类人附属给它绑人体。
        Vindicator vindicator = context.spawn(EntityTypes.VINDICATOR, spot);
        vindicator.setNoAi(true /* aiDisabled */);
        vindicator.setLeftHanded(false /* leftHanded */);
        vindicator.setPersistenceRequired();
        return vindicator;
    }

    private static void hit(LivingEntity target, String partId, double hp) {
        // 指定部位的直接伤害：不吃无敌帧，剂量就是身体 HP。
        var source = target.level().damageSources().generic();
        VanillaDamageFabric.damageDirect(new VanillaDamageFabric.DirectDamage(target, source, partId, hp))
                .orElseThrow();
    }

    private static List<CapabilityModifier> dizzy(LivingEntity entity) {
        // 身体上本附属挂的头晕修正。
        return VanillaDamageFabric.inspectBody(entity).orElseThrow().getModifiersList().stream()
                .filter(modifier -> modifier.getId().startsWith(DIZZY_PREFIX)).toList();
    }

    private static void level(GameTestHelper context, LivingEntity entity, InjurySign sign, int amplifier) {
        MobEffectInstance effect = entity.getEffect(InjuryEffects.holder(sign));
        context.assertTrue(effect != null && effect.getAmplifier() == amplifier && effect.isInfiniteDuration(),
                sign + " 应当挂着、放大值 " + amplifier + "、不限时：" + effect);
    }

    private static void near(GameTestHelper context, double actual, double expected, String what) {
        context.assertTrue(Math.abs(actual - expected) < TOLERANCE, what + " 应为 " + expected + "，实际 " + actual);
    }

    private static void finish(GameTestHelper context, LivingEntity... entities) {
        for (LivingEntity entity : entities) {
            entity.discard();
        }
        context.succeed();
    }
}
