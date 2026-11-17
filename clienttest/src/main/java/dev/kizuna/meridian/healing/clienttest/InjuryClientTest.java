package dev.kizuna.meridian.healing.clienttest;

import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 伤势的连拍，一次录一段（-PclienttestClip）：
 * leg 三个卫道士并排往前走，一个没伤、一个腿受伤、一个腿归零，看谁落在后面；
 * hand 拿斧子的卫道士右手被打到零，斧子掉在脚下；
 * vital 两个卫道士，一个颈被打到零、一个胸被打到零，都当场倒下。
 * 卫道士用正式的生成命令出生，身上是真的人体；伤害走伤害附属的指定部位入口，和功法打在身上是同一条路。
 * 镜头是旁观模式的玩家；游戏冻结后一 tick 放行一次、拍一张。
 */
public final class InjuryClientTest implements FabricClientGameTest {
    private static final String CLIP_PROPERTY = "meridian.healing.clip";
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final String SPAWN = "meridian humanoid spawn minecraft:vindicator wandering_qi %.1f %.1f %.1f";
    private static final int CLEAR_RADIUS = 24;
    private static final int CLEAR_HEIGHT = 12;
    private static final int SETTLE_TICKS = 10;
    private static final int STEP_SETTLE_TICKS = 2;
    private static final double EYE_HEIGHT = 1.62;
    private static final boolean CLEAR_SENT_HISTORY = false;
    /** 开拍后第几张下手：前面留几张看得见受伤之前的样子。 */
    private static final int STRIKE_FRAME = 10;
    /** 走路那段：三条道隔 2 格，往 +Z 走 12 格，每 5 tick 重新指一次路。 */
    private static final double LANE_GAP = 2;
    private static final double WALK_DISTANCE = 12;
    private static final int REPATH_TICKS = 5;
    /** 没伤的那个 60 张左右走完全程；拍到他快到头为止（到头会减速），这时量三人走了多远。 */
    private static final int WALK_FRAMES = 52;
    private static final int HAND_FRAMES = 50;
    private static final int VITAL_FRAMES = 70;
    /** 第二个人（胸）晚这么多张再打，两次倒下错开看。 */
    private static final int SECOND_STRIKE_DELAY = 25;
    /** 随包配置里腿受伤、腿归零的移速倍数，和实测允许差多少。 */
    private static final double INJURED_SPEED = 0.85;
    private static final double ZERO_SPEED = 0.60;
    private static final double SPEED_TOLERANCE = 0.03;
    private static final double THIGH_HP = 25;
    private static final double THIGH_INJURED = 13;
    private static final double HAND_HP = 8;
    private static final double NECK_HP = 10;
    private static final double CHEST_HP = 40;

