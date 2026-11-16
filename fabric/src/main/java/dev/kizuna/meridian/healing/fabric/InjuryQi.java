package dev.kizuna.meridian.healing.fabric;

import dev.kizuna.meridian.healing.HealingBridge;
import dev.kizuna.meridian.healing.injury.InjuryStrength;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CapabilityModifier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.Optional;

/**
 * 头晕（头受伤）让真元摄入变少：在身体上给每边肺挂一条摄入倍率的能力修正，头好了就撤。
 * 身体里的能力修正都有到期时刻、最长 5 分钟（框架的上限），所以挂 4 分钟一条，剩不到一半时换一条新的；
 * 人死了由核心清掉。
 */
final class InjuryQi {
    static final String ID_PREFIX = "meridian_vanilla_healing:dizzy/";
    /** 框架允许一条能力修正挂多久（默认 5 分钟，服主可用系统属性改）。 */
    private static final long LIMIT_MILLIS =
            dev.kizuna.meridian.core.CapabilityModifier.MAX_CURVE_DURATION_MILLIS;
    static final long DURATION_MILLIS = Math.min(4 * 60 * 1000L, LIMIT_MILLIS);
    private static final long RENEW_MILLIS = DURATION_MILLIS / 2;

    private InjuryQi() {
        // 只有静态核对。
    }

    static void reconcile(LivingEntity entity, InjuryTracker tracker) {
        // 先拿手上这份身体看要不要动；要动才向宿主重新读一份最新的，照最新的版本提交。
        if (tracker.body == null || !needsChange(tracker.body, tracker)) {
            return;
        }
        try {
            Optional<HealingBridge.Binding> binding = HealingFabric.inspect(entity);
            if (binding.isEmpty() || !needsChange(binding.get().body(), tracker)) {
                return;
            }
            tracker.body = HealingFabric.bridge().activity(binding.get(), plan(binding.get().body(), tracker));
        } catch (RuntimeException failure) {
            HealingFabric.report(entity, failure);
        }
    }

    private static boolean wanted(BodySnapshot body, InjuryTracker tracker) {
        // 头晕着、倍率真的小于 1、人还活着，才要挂。
        return tracker.burden.dizzy() && tracker.burden.qiIntake() < 1 && !body.getVitality().getDead();
    }

    private static boolean needsChange(BodySnapshot body, InjuryTracker tracker) {
        List<CapabilityModifier> mine = mine(body);
        if (!wanted(body, tracker)) {
            return !mine.isEmpty();
        }
        List<String> lungs = lungs(body);
        long now = body.getSettledAt().getElapsedMillis();
        boolean fresh = mine.size() == lungs.size() && mine.stream().allMatch(modifier ->
                lungs.contains(modifier.getOrganId()) && modifier.getIntakePeak() == tracker.burden.qiIntake()
                        && end(modifier) - now > RENEW_MILLIS);
        return !fresh;
    }

    private static BodyActivity.Builder plan(BodySnapshot body, InjuryTracker tracker) {
        // 旧的全撤；还要挂的话，从身体此刻的时钟起重新挂一轮。
        BodyActivity.Builder plan = BodyActivity.newBuilder();
        mine(body).forEach(modifier -> plan.addRemoveModifiers(modifier.getId()));
        if (wanted(body, tracker)) {
            long now = body.getSettledAt().getElapsedMillis();
            for (String lung : lungs(body)) {
                plan.addAddModifiers(CapabilityModifier.newBuilder().setId(ID_PREFIX + lung)
                        .setSource(HealingBridge.ACTIVITY_SOURCE).setOrganId(lung).setStartMillis(now)
                        .addCurve(CurvePoint.newBuilder().setOffsetMillis(0).setValue(1))
                        .addCurve(CurvePoint.newBuilder().setOffsetMillis(DURATION_MILLIS).setValue(1))
                        .setStrengthPeak(1).setBurstPeak(1).setEndurancePeak(1).setRecoveryPeak(1)
                        .setIntakePeak(tracker.burden.qiIntake()));
            }
        }
        return plan;
    }

    private static List<CapabilityModifier> mine(BodySnapshot body) {
        return body.getModifiersList().stream().filter(modifier -> modifier.getId().startsWith(ID_PREFIX)).toList();
    }

    private static List<String> lungs(BodySnapshot body) {
        // 配置里写的肺，而且这具身体上真有。
        List<String> configured = HealingFabric.config().injury().lungs();
        return body.getStructure().getOrgansList().stream().map(organ -> organ.getId())
                .filter(configured::contains).toList();
    }

    private static long end(CapabilityModifier modifier) {
        return modifier.getStartMillis() + modifier.getCurve(modifier.getCurveCount() - 1).getOffsetMillis();
    }
}
