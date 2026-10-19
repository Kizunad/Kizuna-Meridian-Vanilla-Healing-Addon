# 仓库协作约定

- 原版治疗、自然回血和临时黄心效果归此附属；人体损伤策略归 Human，身体 HP 与死亡事务归 Framework。
- `server/` 保存纯配置与 Protobuf 规则；`fabric/` 接入 Minecraft。只使用公开健康宿主和伤害集成协议，不依赖 Human 内部类。
- 简体中文沟通、文档和函数内部意图注释。按读取、校验、计算、提交、结果顺序编写，优先直白可读的实现。
- 错误文案集中于 `server/.../HealingErrors.java`。状态只在服务端改变；不接受客户端治疗剂量或效果余额。
- 跨附属通信使用 Framework Protobuf；本附属持久效果以 `shared/src/main/proto/` 的 protoc 消息保存，不复制身体 HP 账本。
- 只保留必要整体回归；交付运行构建、回归、GameTest、文档与 diff 检查。
- 文档唯一来源 `docs/wiki/`，README 只作入口。使用 patch 修改文件，不覆盖其他仓库的未提交工作。
- 未获授权不提交、推送或创建远端仓库。许可 PolyForm Noncommercial 1.0.0，保留第三方许可。
