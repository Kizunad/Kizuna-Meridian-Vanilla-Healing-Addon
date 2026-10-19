package dev.kizuna.meridian.healing;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** 整体回归：总量守恒、损毁排除、配置及重载不补黄心。 */
public class HealingRegressionTest {
    @Test
    public void treatmentRedistributesOverflowAndCannotRestoreDestroyedParts() {
        // 一处只缺 1 HP，另一处缺 50 HP，第三处已毁；总治疗 11 应变成 1+10。
        var body = sample();
        var treatment = HealingRules.distribute(body, 11);
        assertEquals(2, treatment.getDosesCount());
        assertEquals(1, treatment.getDoses(0).getHp(), 0);
        assertEquals(10, treatment.getDoses(1).getHp(), 0);
        assertEquals(51, HealingRules.distribute(body, 1000).getDosesList().stream()
                .mapToDouble(dose -> dose.getHp()).sum(), 0);
        assertEquals(0, HealingRules.distribute(body, 0).getDosesCount());
        assertThrows(IllegalArgumentException.class, () -> HealingRules.distribute(body, Double.NaN));
    }

    @Test
    public void absorptionDefaultsToSharedAndPersistsIndependentSpentBalances() {
        // 默认总黄心 4 均分三部位，消费一处后编码往返不能重新授予。
        HealingConfig config = HealingConfig.parse("");
        assertTrue(config.sharedAbsorption());
        assertFalse(config.healthBoostChangesLimits());
        assertTrue(config.totemEnabled() && config.respectPeaceful() && config.respectNaturalRegeneration());
        assertEquals(0.5, config.naturalMultiplier(), 0);
        var state = new TemporaryHealthState();
        state.grantAbsorption(sample(), 1, config);
        assertEquals(4, state.totalShield(), 1e-12);
        state.consumeShield("left", 1);
        assertEquals(4.0 / 3, state.shield("right"), 1e-12);
        var restored = new TemporaryHealthState();
        restored.decode(state.encode());
        restored.bind(sample());
        assertEquals(3, restored.totalShield(), 1e-12);
        restored.clearAbsorption();
        assertEquals(0, restored.totalShield(), 0);

        restored.grantAbsorption(sample(), 1, HealingConfig.parse("absorption_distribution = 'per_part'"));
        assertEquals(12, restored.totalShield(), 0);
        assertThrows(IllegalArgumentException.class, () -> HealingConfig.parse("unknown = true"));
        assertThrows(IllegalArgumentException.class, () -> HealingConfig.parse("absorption_distribution = 'global'"));
        assertThrows(IllegalArgumentException.class, () -> HealingConfig.parse("natural_healing_multiplier = 2.0"));
    }

    private static dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot sample() {
        // 非人体三部位样例确保规则不会依赖人体模块名称或固定数量。
        var left = new Anatomy.PartId("left");
        var right = new Anatomy.PartId("right");
        var lost = new Anatomy.PartId("lost");
        var structure = new BodyStructure(List.of(new Anatomy.PartDefinition(left, "左", 100),
                new Anatomy.PartDefinition(right, "右", 100), new Anatomy.PartDefinition(lost, "毁", 100)),
                List.of(), List.of(), List.of(), List.of());
        return BodyProtobuf.toMessage(new BodySnapshot(2, new BodyId("sample"),
                new BodyTemplate.Reference(new DefinitionId("sample:body"), 1), structure, Realm.WANDERING_QI,
                List.of(new AnatomyState.Part(left, 99), new AnatomyState.Part(right, 50),
                        new AnatomyState.Part(lost, 0)), List.of(), List.of(), List.of(), List.of(),
                new VitalityState(0, 1000, 0, false), new TimeCursor(new DefinitionId("sample:world"), "epoch", 0), 0));
    }
}
