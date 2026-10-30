package dev.kizuna.meridian.healing;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

/** 运行期诊断集中管理，宿主失败码继续使用框架协议。 */
public final class HealingErrors {
    public static final String NO_HOST = "缺少必需的 meridian_health_host 身体宿主";
    public static final String CONFIG = "治疗配置无效";
    public static final String HOST = "健康宿主响应错误或重复认领身体";
    public static final String EFFECT_STATE = "临时健康效果状态无效";
    public static final String HEAL_FAILED = "身体治疗或临时效果同步失败";
    public static final String BODY_PAUSED = "身体已暂停，停止治疗同步；请退出世界后重新加入以重新认领";

    private HealingErrors() {
        // 仅统一诊断，不在业务文件散落错误文案。
    }

    public static IllegalArgumentException invalid(String message) {
        // 保留调用阶段信息，避免宿主拒绝后回退到原版账本。
        return new IllegalArgumentException(message);
    }

    public static final class HostRejected extends IllegalArgumentException {
        private final FailureCode code;

        public HostRejected(FailureCode code) {
            // 宿主拒绝保留原始失败码，使暂停状态不会被误判为未绑定。
            super(HOST + ": " + code);
            this.code = code;
        }

        public FailureCode code() {
            // 调用方按结构化失败码决定是否停止自动同步。
            return code;
        }
    }
}
