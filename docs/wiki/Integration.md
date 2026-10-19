# 构建、协议与验证

## 本地构建与安装

需要 Java 17、Minecraft 1.20.1、Fabric Loader 0.16.10、Fabric API 0.92.3+1.20.1。相邻仓库 Framework 与 Vanilla Damage Addon 通过 Gradle composite build 接入；`local.meridian:*` 是本地替换键，不是发布坐标。

```sh
./gradlew build
./gradlew :fabric:runGametest
./gradlew :fabric:runGametestConfigured
python3 scripts/check_docs.py
git diff --check
```

安装 `fabric/build/libs/` 下不带 `dev` 或 `sources` 的重映射 JAR，并另外安装匹配本次协议的 Vanilla Damage Addon、Fabric API 和提供身体的生物 Addon，例如 Human。Damage 不嵌套进本包；Framework 服务端库与纯规则依赖由构建嵌入。执行发生在服务器，单机由集成服务器处理。

## 跨附属协议

`meridian_health_host` 提供 Framework `BodyHealthHost.exchange(byte[])`，交换 `HealthHostRequest / HealthHostResponse`。本包以实体 UUID 查询唯一宿主，固定身体 revision 后通过 `handleHealthEffect` 提交 `HealBody` 或 `SetPartHealthLimits`。失败不换宿主、不刷新版本重发、不回落到原版直接回血。

`meridian_damage_integration` 提供 Framework `BodyDamageIntegration`。`prepare` 接收固定命中与最终剂量的 `DamageIntegrationContext`，只读生成 `DamageIntegrationPlan`，可以减少剂量并提供受校验保命下限。Damage 将剩余伤害通过原宿主提交；成功后发送 `DamageIntegrationCommit`，本包才扣除局部黄心并同步血条。完全吸收无需 HP 事务。多个集成方同时认领会拒绝，不隐式决定叠加顺序。

成功效果响应携带对应请求的固定 `HealthResult`，`death_prevented` 决定是否调用原版图腾；不能从血条或事后 HP 比例猜测。所有交换均使用 Framework 的 protoc 消息，不接受客户端上传治疗剂量、效果余额或保命事实。

效果余额与本附属授予的上限增量由 `shared/src/main/proto/kizuna/meridian/healing/v1/effects.proto` 定义，保存于实体 `MeridianHealingEffects` NBT 字节数组。读取限制规模、目标重复和数值，不以重新授予来掩盖坏存档。

## 验证边界

整体回归覆盖均分守恒、满血溢出、零 HP 排除、配置和余额编码往返。GameTest 运行真实 Minecraft 服务端，经过 Mixins、Damage 及 Framework 实际命令事务；覆盖玩家与非玩家治疗、实际药水/金苹果消费、再生、直接 setter 拦截、局部黄心扣除、到期与牛奶、效果重载、生命提升、图腾、虚空、亡灵瞬间治疗伤害、自然回血及世界规则。

`runGametest` 验证默认配置；`runGametestConfigured` 在独立生成目录验证每部位全额黄心、生命提升启用、图腾禁用和规则覆盖。测试宿主与样例构型仅在 GameTest source set，不进入安装 JAR。GameTest 是实际服务器运行验证，不能代替 Windows 手动游玩验收。

身体 HP、死亡保命与成功回执在框架内部原子提交。实体黄心、图腾物品、副作用和实体 NBT 与身体 Store 目前没有跨系统崩溃事务；成功提交后中断仍可能需要宿主恢复协调，不声称所有副作用恰好一次。保命图腾不是已死亡身体的复活规则，重生仍保留身体伤势。
