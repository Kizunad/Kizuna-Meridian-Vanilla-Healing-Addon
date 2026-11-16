package dev.kizuna.meridian.healing.injury;

import java.util.Locale;

/** 挂在人身上给人看的伤势状态效果；等级 Ⅰ、Ⅱ 对应放大值 0、1。 */
public enum InjurySign {
    LEG_INJURY, HIP_INJURY, ARM_INJURY, CRIPPLED_HAND, ABDOMEN_INJURY, SHORT_BREATH, NECK_INJURY, DIZZINESS;

    public String path() {
        // 状态效果注册名、图标文件名和语言键都用它。
        return name().toLowerCase(Locale.ROOT);
    }
}
