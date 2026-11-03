# 2026-11-02 会话：Baritone follow 工具

## Plan + Spec（用户已确认）

依据本机 Baritone API jar 1.11.2 的 `javap` 结果与源码，`IFollowProcess.follow(Predicate<Entity>)`、`following()`、`currentFilter()` 和 `cancel()` 都是公开 API。跟随不是固定坐标的 goto，目标实体卸载后 `isActive()` 会变 false，不能声称永久跟随。

新增协议 1.9 `baritone.follow`：

- 必须用 `uuid` 或精确玩家名 `name` 二选一指定一个非自身玩家，不提供“跟随所有玩家”的危险入口。
- `follow_radius` 默认 10，允许 1..64，由 agent 每次调用调整；写入 Baritone `followRadius`。
- `allow_break`、`allow_place` 默认 false，保持不拆建筑的安全默认。
- 启动前停止 tunnel 和既有移动，返回 `following` / `target_not_found` / `not_in_game`，附目标身份、位置和设置。
- `baritone.status` 增加 `follow.active/targets/radius`，跟随激活时状态为 `following`；`baritone.stop` 明确取消 follow。

插件提供 `sirius_follow`，后台每 2 秒检查目标是否消失及是否持续无进展。目标丢失或卡住时停止并唤醒一次；调用方取消、被新 walk/follow 取代、init 接管和会话销毁不发送误报。

验收：参数、协议、能力注册、状态 JSON、默认半径和停止语义自动回归；插件丢失/卡住/正常移动测试；两仓完整构建。真机需验证指定玩家跟随、默认 10 格、调半径、丢失提醒、卡住提醒和 stop。

## 收尾日志

### 文件改动

- `BaritoneApiContracts.java`：新增单目标、默认 10 格、破坏/放置开关校验。
- `BaritoneTools.java`：注册 `baritone.follow`，通过公开 FollowProcess API 启动；状态暴露 follow 对象，stop 明确取消 follow。
- `Capabilities.java`、`schema/index.json`、`schema/tools/baritone.follow.json`：协议 1.9 与第 28 个能力。
- `SmokeMain.java`：新增默认/自定义半径、缺失/冲突目标、半径边界及 28 能力注册一致性回归。
- `BRIDGE.md`：同步选择器、结果、限制、状态和停止语义。

### 自动验证

- `./gradlew.bat build`：成功，**689 passed / 0 failed**。
- 本机 API jar 通过 `javap` 确认 `follow/following/currentFilter/cancel` 公开签名；实现未使用聊天命令或内部 FollowProcess 类。

### 部署与未完成

- 协议 1.9 jar 和匹配的 Baritone API 已复制到实际配置的 `1.21.1-NeoForge_21.1.265/mods/`，源/目标 SHA-256 均一致；桥哈希为 `94ac92e2d842bbbdb6bc978978bded63bdc5b7e450945fce91db26aa570c904f`。
- 旧 1.7 桥与旧 Baritone API 已备份到 `build/runtime-backup/20261102-221737/`。
- 实际实例此前日志明确为协议 1.7，因此 damage 订阅存在但桥端没有事件来源。替换后仍需重启 Minecraft，确认日志变为 1.9，再进行生存模式扣血和指定玩家跟随验收。
