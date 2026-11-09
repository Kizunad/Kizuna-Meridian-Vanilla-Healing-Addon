# 构建、协议与验证

## 本地构建与安装

需要 Java 25、Minecraft 26.2、Fabric Loader 0.20.1、Fabric API 0.161.0+26.2。相邻仓库 Framework、Vanilla Damage Addon 通过 Gradle composite build 接入，Damage 依赖的功法库由 Damage 自己的 settings 嵌套组合：`settings.gradle` 应用 Framework 的 `scripts/workspace.gradle`，用 `includeSource` 按装配清单组合，位置可用 `-Pkizuna.dir.<清单 id>` 改指，版本取版本目录 `libs`。`local.meridian:*` 是框架的本地替换键，不是发布坐标；Damage fabric 按真实坐标声明依赖，替换键用其工程的真实坐标。

```sh
./gradlew build
./gradlew :fabric:runGametest
./gradlew :fabric:runGametestConfigured
python3 scripts/check_docs.py
git diff --check
```

安装 `fabric/build/libs/` 下不带 `sources` 后缀的 JAR（26.2 不混淆，构建不重映射），Meridian 九仓按适用端整套必装并提供 Fabric API。开发运行统一应用 Framework 的 `scripts/full-assembly.gradle`，固定装配同级安装产物。Damage 不嵌套进本包；Framework 服务端库与纯规则依赖由构建嵌入。执行发生在服务器，单机由集成服务器处理。缺健康宿主启动时报错；已装配宿主明确返回实体未绑定时才走原版治疗。

## 跨附属协议

`meridian_health_host` 提供 Framework `BodyHealthHost.exchange(byte[])`，交换 `HealthHostRequest / HealthHostResponse`。本包以实体 UUID 查询唯一宿主，固定身体 revision 后通过 `handleHealthEffect` 提交 `HealBody` 或 `SetPartHealthLimits`。失败不换宿主、不刷新版本重发、不回落到原版直接回血。

宿主查询仍严格拒绝协议错误与失败响应。原版事件边界会记录异常并停止当前治疗、自然回血或临时效果；不会让失败冒出玩家 tick，也不会把失败视为未绑定。自然回血未提交时不扣相应饥饿消耗。附加和平回血仅在实际满足治疗时间与血量条件时查询宿主。

原版 `LivingEntity` 构造器设置初始血量时沿用原版 setter，允许区块生成线程正常构造生物。治疗适配器只在实体已登记于 `ServerLevel`、未移除且位于服务端主线程时访问身体宿主；工作线程和未入世实体直接跳过，不查询宿主。宿主自身的线程与身份校验仍严格保留。

读取 NBT 只恢复效果余额并标记待刷新；实体加载也只设标记，等首次 tick、各宿主完成认领后投影 HP 与黄心。已绑定身体返回 `BODY_PAUSED` 时保留归属，封住原版回血和 setter 旁路，停止自动查询且只告警一次；玩家重新登录产生新实体后重新查询同一身体。其他同步失败每实体仅记录首个堆栈。

`meridian_damage_integration` 提供 Framework `BodyDamageIntegration`。`prepare` 接收固定命中与最终剂量的 `DamageIntegrationContext`，只读生成 `DamageIntegrationPlan`，可以减少剂量并提供受校验保命下限。Damage 将剩余伤害通过原宿主提交；成功后发送 `DamageIntegrationCommit`，本包才扣除局部黄心并同步血条。完全吸收无需 HP 事务。多个集成方同时认领会拒绝，不隐式决定叠加顺序。

初始化时向 Damage 的 `registerDamageScope` 登记 `beginDamage` / `endDamage`，使指定部位直接伤害
复用同一局部黄心、图腾上下文。原版 `hurtServer` 的上下文 mixin 保持原路径；嵌套调用按栈退出。
编译依赖保持 Healing → Damage，Damage 不读取 Healing 的安装 JAR。

成功效果响应携带对应请求的固定 `HealthResult`，`death_prevented` 决定是否调用原版图腾；不能从血条或事后 HP 比例猜测。所有交换均使用 Framework 的 protoc 消息，不接受客户端上传治疗剂量、效果余额或保命事实。

效果余额与本附属授予的上限增量由 `shared/src/main/proto/kizuna/meridian/healing/v1/effects.proto` 定义，保存于实体 `MeridianHealingEffects` NBT 字节数组。读取限制规模、目标重复和数值，不以重新授予来掩盖坏存档。

## 验证边界

整体回归覆盖均分守恒、满血溢出、零 HP 排除、配置和余额编码往返。GameTest 运行真实 Minecraft 服务端，经过 Mixins、Damage 及 Framework 实际命令事务；覆盖玩家与非玩家治疗、实际药水/金苹果消费、再生、直接 setter 拦截、局部黄心扣除、到期与牛奶、效果重载、生命提升、图腾、虚空、亡灵瞬间治疗伤害、自然回血及世界规则。

构造生命周期回归在工作线程创建真实炽足兽及带黄心 NBT 的铁傀儡，检查初始满血、读 NBT 和异步调用均不查询宿主、未入世时不查询，入世后的首次 tick 正确投影 HP 与黄心。另验证暂停后跨多个刷新周期只查询一次，不回血、不改身体、不扣饥饿。饥饿与和平回血只在服务端玩家上执行，暂停、宿主拒绝、自然回血与和平回血用例的玩家取自功法库 `fabric` 的 `testFixtures`（`TestPlayers.detached`：正式网络处理器、不进玩家列表），经 Damage 组合的功法源码解析；其余玩家用例使用原版模拟玩家。

2026-10-21 原仓 Java 17 构建及 2 项整体回归通过；默认与配置变体各 5 项 GameTest 通过。未修复版本在新增构造回归中只有 1/20 HP，其余四项通过；修复版本保留运行期线程与宿主校验。包含本修复的 Windows 完整装配另通过双客户端换维度、重生、断线和重连流程。

`runGametest` 验证默认配置；`runGametestConfigured` 在独立生成目录验证每部位全额黄心、生命提升启用、图腾禁用和规则覆盖。测试宿主与样例构型仅在 GameTest source set，不进入安装 JAR。GameTest 是实际服务器运行验证，不能代替 Windows 手动游玩验收。

可用 `-PgametestRunDir=build/新目录` 与 `-PconfiguredGametestRunDir=build/新配置目录` 分别指定运行目录，
避免旧装配配置干扰新版本验证；默认目录保持原值，世界清理和配置变体写入均跟随指定目录。

身体 HP、死亡保命与成功回执在框架内部原子提交。实体黄心、图腾物品、副作用和实体 NBT 与身体 Store 目前没有跨系统崩溃事务；成功提交后中断仍可能需要宿主恢复协调，不声称所有副作用恰好一次。保命图腾不是已死亡身体的复活规则。玩家死亡重生由 Human 提交 Framework `ReviveBody`，局部 HP 至少恢复 10%、肌肉疲劳置满并扣寿元；本附属按复活后的权威快照同步红心并恢复普通治疗，类人生物不自动复活。
