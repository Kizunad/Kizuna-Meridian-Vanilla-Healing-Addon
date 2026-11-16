package dev.kizuna.meridian.healing;

import dev.kizuna.meridian.protocol.ActivityHostProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityHostMessages.ActivityHostRequest;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.server.BodyActivityHost;
import dev.kizuna.meridian.server.BodyHealthHost;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 固定唯一健康宿主，失败时不能回退到另一个 HP 账本；伤势的能力修正经活动宿主提交。 */
public final class HealingBridge {
    /** 本附属向活动宿主报的调用身份，也是它挂的能力修正的来源。 */
    public static final String ACTIVITY_SOURCE = "meridian_vanilla_healing:injury";
    private final List<BodyHealthHost> hosts;
    private final List<BodyActivityHost> activityHosts;

    public HealingBridge(List<BodyHealthHost> hosts, List<BodyActivityHost> activityHosts) {
        // 缺少宿主是装配错误；只有宿主明确返回 unbound 才代表原版实体。
        this.hosts = List.copyOf(hosts);
        this.activityHosts = List.copyOf(activityHosts);
        if (this.hosts.isEmpty()) {
            throw HealingErrors.invalid(HealingErrors.NO_HOST);
        }
    }

    public Optional<Binding> inspect(String subjectId) {
        // 全部宿主都检查一次以确认唯一归属；明确未绑定才允许原版路径。
        var request = HealthHostRequest.newBuilder().setProtocolVersion(1).setRequestId(UUID.randomUUID().toString())
                .setSubjectId(subjectId).setInspect(true).build();
        Binding binding = null;
        for (BodyHealthHost host : hosts) {
            HealthHostResponse response = exchange(host, request);
            if (response.hasUnbound()) {
                continue;
            }
            if (binding != null) {
                throw HealingErrors.invalid(HealingErrors.HOST);
            }
            binding = new Binding(subjectId, host, response.getBody());
        }
        return Optional.ofNullable(binding);
    }

    public BodySnapshot apply(Binding binding, BodyCommand.Builder command) {
        // 请求身份、身体身份和版本在同一次读取后固定，拒绝后不刷新版本重发。
        String requestId = UUID.randomUUID().toString();
        var effect = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(requestId)
                .setBodyCommand(command.setBodyId(binding.body().getId())
                        .setExpectedRevision(binding.body().getRevision())).build();
        var request = HealthHostRequest.newBuilder().setProtocolVersion(1).setRequestId(requestId)
                .setSubjectId(binding.subjectId()).setEffect(effect).build();
        HealthHostResponse response = exchange(binding.host(), request);
        if (!response.hasBody() || !response.getBody().getId().equals(binding.body().getId())) {
            throw HealingErrors.invalid(HealingErrors.HOST);
        }
        return response.getBody();
    }

    /** 提交一份只动能力修正的活动计划；返回提交后的身体。绑着这具身体的宿主不收活动时报宿主错误。 */
    public BodySnapshot activity(Binding binding, BodyActivity.Builder plan) {
        // 和治疗一样固定读取时的版本；逐个活动宿主问，认领这具身体的那个给出结果。
        String requestId = UUID.randomUUID().toString();
        var envelope = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(requestId)
                .setBodyCommand(BodyCommand.newBuilder().setBodyId(binding.body().getId())
                        .setExpectedRevision(binding.body().getRevision()).setExecuteActivity(plan)).build();
        byte[] payload = ActivityHostProtobuf.encodeRequest(ActivityHostRequest.newBuilder().setProtocolVersion(1)
                .setRequestId(requestId).setSubjectId(binding.subjectId()).setActivity(envelope).build());
        for (BodyActivityHost host : activityHosts) {
            HealthHostResponse response = HealthHostProtobuf.decodeResponse(host.exchange(ACTIVITY_SOURCE, payload));
            if (!response.getRequestId().equals(requestId)) {
                throw HealingErrors.invalid(HealingErrors.HOST);
            }
            if (response.hasFailure()) {
                throw new HealingErrors.HostRejected(response.getFailure().getCode());
            }
            if (response.hasBody() && response.getBody().getId().equals(binding.body().getId())) {
                return response.getBody();
            }
        }
        throw HealingErrors.invalid(HealingErrors.HOST);
    }

    private static HealthHostResponse exchange(BodyHealthHost host, HealthHostRequest request) {
        // 不把协议拒绝视为未绑定，避免核心失败后依然产生原版回血。
        HealthHostResponse response = HealthHostProtobuf.decodeResponse(
                host.exchange(HealthHostProtobuf.encodeRequest(request)));
        if (!response.getRequestId().equals(request.getRequestId())) {
            throw HealingErrors.invalid(HealingErrors.HOST);
        }
        if (response.hasFailure()) {
            throw new HealingErrors.HostRejected(response.getFailure().getCode());
        }
        return response;
    }

    public record Binding(String subjectId, BodyHealthHost host, BodySnapshot body) { }
}
