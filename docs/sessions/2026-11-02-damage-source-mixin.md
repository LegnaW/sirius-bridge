# 2026-11-02 会话：远程玩家伤害来源修复

## Plan + Spec（用户报告并确认修复方向）

### 根因

旧实现假设 `ClientboundDamageEventPacket` 会把 `DamageSource` 保存到 `LocalPlayer.lastDamageSource`。实际 1.21.1 源码显示，`LivingEntity.handleDamageEvent` 只做受击动画与音效；`lastDamageSource` 在服务端 `LivingEntity.hurt` 中写入。远程客户端因此只有 null 或旧来源，玩家攻击也显示 unknown。

### 方案

新增客户端专用 `LivingEntityMixin`，在 `handleDamageEvent` HEAD 把真实 `DamageSource` 交给 `ClientDamageSources`。记录只服务本地玩家，world 退出时清空；`EventPusher` 继续每 tick 比较实际生命值，并使用这份来源生成 attacker/direct_entity。

这是捕获客户端已有数据，不根据附近实体猜测，也不改变伤害、无敌帧或网络包。协议和字段保持 1.9，不新增工具或配置。Mixin 只加载客户端。

### 验收

- 编译、完整 build 与既有 689 项 smoke 通过。
- 部署到实际实例并重启后，生存模式玩家近战应返回玩家 name/type/uuid；怪物近战返回怪物；骷髅射箭时 attacker 与 direct_entity 按包信息展示。
- 吸收-only、回血和未知环境来源仍遵守原行为。

## 二次问题：血量包与来源包时序

Mixin 正常加载后仍有 unknown。血量变化和伤害来源来自不同网络包，血量可能先下降，旧实现立即发出 unknown，来源稍后到达时已没有新的血量变化。

调整为 250ms 归因窗口：最近 250ms 内的新鲜来源可与待处理扣血配对，来源先到或后到都覆盖；超过窗口仍无来源才输出 unknown。已被使用的来源不得用于下一次扣血，连续扣血保留最早 before 与最新 after。纯 `DamageContracts.Attributor` 状态机负责窗口逻辑并新增回归。

## 收尾日志

### 文件改动

- `src/main/java/io/sirius/bridge/mixin/LivingEntityMixin.java`：在客户端受击入口捕获真实 DamageSource。
- `src/main/java/io/sirius/bridge/ClientDamageSources.java`：保存本地玩家最近一次来源，退出世界时清空。
- `src/main/resources/sirius_bridge.mixins.json`、`src/main/templates/META-INF/neoforge.mods.toml`：注册客户端专用 Mixin。
- `EventPusher.java`：改用捕获的来源，保留每 tick 新鲜度判断和实际扣血过滤。
- `BRIDGE.md`、`docs/PROGRESS.md`：同步客户端归因机制和部署状态。

### 自动验证

- `./gradlew.bat build`：成功，**696 passed / 0 failed**，新增血量先到、来源后到、250ms 窗口、旧来源不复用和连续扣血回归。
- jar 内已确认包含 `sirius_bridge.mixins.json` 和 `[[mixins]]` 配置。

### 部署与未完成

- 时序修复 jar 已复制到实际实例，源/目标 SHA-256 均为 `58cffc07b9bdf268a4e1afa5c65ea94c9eba69d083b2fbf2d3574b18cf47a9b3`。
- 上一版 jar 已备份到 `build/runtime-backup/20261102-223912/`，首次 Mixin 修复前 jar 备份在 `build/runtime-backup/20261102-222824/`。
- Minecraft 尚需重启；玩家近战、怪物和弹射物的真实来源仍未完成真机验收。
