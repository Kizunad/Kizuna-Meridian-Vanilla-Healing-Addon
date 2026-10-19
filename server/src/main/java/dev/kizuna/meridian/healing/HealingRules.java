package dev.kizuna.meridian.healing;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DeathProtection;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HealBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.Healing;
import java.util.ArrayList;
import java.util.Comparator;

/** 平均治疗与图腾下限只生成意图，实际 HP 由框架事务写入。 */
public final class HealingRules {
    private HealingRules() {
        // 所有计算依据本次权威快照，无模板或人体 ID 兜底。
    }

    public static HealBody distribute(BodySnapshot body, double totalHp) {
        // 先收集仍有 HP 的缺血部位；零 HP 视为损毁，普通回血不能使其恢复。
        if (!Double.isFinite(totalHp) || totalHp < 0) {
            throw HealingErrors.invalid(HealingErrors.HOST);
        }
        var result = HealBody.newBuilder().setSourceId("meridian_vanilla:healing");
        if (body.getVitality().getDead()) {
            return result.build();
        }
        var deficits = new ArrayList<Deficit>();
        for (var part : body.getStructure().getPartsList()) {
            var state = body.getPartStatesList().stream()
                    .filter(candidate -> candidate.getId().equals(part.getId())).findFirst().orElseThrow();
            if (state.getHp() > 0 && state.getHp() < part.getMaxHp()) {
                deficits.add(new Deficit(part.getId(), part.getMaxHp() - state.getHp()));
            }
        }

        // 从缺口最小者开始封顶，剩余总量均分给后续部位，溢出不会被浪费。
        deficits.sort(Comparator.comparingDouble(Deficit::missingHp));
        double remainingHp = totalHp;
        int remainingParts = deficits.size();
        for (Deficit deficit : deficits) {
            double dose = Math.min(deficit.missingHp(), remainingHp / remainingParts);
            if (dose > 0) {
                result.addDoses(Healing.newBuilder().setTarget(
                        HealthTarget.newBuilder().setPartId(deficit.partId())).setHp(dose));
                remainingHp = Math.max(0, remainingHp - dose);
            }
            remainingParts--;
        }
        return result.build();
    }

    public static DeathProtection totemProtection(BodySnapshot body, double ratio) {
        // 图腾属于保命例外；所有 HP 账户在同一伤害事务内达到下限，不清除已有伤势。
        var protection = DeathProtection.newBuilder().setSourceId("meridian_vanilla:totem");
        for (var part : body.getStructure().getPartsList()) {
            protection.addMinimumHp(Healing.newBuilder().setTarget(HealthTarget.newBuilder().setPartId(part.getId()))
                    .setHp(part.getMaxHp() * ratio));
        }
        for (var tissue : body.getStructure().getTissuesList()) {
            protection.addMinimumHp(Healing.newBuilder().setTarget(HealthTarget.newBuilder().setTissueId(tissue.getId()))
                    .setHp(tissue.getMaxHp() * ratio));
        }
        for (var organ : body.getStructure().getOrgansList()) {
            protection.addMinimumHp(Healing.newBuilder().setTarget(HealthTarget.newBuilder().setOrganId(organ.getId()))
                    .setHp(organ.getMaxHp() * ratio));
        }
        return protection.build();
    }

    public static double partHp(BodySnapshot body) {
        // 组织、器官有独立 HP，不重复计入原版血条的身体总账。
        return body.getPartStatesList().stream().mapToDouble(part -> part.getHp()).sum();
    }

    public static double partMaximum(BodySnapshot body) {
        // 映射使用实例实际上限，兼容永久改造与临时生命提升。
        return body.getStructure().getPartsList().stream().mapToDouble(part -> part.getMaxHp()).sum();
    }

    private record Deficit(String partId, double missingHp) { }
}
