package dev.kizuna.meridian.healing.injury;

import java.util.Locale;

/** 伤势按哪几组部位看；每组认哪些部位 ID 写在配置里，本附属不认识人体。 */
public enum InjuryGroup {
    LEG, HIP, LEFT_ARM, RIGHT_ARM, LEFT_HAND, RIGHT_HAND, ABDOMEN, CHEST, NECK, HEAD;

    public String key() {
        // 配置里用小写名，如 left_arm。
        return name().toLowerCase(Locale.ROOT);
    }
}
