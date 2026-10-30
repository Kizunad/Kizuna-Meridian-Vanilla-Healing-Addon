package dev.kizuna.meridian.healing;

import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.server.BodyHealthHost;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 固定唯一健康宿主，失败时不能回退到另一个 HP 账本。 */
public final class HealingBridge {
    private final List<BodyHealthHost> hosts;

    public HealingBridge(List<BodyHealthHost> hosts) {
        // 缺少宿主是装配错误；只有宿主明确返回 unbound 才代表原版实体。
        this.hosts = List.copyOf(hosts);
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
