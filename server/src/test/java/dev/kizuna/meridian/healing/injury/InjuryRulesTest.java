package dev.kizuna.meridian.healing.injury;

import dev.kizuna.meridian.healing.HealingConfig;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyStructure;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.VitalityState;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 伤势规则：从身体快照判档、把档换成后果、读配置。身体是按随包配置里的部位名搭的，每个部位上限 100。 */
public class InjuryRulesTest {
    private static final double MAX = 100;
    private static final double TOLERANCE = 1e-12;
    private static final InjuryConfig CONFIG = HealingConfig.parse("").injury();
    private static final boolean LEFT_HANDED = true;
    private static final boolean RIGHT_HANDED = false;

    @Test
    public void tierFollowsPartHpAtTheThresholdAndAtZero() {
        // 刚过一半不算伤，正好一半算受伤，零算归零；一组里取最重的那个部位。
        assertEquals(Injuries.NONE, assess(Map.of("left_thigh", 50.5)).tier(InjuryGroup.LEG));
        assertEquals(Injuries.INJURED, assess(Map.of("left_thigh", 50.0)).tier(InjuryGroup.LEG));
        assertEquals(Injuries.ZERO, assess(Map.of("left_thigh", 0.0)).tier(InjuryGroup.LEG));
        assertEquals(Injuries.ZERO, assess(Map.of("left_thigh", 40.0, "right_foot", 0.0)).tier(InjuryGroup.LEG));
        assertEquals(Injuries.NONE, assess(Map.of("left_thigh", 0.0)).tier(InjuryGroup.HIP));
        assertSame(InjuryBurden.NONE, assess(Map.of()).burden(CONFIG, RIGHT_HANDED));
    }

    @Test
    public void legAndHipSlowAndAtZeroStopSprintingAndJumping() {
        InjuryBurden hurt = burden(Map.of("right_lower_leg", 30.0), RIGHT_HANDED);
        assertEquals(0.85, hurt.speed(), TOLERANCE);
        assertEquals(1, hurt.jump(), 0);
        assertFalse(hurt.noSprint());
        assertEquals(Map.of(InjurySign.LEG_INJURY, 0), hurt.signs());
        InjuryBurden gone = burden(Map.of("right_lower_leg", 0.0), RIGHT_HANDED);
        assertEquals(0.60, gone.speed(), TOLERANCE);
        assertEquals(0.50, gone.jump(), TOLERANCE);
        assertTrue(gone.noSprint());
        assertEquals(Map.of(InjurySign.LEG_INJURY, 1), gone.signs());
        InjuryBurden both = burden(Map.of("left_foot", 20.0, "pelvis", 0.0), RIGHT_HANDED);
        assertEquals(0.85 * 0.70, both.speed(), TOLERANCE);
        assertEquals(1, both.jump(), 0);
        assertTrue(both.noSprint());
        assertEquals(Map.of(InjurySign.LEG_INJURY, 0, InjurySign.HIP_INJURY, 1), both.signs());
    }

    @Test
    public void armInjuryOnlyWeakensTheMainHandAndAZeroHandDropsWhatItHolds() {
        // 左前臂归零：惯用右手的人攻击、挖掘不受影响，但照样挂臂伤 Ⅱ；惯用左手的人减到 0.5、0.3。
        Map<String, Double> leftForearm = Map.of("left_forearm", 0.0);
        InjuryBurden offArm = burden(leftForearm, RIGHT_HANDED);
        assertEquals(1, offArm.attack(), 0);
        assertEquals(1, offArm.mining(), 0);
        assertEquals(Map.of(InjurySign.ARM_INJURY, 1), offArm.signs());
        InjuryBurden mainArm = burden(leftForearm, LEFT_HANDED);
        assertEquals(0.50, mainArm.attack(), TOLERANCE);
        assertEquals(0.30, mainArm.mining(), TOLERANCE);
        // 手受伤算手臂受伤一档；手归零也只算一档，另外那只手拿不住东西。
        InjuryBurden sore = burden(Map.of("right_hand", 50.0), RIGHT_HANDED);
        assertEquals(0.80, sore.attack(), TOLERANCE);
        assertEquals(0.70, sore.mining(), TOLERANCE);
        assertFalse(sore.dropRight());
        InjuryBurden crippled = burden(Map.of("right_hand", 0.0), RIGHT_HANDED);
        assertEquals(0.80, crippled.attack(), TOLERANCE);
        assertTrue(crippled.dropRight());
        assertFalse(crippled.dropLeft());
        assertEquals(Map.of(InjurySign.ARM_INJURY, 0, InjurySign.CRIPPLED_HAND, 0), crippled.signs());
        // 颈受伤两只手都算，和惯用手那条手臂的伤连乘。
        InjuryBurden neck = burden(Map.of("neck", 10.0, "right_upper_arm", 40.0), RIGHT_HANDED);
        assertEquals(0.80 * 0.80, neck.attack(), TOLERANCE);
        assertEquals(0.70 * 0.70, neck.mining(), TOLERANCE);
        assertEquals(Map.of(InjurySign.ARM_INJURY, 0, InjurySign.NECK_INJURY, 0), neck.signs());
    }

