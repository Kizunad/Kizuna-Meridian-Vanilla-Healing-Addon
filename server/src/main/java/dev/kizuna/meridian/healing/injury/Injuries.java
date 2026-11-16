package dev.kizuna.meridian.healing.injury;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 一具身体此刻的伤势：每组部位伤到哪一档（0 没事、1 受伤、2 归零），两眼、两肺坏了几成。
 * 只从身体快照算，不存倒计时：HP 回到门槛以上，下一次算出来就没有这一档了。
 */
public record Injuries(Map<InjuryGroup, Integer> tiers, boolean blind, int lungsLost) {
    public static final int NONE = 0;
    public static final int INJURED = 1;
    public static final int ZERO = 2;
    /** 肺：一边归零记 1，认得的全归零记 2。 */
    public static final int ALL_LUNGS = 2;
    private static final Injuries HEALTHY = new Injuries(Map.of(), false, NONE);

    public Injuries {
        tiers = Map.copyOf(tiers);
    }

    public int tier(InjuryGroup group) {
        return tiers.getOrDefault(group, NONE);
    }

    public static Injuries assess(BodySnapshot body, InjuryConfig config) {
        // 整套关着、或者人已经死了，都不挂伤势。
        if (!config.enabled() || body.getVitality().getDead()) {
            return HEALTHY;
        }
        Map<String, Double> maximum = body.getStructure().getPartsList().stream()
                .collect(Collectors.toMap(part -> part.getId(), part -> part.getMaxHp()));
        Map<String, Double> hp = body.getPartStatesList().stream()
                .collect(Collectors.toMap(part -> part.getId(), part -> part.getHp()));
        Map<InjuryGroup, Integer> tiers = new EnumMap<>(InjuryGroup.class);
        for (InjuryGroup group : InjuryGroup.values()) {
            int worst = NONE;
            for (String id : config.parts().get(group)) {
                // 这具身体没有这个部位（不是人体模板、改造掉了）就不看它。
                if (hp.containsKey(id) && maximum.getOrDefault(id, 0.0) > 0) {
                    worst = Math.max(worst, tier(hp.get(id), maximum.get(id), config.injuredRatio()));
                }
            }
            if (worst != NONE) {
                tiers.put(group, worst);
            }
        }
        Map<String, Double> organs = body.getOrganStatesList().stream()
                .collect(Collectors.toMap(organ -> organ.getId(), organ -> organ.getHp()));
        List<Double> eyes = present(config.eyes(), organs::get);
        List<Double> lungs = present(config.lungs(), organs::get);
        boolean blind = !eyes.isEmpty() && eyes.stream().allMatch(value -> value == 0);
        long lost = lungs.stream().filter(value -> value == 0).count();
        int lungsLost = lost == 0 ? NONE : (lost == lungs.size() ? ALL_LUNGS : INJURED);
        return new Injuries(tiers, blind, lungsLost);
    }

    private static int tier(double hp, double maximum, double injuredRatio) {
        if (hp <= 0) {
            return ZERO;
        }
        return hp <= maximum * injuredRatio ? INJURED : NONE;
    }

    private static List<Double> present(List<String> ids, Function<String, Double> hp) {
        // 配置里写了、这具身体上也真有的那些器官的 HP。
        return ids.stream().map(hp).filter(value -> value != null).toList();
    }

    /**
     * 这些伤势落到人身上是什么：各项倍数、不能疾跑、哪只手拿不住、看不看得见、挂哪几个效果。
     *
     * @param mainArmLeft 惯用手是不是左手：攻击和挖掘只看惯用手那条手臂
     */
    public InjuryBurden burden(InjuryConfig config, boolean mainArmLeft) {
        if (tiers.isEmpty() && !blind && lungsLost == NONE) {
            return InjuryBurden.NONE;
        }
        return InjuryBurdens.of(this, config, mainArmLeft);
    }
}
