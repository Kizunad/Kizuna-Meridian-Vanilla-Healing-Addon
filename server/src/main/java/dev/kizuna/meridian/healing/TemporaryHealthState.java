package dev.kizuna.meridian.healing;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.healing.pb.HealingEffects.EffectState;
import dev.kizuna.meridian.healing.pb.HealingEffects.PartShield;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import java.util.LinkedHashMap;
import java.util.Map;

/** 此附属拥有的临时护盾与上限增量；不保存第二份身体 HP。 */
public final class TemporaryHealthState {
    private String bodyId = "";
    private int absorptionLevel;
    private final Map<String, Double> shields = new LinkedHashMap<>();
    private final Map<String, Double> boostGrants = new LinkedHashMap<>();

    public void bind(BodySnapshot body) {
        // 换成另一具身体时不能继承旧身体的局部效果。
        if (!bodyId.equals(body.getId())) {
            bodyId = body.getId();
            absorptionLevel = 0;
            shields.clear();
            boostGrants.clear();
        }
        shields.keySet().removeIf(id -> body.getStructure().getPartsList().stream().noneMatch(part -> part.getId().equals(id)));
    }

    public int absorptionLevel() {
        // 等级只用于识别加载与效果边界，不能按剩余黄心推断并重新补满。
        return absorptionLevel;
    }

    public void grantAbsorption(BodySnapshot body, int level, HealingConfig config) {
        // 原版效果刷新会重新授予；分配口径在此一次确定，不在命中时转移余额。
        bind(body);
        clearAbsorption();
        absorptionLevel = level;
        if (level <= 0 || body.getVitality().getDead()) {
            return;
        }
        int count = body.getStructure().getPartsCount();
        double perPart = 4.0 * level * config.hpPerVanillaHp();
        if (config.sharedAbsorption() && count > 0) {
            perPart /= count;
        }
        for (var part : body.getStructure().getPartsList()) {
            shields.put(part.getId(), perPart);
        }
    }

    public void clearAbsorption() {
        // 到期、牛奶及显式移除效果共用同一个清空入口。
        shields.clear();
        absorptionLevel = 0;
    }

    public double shield(String partId) {
        // 未登记部位的护盾为零，不能借用其他部位的剩余量。
        return shields.getOrDefault(partId, 0.0);
    }

    public void consumeShield(String partId, double amount) {
        // 只在身体伤害提交成功后扣除，完全吸收也不产生伪造的零伤害事务。
        double remaining = Math.max(0, shield(partId) - amount);
        if (shields.containsKey(partId)) {
            shields.put(partId, remaining);
        }
    }

    public double totalShield() {
        // 原版黄心只展示局部余额之和，不再作为另一套可扣除的全局护盾。
        return shields.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    public Map<String, Double> boostGrants() {
        // 调用方只能读取授予记录，上限本身必须通过框架命令更新。
        return Map.copyOf(boostGrants);
    }

    public void recordBoost(Map<String, Double> grants) {
        // 核心确认上限变更后才记录本附属增量，撤销时不会扣到其他来源的增益。
        boostGrants.clear();
        boostGrants.putAll(grants);
    }

    public byte[] encode() {
        // NBT 只包裹 protoc 消息字节，重连保留各部位已经消耗的黄心。
        var state = EffectState.newBuilder().setBodyId(bodyId).setAbsorptionLevel(absorptionLevel);
        shields.forEach((id, amount) -> state.addShields(PartShield.newBuilder().setPartId(id).setAmount(amount)));
        boostGrants.forEach((id, amount) -> state.addHealthBoostGrants(PartShield.newBuilder().setPartId(id).setAmount(amount)));
        return state.build().toByteArray();
    }

    public void decode(byte[] bytes) {
        // 存档边界同样限制规模和数值；损坏数据拒绝加载，不能静默重新授予效果。
        if (bytes.length > 65536) {
            throw HealingErrors.invalid(HealingErrors.EFFECT_STATE);
        }
        try {
            EffectState state = EffectState.parseFrom(bytes);
            if (state.getAbsorptionLevel() < 0 || !state.getUnknownFields().asMap().isEmpty()) {
                throw HealingErrors.invalid(HealingErrors.EFFECT_STATE);
            }
            Map<String, Double> restoredShields = readAmounts(state.getShieldsList());
            Map<String, Double> restoredGrants = readAmounts(state.getHealthBoostGrantsList());
            bodyId = state.getBodyId();
            absorptionLevel = state.getAbsorptionLevel();
            shields.clear();
            shields.putAll(restoredShields);
            recordBoost(restoredGrants);
        } catch (InvalidProtocolBufferException failure) {
            throw new IllegalArgumentException(HealingErrors.EFFECT_STATE, failure);
        }
    }

    private static Map<String, Double> readAmounts(java.util.List<PartShield> entries) {
        // 先完整验证临时表，再整体发布，避免坏存档留下半份效果。
        if (entries.size() > 256) {
            throw HealingErrors.invalid(HealingErrors.EFFECT_STATE);
        }
        var amounts = new LinkedHashMap<String, Double>();
        for (PartShield entry : entries) {
            if (entry.getPartId().isBlank() || entry.getPartId().length() > 256
                    || !Double.isFinite(entry.getAmount()) || entry.getAmount() < 0
                    || !entry.getUnknownFields().asMap().isEmpty()
                    || amounts.putIfAbsent(entry.getPartId(), entry.getAmount()) != null) {
                throw HealingErrors.invalid(HealingErrors.EFFECT_STATE);
            }
        }
        return amounts;
    }
}