    @Override
    public void runTest(ClientGameTestContext context) {
        String clip = System.getProperty(CLIP_PROPERTY, "leg");
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            Vec3 base = Vec3.atLowerCornerOf(prepare(context, world));
            command(world, "tick freeze");
            switch (clip) {
                case "leg" -> leg(context, world, base);
                case "hand" -> hand(context, world, base);
                case "vital" -> vital(context, world, base);
                default -> throw new IllegalArgumentException("没有这一段连拍：" + clip);
            }
            command(world, "tick unfreeze");
        }
    }

    private static void leg(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
        // 镜头在起点后上方往前看，三条道都在画面里。画面右边没伤，中间大腿受伤（实际速度 ×0.85），
        // 左边大腿归零（×0.60）。量每人每 tick 走的距离、取中位数（起步和中途重新找路的那几 tick 不算数）：
        // 受伤的、归零的和没伤的相比，要和配置的 0.85、0.60 对上，各差不超过 0.03。
        look(context, world, base.add(0.5, 7, -9.5), base.add(0.5, 0, WALK_DISTANCE / 2));
        List<Vindicator> walkers = new ArrayList<>();
        for (int lane = -1; lane <= 1; lane++) {
            Vec3 feet = base.add(0.5 + lane * LANE_GAP, 0, 0.5);
            walkers.add(world.getServer().computeOnServer(server -> vindicator(server, feet, ItemStack.EMPTY)));
        }
        settle(context, world);
        world.getServer().runOnServer(server -> {
            hit(walkers.get(1), "left_thigh", THIGH_INJURED);
            hit(walkers.get(2), "left_thigh", THIGH_HP);
        });
        List<String> rows = new ArrayList<>();
        List<double[]> walked = new ArrayList<>();
        for (int frame = 1; frame <= WALK_FRAMES; frame++) {
            if (frame % REPATH_TICKS == 1) {
                world.getServer().runOnServer(server -> walkers.forEach(walker -> walker.getNavigation()
                        .moveTo(walker.getX(), walker.getY(), base.z + 0.5 + WALK_DISTANCE + 4, 1.0)));
            }
            step(context, world, "leg", frame);
            double[] at = world.getServer().computeOnServer(server -> walkers.stream()
                    .mapToDouble(walker -> walker.getZ() - base.z).toArray());
            walked.add(at);
            rows.add(frame + "\t" + String.format(Locale.ROOT, "%.2f\t%.2f\t%.2f", at[0], at[1], at[2]));
        }
        write("leg.tsv", "张\t没伤走了\t腿受伤走了\t腿归零走了\n" + String.join("\n", rows) + "\n");
        double[] pace = {pace(walked, 0), pace(walked, 1), pace(walked, 2)};
        double injured = pace[1] / pace[0];
        double zero = pace[2] / pace[0];
        if (Math.abs(injured - INJURED_SPEED) > SPEED_TOLERANCE || Math.abs(zero - ZERO_SPEED) > SPEED_TOLERANCE) {
            throw new AssertionError(String.format(Locale.ROOT, "实际速度和配置对不上：腿受伤 %.3f（应为 %.2f），"
                    + "腿归零 %.3f（应为 %.2f）；每 tick 各走 %s", injured, INJURED_SPEED, zero, ZERO_SPEED,
                    Arrays.toString(pace)));
        }
        write("leg-ratio.txt", String.format(Locale.ROOT, "腿受伤 %.3f 腿归零 %.3f（和没伤的比；每 tick 走的距离"
                + "取 %d tick 里的中位数，没伤 %.3f、腿受伤 %.3f、腿归零 %.3f 格）%n", injured, zero, WALK_FRAMES,
                pace[0], pace[1], pace[2]));
    }

    private static double pace(List<double[]> walked, int walker) {
        // 这个人每 tick 往前走的距离的中位数：起步加速、中途重新找路顿一下的那几 tick 落在两头，不影响它。
        double[] steps = new double[walked.size() - 1];
        for (int index = 1; index < walked.size(); index++) {
            steps[index - 1] = walked.get(index)[walker] - walked.get(index - 1)[walker];
        }
        Arrays.sort(steps);
        return steps[steps.length / 2];
    }

    private static void hand(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
        // 镜头在正前方偏高处看着他；第 10 张把右手打到零，斧子掉在脚下，他空着手站着。
        look(context, world, base.add(0.5, 1.2, 5.5), base.add(0.5, 1.0, 0.5));
        Vec3 feet = base.add(0.5, 0, 0.5);
        Vindicator vindicator = world.getServer().computeOnServer(server -> {
            Vindicator spawned = vindicator(server, feet, new ItemStack(Items.IRON_AXE));
            // 固定镜头只看掉斧子：让他留在掉落点旁，伤势仍由实体 tick 正常处理。
            spawned.setNoAi(true /* aiDisabled */);
            return spawned;
        });
        settle(context, world);
        List<String> rows = new ArrayList<>();
        for (int frame = 1; frame <= HAND_FRAMES; frame++) {
            if (frame == STRIKE_FRAME) {
                world.getServer().runOnServer(server -> hit(vindicator, "right_hand", HAND_HP));
            }
            step(context, world, "hand", frame);
            rows.add(frame + "\t" + world.getServer().computeOnServer(server ->
                    vindicator.getMainHandItem().isEmpty() + "\t" + axes(vindicator).size()));
        }
        write("hand.tsv", "张\t手里空了\t地上的斧子\n" + String.join("\n", rows) + "\n");
        boolean dropped = world.getServer().computeOnServer(
                server -> vindicator.getMainHandItem().isEmpty() && axes(vindicator).size() == 1);
        if (!dropped) {
            throw new AssertionError("右手归零以后斧子没有掉在脚下");
        }
    }

    private static void vital(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
        // 左边的打颈（上限 10），右边的晚一点打胸（上限 40）：各只挨这一下，都当场倒下。
        look(context, world, base.add(0.5, 1.5, 7.5), base.add(0.5, 1.0, 0.5));
        Vindicator neck = world.getServer().computeOnServer(
                server -> vindicator(server, base.add(2.0, 0, 0.5), ItemStack.EMPTY));
        Vindicator chest = world.getServer().computeOnServer(
                server -> vindicator(server, base.add(-1.0, 0, 0.5), ItemStack.EMPTY));
        settle(context, world);
        List<String> rows = new ArrayList<>();
        for (int frame = 1; frame <= VITAL_FRAMES; frame++) {
            if (frame == STRIKE_FRAME) {
                world.getServer().runOnServer(server -> hit(neck, "neck", NECK_HP));
            }
            if (frame == STRIKE_FRAME + SECOND_STRIKE_DELAY) {
                world.getServer().runOnServer(server -> hit(chest, "chest", CHEST_HP));
            }
            step(context, world, "vital", frame);
            rows.add(frame + "\t" + world.getServer().computeOnServer(
                    server -> neck.isAlive() + "\t" + chest.isAlive()));
        }
        write("vital.tsv", "张\t打颈的还活着\t打胸的还活着\n" + String.join("\n", rows) + "\n");
        boolean bothDead = world.getServer().computeOnServer(server -> !neck.isAlive() && !chest.isAlive());
        if (!bothDead) {
            throw new AssertionError("颈、胸归零的人没有当场死");
        }
    }

    private static List<ItemEntity> axes(Vindicator vindicator) {
        // 他脚边掉着的铁斧。
        return vindicator.level().getEntitiesOfClass(ItemEntity.class, vindicator.getBoundingBox().inflate(2),
                item -> item.getItem().is(Items.IRON_AXE));
    }

    private static BlockPos prepare(ClientGameTestContext context, TestSingleplayerContext world) {
        // 原点取 (0, 0) 处的地表；正午、晴天，界面藏起来，四周清成平地；玩家改旁观，不进任何生物的眼。
        int surface = world.getServer().computeOnServer(
                server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0));
        BlockPos origin = new BlockPos(0, surface, 0);
        command(world, "time set noon");
        command(world, "gamerule advance_time false");
        command(world, "weather clear");
        command(world, "gamemode spectator @a");
        context.runOnClient(client -> {
            if (!client.gui.hud.isHidden()) {
                client.gui.hud.toggle();
            }
        });
        BlockPos from = origin.offset(-CLEAR_RADIUS, 0, -CLEAR_RADIUS);
        BlockPos to = origin.offset(CLEAR_RADIUS, CLEAR_HEIGHT, CLEAR_RADIUS);
        command(world, "fill " + coordinates(from) + " " + coordinates(to) + " air");
        command(world, "fill " + coordinates(from.below()) + " " + coordinates(to.atY(origin.getY() - 1))
                + " smooth_stone");
        AABB area = new AABB(Vec3.atLowerCornerOf(from), Vec3.atLowerCornerOf(to.offset(1, 1, 1)));
        world.getServer().runOnServer(server -> server.overworld()
                .getEntities((Entity) null, area, entity -> !(entity instanceof Player))
                .forEach(Entity::discard));
        return origin;
    }

    private static Vindicator vindicator(MinecraftServer server, Vec3 feet, ItemStack mainHand) {
        // 用正式的生成命令出生一个游炁卫道士，面朝 +Z、惯用右手、不出声、不消失。
        ServerLevel level = server.overworld();
        var source = server.createCommandSourceStack().withLevel(level).withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, String.format(Locale.ROOT, SPAWN, feet.x, feet.y, feet.z));
        List<Vindicator> spawned = level.getEntitiesOfClass(Vindicator.class, new AABB(BlockPos.containing(feet)));
        if (spawned.size() != 1) {
            throw new IllegalStateException("生成命令没有放出恰好一个卫道士：" + spawned);
        }
        Vindicator vindicator = spawned.getFirst();
        vindicator.snapTo(feet.x, feet.y, feet.z, 0, 0);
        vindicator.setYHeadRot(0);
        vindicator.setYBodyRot(0);
        vindicator.setLeftHanded(false /* leftHanded */);
        vindicator.setSilent(true /* silent */);
        vindicator.setPersistenceRequired();
        vindicator.setItemInHand(InteractionHand.MAIN_HAND, mainHand);
        return vindicator;
    }

    private static void hit(Vindicator target, String partId, double hp) {
        // 指定部位的直接伤害，剂量就是身体 HP。
        var source = target.level().damageSources().generic();
        VanillaDamageFabric.damageDirect(new VanillaDamageFabric.DirectDamage(target, source, partId, hp));
    }

    private static void settle(ClientGameTestContext context, TestSingleplayerContext world) {
        // 放行几 tick，让身体绑好、治疗附属同步一次，再开拍。
        for (int tick = 0; tick < SETTLE_TICKS; tick++) {
            command(world, "tick step 1");
            context.waitTicks(STEP_SETTLE_TICKS);
        }
    }

    private static void step(ClientGameTestContext context, TestSingleplayerContext world, String clip, int frame) {
        // 游戏已经冻结：每次只放行一个 tick，等它的包到了再拍，一张正好是一个游戏 tick。
        command(world, "tick step 1");
        context.waitTicks(STEP_SETTLE_TICKS);
        context.runOnClient(client -> {
            // 命令回显和右上角提示会挡画面，拍之前清掉。
            client.gui.hud.getChat().clearMessages(CLEAR_SENT_HISTORY);
            client.gui.toastManager().clear();
        });
        context.takeScreenshot(TestScreenshotOptions.of(String.format(Locale.ROOT, "%s_%04d", clip, frame))
                .disableCounterPrefix());
    }

    private static void look(ClientGameTestContext context, TestSingleplayerContext world, Vec3 feet, Vec3 target) {
        // 原版朝向：偏航 0 朝 +Z，俯仰为正时低头；tp 给的是脚底坐标，眼睛在其上方 1.62 格。
        Vec3 direction = target.subtract(feet.add(0, EYE_HEIGHT, 0));
        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        double pitch = Math.toDegrees(Math.atan2(-direction.y, direction.horizontalDistance()));
        command(world,
                String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %.2f %.2f", feet.x, feet.y, feet.z, yaw, pitch));
        context.waitTicks(SETTLE_TICKS);
        world.getConnection().waitForChunksRender();
    }

    private static void command(TestSingleplayerContext world, String command) {
        // 以服务端身份执行，和管理员在游戏里输入的一样。
        world.getServer().runCommand(command);
    }

    private static void write(String name, String content) {
        // 写到本次运行目录里。
        try {
            Files.writeString(FabricLoader.getInstance().getGameDir().resolve(name), content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
