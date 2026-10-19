package dev.kizuna.meridian.healing;

import org.tomlj.Toml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** 单位换算、分配策略及原版行为开关，不包含人体部位名称。 */
public record HealingConfig(double hpPerVanillaHp, boolean sharedAbsorption, double naturalMultiplier,
                            boolean healthBoostChangesLimits, boolean totemEnabled, double totemMinimumRatio,
                            boolean respectPeaceful, boolean respectNaturalRegeneration) {
    public HealingConfig {
        // 极端值必须在载入时拒绝，不能等进入实体事件后才部分消耗资源。
        if (!Double.isFinite(hpPerVanillaHp) || hpPerVanillaHp <= 0
                || !Double.isFinite(naturalMultiplier) || naturalMultiplier < 0 || naturalMultiplier > 1
                || !Double.isFinite(totemMinimumRatio) || totemMinimumRatio <= 0 || totemMinimumRatio > 1) {
            throw HealingErrors.invalid(HealingErrors.CONFIG);
        }
    }

    public static HealingConfig load(Path path) {
        // 首次启动复制唯一默认配置；后续只读取，避免覆盖服主调整。
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                try (InputStream source = HealingConfig.class.getResourceAsStream("/meridian-vanilla-healing.toml")) {
                    Files.copy(source, path);
                }
            }
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException(HealingErrors.CONFIG, failure);
        }
    }

    public static HealingConfig parse(String text) {
        // 显式列出允许的键，拼错配置不能静默落回默认值。
        var table = Toml.parse(text);
        Set<String> allowed = Set.of("hp_per_vanilla_hp", "absorption_distribution", "natural_healing_multiplier",
                "health_boost_changes_part_limits", "totem_enabled", "totem_minimum_hp_ratio",
                "respect_peaceful_difficulty", "respect_natural_regeneration");
        try {
            if (table.hasErrors() || !allowed.containsAll(table.keySet())) {
                throw HealingErrors.invalid(HealingErrors.CONFIG);
            }
            String distribution = table.getString("absorption_distribution", () -> "shared");
            if (!distribution.equals("shared") && !distribution.equals("per_part")) {
                throw HealingErrors.invalid(HealingErrors.CONFIG);
            }
            return new HealingConfig(table.getDouble("hp_per_vanilla_hp", () -> 1.0), distribution.equals("shared"),
                    table.getDouble("natural_healing_multiplier", () -> 0.5),
                    table.getBoolean("health_boost_changes_part_limits", () -> false),
                    table.getBoolean("totem_enabled", () -> true), table.getDouble("totem_minimum_hp_ratio", () -> 0.25),
                    table.getBoolean("respect_peaceful_difficulty", () -> true),
                    table.getBoolean("respect_natural_regeneration", () -> true));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(HealingErrors.CONFIG, failure);
        }
    }
}