    @Test
    public void abdomenChestLungsEyesAndHeadEachHaveTheirOwnConsequence() {
        assertEquals(1.5, burden(Map.of("abdomen", 50.0), RIGHT_HANDED).hunger(), TOLERANCE);
        assertEquals(3.0, burden(Map.of("abdomen", 0.0), RIGHT_HANDED).hunger(), TOLERANCE);
        InjuryBurden chest = burden(Map.of("chest", 20.0), RIGHT_HANDED);
        assertEquals(2.0, chest.sprintHunger(), TOLERANCE);
        assertEquals(2.0, chest.air(), TOLERANCE);
        assertEquals(1, chest.hunger(), 0);
        assertEquals(Map.of(InjurySign.SHORT_BREATH, 0), chest.signs());
        // 肺坏一边就气短；两边都坏再减移速。只坏一只眼不失明，两只都坏才失明。
        InjuryBurden oneLung = organs(Map.of("left_lung", 0.0)).burden(CONFIG, RIGHT_HANDED);
        assertEquals(2.0, oneLung.air(), TOLERANCE);
        assertEquals(1, oneLung.speed(), 0);
        InjuryBurden noLungs = organs(Map.of("left_lung", 0.0, "right_lung", 0.0)).burden(CONFIG, RIGHT_HANDED);
        assertEquals(0.80, noLungs.speed(), TOLERANCE);
        assertEquals(Map.of(InjurySign.SHORT_BREATH, 0), noLungs.signs());
        assertFalse(organs(Map.of("left_eye", 0.0)).burden(CONFIG, RIGHT_HANDED).blind());
        assertTrue(organs(Map.of("left_eye", 0.0, "right_eye", 0.0)).burden(CONFIG, RIGHT_HANDED).blind());
        InjuryBurden dizzy = burden(Map.of("head", 9.0), RIGHT_HANDED);
        assertTrue(dizzy.dizzy());
        assertEquals(0.70, dizzy.qiIntake(), TOLERANCE);
        assertEquals(Map.of(InjurySign.DIZZINESS, 0), dizzy.signs());
    }

    @Test
    public void deadBodiesDisabledConfigAndForeignBodiesCarryNoInjury() {
        // 人死了不挂；整套关了不挂；身体上没有配置里写的那些部位（不是人体模板）也不挂。
        BodySnapshot dead = body(Map.of("left_thigh", 0.0), Map.of()).toBuilder()
                .setVitality(VitalityState.newBuilder().setDead(true)).build();
        assertSame(InjuryBurden.NONE, Injuries.assess(dead, CONFIG).burden(CONFIG, RIGHT_HANDED));
        InjuryConfig off = HealingConfig.parse("[injury]\nenabled = false").injury();
        assertSame(InjuryBurden.NONE,
                Injuries.assess(body(Map.of("left_thigh", 0.0), Map.of()), off).burden(off, RIGHT_HANDED));
        BodySnapshot foreign = BodySnapshot.newBuilder()
                .setStructure(BodyStructure.newBuilder().addParts(PartDefinition.newBuilder().setId("section_0")
                        .setMaxHp(MAX)))
                .addPartStates(PartState.newBuilder().setId("section_0").setHp(0)).build();
        assertSame(InjuryBurden.NONE, Injuries.assess(foreign, CONFIG).burden(CONFIG, RIGHT_HANDED));
    }

