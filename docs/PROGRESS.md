# sirius-bridge 当前进度

最后更新：2026-11-04

## 当前状态：协议 1.15

- dig 支持严格方块目标，视角输出归一化且内部维持连续转向。

- input.click 支持指定 GUI 坐标，直接向当前 Screen 派发点击，目标是真机失焦可用。

- v2 两大板块为“完善接口底层 → 编写自带插件”。协议 1.10 的 block.get 与真实容器事务保持通过；1.11 新增 14 类核心事件；1.12 新增 `inventory.drop`，RPC 工具共 **33 个**。
- `inventory.drop {slot,count?}` 通过原版 `ClickType.THROW` 丢弃指定 0..40 背包槽位物品，count 省略为整组；补齐盔甲 36..39→菜单 5..8、副手 40→菜单 45 映射。按客户端槽位回读返回 dropped/partial/unknown，不注入 Q。用户已完成真机测试，规格见 `docs/sessions/2026-11-03-inventory-drop.md`。
- 新事件：block_update；entity_spawn/gone/hurt/death；item_pickup；player_join/leave；window_open/close/slot_update；held_item_changed；connection_state；baritone_state。
- bridge 忠实投递客户端原始事件，不做业务筛选、合并、冷却或默认降频；筛选、背压、去重和处理策略由连接侧及后续脚本负责。
- slot_update 来自真实服务端容器包，`server_packet` 不把某个包强行关联为一次操作成功；entity_death 在实体离开且 dead/dying 时发出，保留最近伤害来源。
- `movement_look` 只在 Baritone 寻路、路径计算、跟随或 Builder 活跃时允许触发；普通键盘移动不再触发 yaw 转向或 pitch 回平，显式转头优先规则和原有角速度不变。用户已真机确认行走不再被抢占。
- 核心事件规格、注入修复与逐项证据见 `docs/sessions/2026-11-03-core-event-sources.md`。

## 当前验证与部署

- v0.1.0 修复 NeoForge 前置声明：客户端必需 `baritoe`（Baritone API 1.11.2+），Bridge 在其后加载。Gradle 构建及 747 项 smoke 通过，已核对 jar 内元数据。加载器真机待验。

- 本次 `inventory.drop`：`./gradlew.bat build` 成功，**747 passed / 0 failed**，能力目录 33/33 且注册一致。协议 1.12 jar 已部署，用户已完成 DSH/客户端真机测试。
- 公开发布：当前快照已完成本机路径与身份信息脱敏；远程 `main` 已由无旧父提交的新历史覆盖，远程没有其它分支或标签引用旧历史。
- 本次 `movement_look` 触发范围修复：`./gradlew.bat build` 成功，**735 passed / 0 failed**；新回归覆盖配置与 Baritone 导航双重门槛。新 jar 已部署，源/目标 SHA-256 均为 `415fc9260e4e505f6945554b16e47ea8a90b247788634676a18961c86046a3d1`，旧 jar 备份在 `build/runtime-backup/20261103-224946/`；用户已确认真机行走不再被抢占。
- 核心事件基线 `./gradlew.bat build`：成功，**734 passed / 0 failed**；能力目录双向一致，协议 **1.11**。
- 14 类事件均有本地真机通知：门与模组方块状态、实体生成/消失/受伤/死亡、物品拾取、容器窗口和服务端槽位包、快捷栏、Baritone、连接和本地玩家 join/leave。
- 最终 jar 源/目标 SHA-256 均为 `e30a8f6b4683bef290c06aa510b76b623fab92f08a5452db750c3060583605e4`；修复前备份在 `build/runtime-backup/20261103-104834/`。
- 多人第二玩家的 join/leave 尚未验证，不用本地玩家结果冒充。公网断线重连与所有模组行为也未穷尽验证。
- 插件运行时代码未改，基线 `npm test` 223 passed / 0 failed。测试客户端正常关闭，无关 server 未操作。

## 当前下一步

1. 补物品开始/停止使用、批量感知和统一动作句柄，再进入只读脚本运行时和自带插件阶段。
2. 有第二个真实玩家时补远程 player_join/player_leave；保留协议 1.9 的 follow/damage 既有真机待验项。

## 历史：协议 1.9 follow 与 damage

- 协议 **1.7** 完成 Baritone API 重构；**1.8** 新增即时 damage；能力目录已修复双向一致性检查。
- 协议 **1.9** 新增 `baritone.follow`：UUID 或精确玩家名单目标，`follow_radius` 默认 **10**（1..64 可调），破坏和放置默认关闭。启动返回 `following/target_not_found/not_in_game`。
- `baritone.status` 新增 `follow.active/targets/radius` 和顶层 `following` 状态；`baritone.stop` 同时取消 follow 与其它移动。
- 插件 `sirius_follow` 每 2 秒检查目标丢失和卡住，停止并唤醒一次；状态查询瞬断不误报，新 walk/follow、stop、init 和销毁只取消不提醒。
- 规格与证据见 `docs/sessions/2026-11-02-follow-tool.md`。

## 历史验证与部署

- `./gradlew.bat build`：成功，**696 passed / 0 failed**，新增血量先到、来源后到、250ms 窗口、旧来源不复用和连续扣血回归。
- 插件 `npm test`：**223 passed / 0 failed**。
- 协议 1.9 已在实际实例生效。归因现在由 `LivingEntityMixin` 捕获来源，并由 `DamageContracts.Attributor` 等待最多 250ms 配对血量包与来源包；超时才报 unknown。
- 时序修复 jar 已部署，源/目标 SHA-256 均为 `58cffc07b9bdf268a4e1afa5c65ea94c9eba69d083b2fbf2d3574b18cf47a9b3`；上一版备份在 `build/runtime-backup/20261102-223912/`。
- 旧 1.7 桥和旧 Baritone API 备份在 `build/runtime-backup/20261102-221737/`，首次 Mixin 修复前 jar 备份在 `build/runtime-backup/20261102-222824/`。
- DSH 已是 0.2.1-rc.2，Soul 真实宿主 216/216 通过；时序修复版仍需重启 Minecraft 后验证玩家、怪物和弹射物来源。

## 下一步

1. 部署新 jar 并重启游戏与 DSH，确认协议 1.9、28 个能力和 `baritone.follow` 已注册。
2. 指定一个真实玩家调用 `sirius_follow`，确认默认 10 格、跟随移动和不破坏建筑；用自定义 `follow_radius` 复测。
3. 让目标离开实体追踪范围，确认停止并只提醒一次；制造不可达路线，确认 4.5 秒无进展后停止并提醒。
4. 回归 `sirius_baritone` 的 follow 状态、`sirius_stop`、新 walk/follow 取代和 init 接管；取消不应产生丢失提醒。
5. 继续完成 damage、周期维护和默认 damage 拉取的既有真机验收。
6. 真机结果补入本轮会话记录；当前各组改动尚未再次提交，提交前按逻辑单元拆分并只纳入相关文件。
