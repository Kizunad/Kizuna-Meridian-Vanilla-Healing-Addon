# 原版治疗映射附属

本附属将已绑定身体实体的原版 `LivingEntity.heal` 映射到 Framework `HealBody`，覆盖瞬间治疗、生命恢复、食物效果、信标和调用此入口的其他模组。原版 HP 只按身体部位总 HP 比例显示；物品仍可正常食用、饮用。

身体归 Framework 唯一账本，生物死亡条件归生物 Addon。本包不创建人体、不复制人体定义。原版伤害由独立 Vanilla Damage Addon 接入，两者通过公开 Protobuf 交换局部黄心与本次保命方案。

- [治疗、黄心与配置](Healing-Rules.md)
- [构建、协议与验证](Integration.md)

当前为源码实现，尚未发布 Maven 包或正式发行版本。
