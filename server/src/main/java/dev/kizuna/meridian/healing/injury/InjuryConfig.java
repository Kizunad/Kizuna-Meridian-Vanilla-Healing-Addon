package dev.kizuna.meridian.healing.injury;

import dev.kizuna.meridian.healing.HealingErrors;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 伤势配置（meridian-vanilla-healing.toml 的 [injury] 一节）：总开关、受伤门槛、头挨打的反胃时长、
 * 每组认哪些部位和器官、每条效果的强度。部位和器官的 ID 只从配置来。
 */
public record InjuryConfig(boolean enabled, double injuredRatio, int headHitNauseaTicks,
                           Map<InjuryGroup, List<String>> parts, List<String> eyes, List<String> lungs,
                           Map<InjuryStrength, Double> strengths) {
    private static final Set<String> KEYS =
            Set.of("enabled", "injured_ratio", "head_hit_nausea_ticks", "parts", "organs", "strength");
    private static final Set<String> ORGAN_KEYS = Set.of("eyes", "lungs");
    private static final int MAX_NAUSEA_TICKS = 20 * 60;

    public InjuryConfig {
        // 门槛在 (0, 1)：写成 1 就是满血也算受伤，写成 0 就和「归零」分不开。
        if (!Double.isFinite(injuredRatio) || injuredRatio <= 0 || injuredRatio >= 1
                || headHitNauseaTicks < 0 || headHitNauseaTicks > MAX_NAUSEA_TICKS) {
            throw HealingErrors.invalid(HealingErrors.CONFIG);
        }
        parts = Map.copyOf(parts);
        eyes = List.copyOf(eyes);
        lungs = List.copyOf(lungs);
        strengths = Map.copyOf(strengths);
    }

    public double strength(InjuryStrength strength) {
        return strengths.get(strength);
    }

    /**
     * 读 [injury] 一节。这一节是后加的：老配置文件里整节没有、或者少了后来加的键，都按随包初值（fallback）算；
     * 拼错的键、类型不对、越界的值一律拒绝，不悄悄落回初值。
     */
    public static InjuryConfig parse(TomlTable table, TomlTable fallback) {
        if (fallback == null) {
            throw HealingErrors.invalid(HealingErrors.CONFIG);
        }
        TomlTable injury = table == null ? fallback : table;
        if (!KEYS.containsAll(injury.keySet())) {
            throw HealingErrors.invalid(HealingErrors.CONFIG);
        }
        TomlTable organs = section(injury, fallback, "organs");
        if (!ORGAN_KEYS.containsAll(organs.keySet())) {
            throw HealingErrors.invalid(HealingErrors.CONFIG);
        }
        TomlTable fallbackOrgans = fallback.getTable("organs");
        return new InjuryConfig(
                injury.getBoolean("enabled", () -> fallback.getBoolean("enabled")),
                injury.getDouble("injured_ratio", () -> fallback.getDouble("injured_ratio")),
                Math.toIntExact(injury.getLong("head_hit_nausea_ticks",
                        () -> fallback.getLong("head_hit_nausea_ticks"))),
                groups(section(injury, fallback, "parts"), fallback.getTable("parts")),
                ids(organs, fallbackOrgans, "eyes"), ids(organs, fallbackOrgans, "lungs"),
                strengths(section(injury, fallback, "strength")));
    }

    private static TomlTable section(TomlTable injury, TomlTable fallback, String key) {
        // 子表没写就用随包的那一张；写了但不是表，拒绝。
        if (!injury.contains(key)) {
            return fallback.getTable(key);
        }
        if (!(injury.get(key) instanceof TomlTable section)) {
            throw HealingErrors.invalid(HealingErrors.CONFIG);
        }
        return section;
    }

    private static Map<InjuryGroup, List<String>> groups(TomlTable parts, TomlTable fallback) {
        // 每组一张部位 ID 表；组名只认已知的那几个，没写的组用随包初值。写成空表就是这一组不看。
        Map<InjuryGroup, List<String>> groups = new EnumMap<>(InjuryGroup.class);
        Set<String> known = new HashSet<>();
        for (InjuryGroup group : InjuryGroup.values()) {
            known.add(group.key());
            groups.put(group, ids(parts, fallback, group.key()));
        }
        if (!known.containsAll(parts.keySet())) {
            throw HealingErrors.invalid(HealingErrors.CONFIG);
        }
        return groups;
    }

    private static List<String> ids(TomlTable table, TomlTable fallback, String key) {
        // 一张不重复的非空字符串表。
        TomlArray array = table.contains(key) ? table.getArray(key) : fallback.getArray(key);
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            if (!(array.get(index) instanceof String id) || id.isBlank() || ids.contains(id)) {
                throw HealingErrors.invalid(HealingErrors.CONFIG);
            }
            ids.add(id);
        }
        return ids;
    }

    private static Map<InjuryStrength, Double> strengths(TomlTable table) {
        // 没写的强度用枚举里的初值（和随包配置一致）；写了的要在这一条允许的范围里。
        Map<InjuryStrength, Double> strengths = new EnumMap<>(InjuryStrength.class);
        Set<String> known = new HashSet<>();
        for (InjuryStrength strength : InjuryStrength.values()) {
            known.add(strength.key());
            double value = table.getDouble(strength.key(), strength::initial);
            if (!strength.accepts(value)) {
                throw HealingErrors.invalid(HealingErrors.CONFIG);
            }
            strengths.put(strength, value);
        }
        if (!known.containsAll(table.keySet())) {
            throw HealingErrors.invalid(HealingErrors.CONFIG);
        }
        return strengths;
    }
}