    @Test
    public void configFallsBackPerKeyAndRejectsTyposAndOutOfRangeValues() {
        // 随包初值和枚举里的初值是同一套数；老文件没有 [injury] 照初值算；只改一条的，其余照旧。
        for (InjuryStrength strength : InjuryStrength.values()) {
            assertEquals(strength.key(), strength.initial(), CONFIG.strength(strength), 0);
        }
        assertTrue(CONFIG.enabled());
        assertEquals(0.5, CONFIG.injuredRatio(), 0);
        assertEquals(100, CONFIG.headHitNauseaTicks());
        assertEquals(List.of("pelvis"), CONFIG.parts().get(InjuryGroup.HIP));
        InjuryConfig tuned = HealingConfig.parse("[injury]\ninjured_ratio = 0.3\n[injury.strength]\n"
                + "leg_zero_speed = 0.4\n[injury.parts]\nhip = []").injury();
        assertEquals(0.3, tuned.injuredRatio(), 0);
        assertEquals(0.4, tuned.strength(InjuryStrength.LEG_ZERO_SPEED), 0);
        assertEquals(0.85, tuned.strength(InjuryStrength.LEG_INJURED_SPEED), 0);
        assertEquals(List.of(), tuned.parts().get(InjuryGroup.HIP));
        assertEquals(CONFIG.parts().get(InjuryGroup.LEG), tuned.parts().get(InjuryGroup.LEG));
        assertEquals(CONFIG.lungs(), tuned.lungs());
        for (String bad : List.of("[injury]\nthreshold = 0.5", "[injury]\ninjured_ratio = 1.0",
                "[injury]\nhead_hit_nausea_ticks = -1", "[injury.strength]\nleg_zero_speed = 0.0",
                "[injury.strength]\nleg_zero_speed = 1.5", "[injury.strength]\nabdomen_zero_hunger = 0.5",
                "[injury.strength]\nleg_speed = 0.5", "[injury.parts]\ntail = ['tail']",
                "[injury.parts]\nleg = ['left_thigh', 'left_thigh']", "[injury.organs]\nheart = ['heart']",
                "injury = 3")) {
            assertThrows(bad, IllegalArgumentException.class, () -> HealingConfig.parse(bad));
        }
    }

    private static InjuryBurden burden(Map<String, Double> partHp, boolean mainArmLeft) {
        return assess(partHp).burden(CONFIG, mainArmLeft);
    }

    private static Injuries assess(Map<String, Double> partHp) {
        return Injuries.assess(body(partHp, Map.of()), CONFIG);
    }

    private static Injuries organs(Map<String, Double> organHp) {
        return Injuries.assess(body(Map.of(), organHp), CONFIG);
    }

    private static BodySnapshot body(Map<String, Double> partHp, Map<String, Double> organHp) {
        // 配置里写到的部位、器官各搭一个，上限都是 100；没点名的都是满的。
        var structure = BodyStructure.newBuilder();
        var body = BodySnapshot.newBuilder();
        Map<String, Double> parts = new LinkedHashMap<>();
        CONFIG.parts().values().forEach(ids -> ids.forEach(id -> parts.put(id, MAX)));
        parts.putAll(partHp);
        parts.forEach((id, hp) -> {
            structure.addParts(PartDefinition.newBuilder().setId(id).setMaxHp(MAX));
            body.addPartStates(PartState.newBuilder().setId(id).setHp(hp));
        });
        Map<String, Double> organs = new LinkedHashMap<>();
        CONFIG.eyes().forEach(id -> organs.put(id, MAX));
        CONFIG.lungs().forEach(id -> organs.put(id, MAX));
        organs.putAll(organHp);
        organs.forEach((id, hp) -> {
            structure.addOrgans(OrganDefinition.newBuilder().setId(id).setMaxHp(MAX));
            body.addOrganStates(OrganState.newBuilder().setId(id).setHp(hp));
        });
        return body.setStructure(structure).build();
    }
}
