package dev.kizuna.meridian.healing;

/** 运行期诊断集中管理，宿主失败码继续使用框架协议。 */
public final class HealingErrors {
    public static final String CONFIG = "治疗配置无效";
    public static final String HOST = "健康宿主响应错误或重复认领身体";
    public static final String EFFECT_STATE = "临时健康效果状态无效";
    public static final String HEAL_FAILED = "身体治疗或临时效果同步失败";

    private HealingErrors() {
        // 仅统一诊断，不在业务文件散落错误文案。
    }

    public static IllegalArgumentException invalid(String message) {
        // 保留调用阶段信息，避免宿主拒绝后回退到原版账本。
        return new IllegalArgumentException(message);
    }
}
