# 2026-11-03 会话：v2 核心事件源

## Plan + Spec（用户已确认范围与职责边界）

协议从 1.10 升为 **1.11**，不新增高层工具，能力目录仍为 32 个 RPC 工具。新增以下 14 类通知：`block_update`；`entity_spawn / entity_gone / entity_hurt / entity_death`；`item_pickup`；`player_join / player_leave`；`window_open / window_close / slot_update`；`held_item_changed`；`connection_state`；`baritone_state`。

**bridge 的职责是忠实反映客户端实际观察到的事件，连接侧负责筛选和处理。** 新事件不做距离过滤、数量裁剪、合并、冷却、默认降频或业务去重。`block_update` 原样反映每次成功的客户端方块状态变化。现有 `events.subscribe(types,min_level)` 是连接侧显式选择的过滤；未订阅的连接不接收通知。事件队列、背压、按位置/物品/实体过滤以及自动插件策略留给后续脚本运行时。

## 来源与证据

- `block_update`：客户端 `LevelChunk.setBlockState` 成功改变状态时发出；包含世界坐标、dimension、旧/新 registry id 与完整属性、`evidence:"client_block_change"`。初始区块加载等真实客户端状态变化同样属于原始事件，不由 bridge 猜测哪些重要。
- `entity_spawn / entity_gone`：客户端侧 `EntityJoinLevelEvent` / `EntityLeaveLevelEvent`，包含 entity id、UUID、type、name、position、dimension；gone 另带 `dead_or_dying`。
- `entity_hurt`：客户端 `LivingEntity.handleDamageEvent`，包含实体、damage_type、attacker/direct_entity、当前 health；此事件证明客户端受伤表现，不伪造客户端不可见的精确扣血量。`entity_death` 在实体离开且 `dead_or_dying=true` 的边界发出，保留 WeakHashMap 中最近一次伤害来源，证据为 `client_entity_leave`；真机证明 handleDamageEvent 结束时尚未标记死亡，不能在该处猜 death。
- `item_pickup`：`ClientboundTakeItemEntityPacket`，包含 item entity、collector entity id、amount 和可取得时的物品事实；不局限于本地玩家。
- `player_join / player_leave`：上述实体事件中实体为 Player 时分别另发，包含玩家事实，不根据聊天提示猜测。本地单人世界离开不触发实体 leave 时，由 `ClientPlayerNetworkEvent.LoggingOut` 补发 `player_leave`，证据为 `client_connection_event`。
- `window_open / window_close`：现有 ScreenEvent 中只有 `AbstractContainerScreen` 才另发窗口事件，带 container id 和 screen class；原 `gui_open/gui_close` 保持不变。
- `slot_update`：客户端收到 `ClientboundContainerSetSlotPacket` / `ClientboundContainerSetContentPacket` 后逐项发出，带 container id、state id/revision、packet 类型、菜单槽索引和物品事实。`evidence:"server_packet"` 只证明服务端当时发送的状态，不把包与某次点击强行关联为事务确认。
- `held_item_changed`：每客户端 tick 比较选中槽、物品 id/components 与数量；首次只建立基线，之后变化时发 before/after 和槽位。它表达 tick 边界的客户端观察。
- `connection_state`：`ClientPlayerNetworkEvent.LoggingIn/LoggingOut`，state 为 connected/disconnected，带可获得的本地玩家事实。
- `baritone_state`：复用真实 Baritone API 的 `statusResult`；PathEvent 和每 tick 状态比较触发，首次建立基线，之后状态 JSON 变化即原样发出，包含 `reason:"path_event"/"state_changed"`。

事件等级：entity_hurt、entity_death、connection_state 为 WARNING，其余本轮新事件为 INFO。等级只是连接侧过滤依据，不是 bridge 丢弃策略。

## 代码边界

- `CoreEventSources`：NeoForge 监听、主线程事实提取、tick 基线和统一推送。
- 新 Mixin：客户端区块状态、LivingEntity 扩展、容器/拾取数据包。所有游戏对象只在客户端主线程读取；物理服务端/integrated server 侧通过 `isClientSide` 排除。
- `CoreEventContracts`：纯 Gson/JDK 的状态 JSON、物品变化比较和 slot notification 形状，SmokeMain 回归。
- `BaritoneTools`：复用同一状态快照，不另写聊天解析或第二套状态词表。
- 同步 `Capabilities`、events schema、`BRIDGE.md`、PROGRESS 和 v2 目标文档。

## 验收

- 自动：14 类事件常量与等级、状态序列化、槽位包形状、手持物变化判断、事件类型/等级订阅、原 32 能力目录不变。
- `./gradlew.bat build`、两仓 `git diff --check`；部署前备份，复制后 SHA-256 一致，重启客户端。
- 真机使用独立 RPC 订阅并保存原始通知：门开关与方块变化；生成/移除实体、怪物受伤和死亡；拾取掉落物；本地玩家 join/leave 与连接状态；模组容器 open/transfer/close 及 server_packet slot 更新；切换快捷栏；Baritone goto/path/stop。
- 多人玩家 join/leave 若无第二个玩家则明确标为未验证，不用本地玩家重复事件冒充。
- 真机记录不宣称网络多实例、断线重连或所有模组都已穷尽验证。

## 收尾日志

### 自动验证与部署

- 首轮 `compileJava` 发现 1.21.1 拾取包方法为 `getItemId()`，按 javap 实际签名修正。
- 真机第一次启动发现 `LevelChunk.setBlockState` 第三参数是 boolean 而非 int；第二次发现批量槽位方法为 `handleContainerContent`。两处 Mixin 描述符修正后，客户端正常进入世界，hello 协议为 1.11。
- 最终 `./gradlew.bat build` 成功，**734 passed / 0 failed**。32 个 RPC 能力不变，14 类新事件常量、等级、方块状态、手持比较、slot evidence 和连接侧过滤边界均有回归。
- 修复前 jar 备份：`build/runtime-backup/20261103-104834/`。最终源/目标 SHA-256 均为 `e30a8f6b4683bef290c06aa510b76b623fab92f08a5452db750c3060583605e4`。

### 真机结果

- `block_update`：橡木门 open 双向变化与完整 state、模组金桶 open 状态均收到；同时收到自然藤蔓变化，证明无白名单过滤。
- `entity_spawn / entity_gone`：进入世界时收到批量原始实体通知，测试牛生成和死亡消失均收到。`item_pickup` 收到石头、皮革及本地 collector；相同实体可出现多个真实数据包，bridge 按原则不去重。
- `entity_hurt`：攻击牛收到 `minecraft:player_attack` 与本地玩家 attacker/direct_entity。`/kill` 收到 generic_kill。真机发现 handleDamageEvent 末尾 health 仍为 10 且未 dead，不能提前发 death；改为实体离开边界后收到 `entity_death{health:0,damage_type:minecraft:generic_kill,evidence:client_entity_leave}`，再收到 gone。
- `window_open/window_close`：StorageScreen container id 正确。原版箱子初始化收到 63 个 set_content 槽位及 carried=-1，含 granite、石头、泥土、模组物品，state_id/evidence 正确。快捷栏 0→1 收到 held_item_changed。
- `baritone_state`：PathEvent 和 API 快照持续到达，包含 goal/pathing/follow/settings 等真实字段。
- `connection_state`：退出世界收到 disconnected，重进收到 connected。单人世界第一次实体 leave 没有 player_leave，补 LoggingOut 后复测同时收到 disconnected 与本地 player_leave，重进收到 player_join。
- 因此 14 类均有真实通知证据。**仅验证本地玩家 join/leave；没有第二个真实玩家，远程多人 player_join/player_leave 仍待验。**未宣称公网断线重连或所有模组行为穷尽验证。
- 测试脚本、截图和构建临时日志已清理；客户端正常关闭，无关 server 未操作。插件运行时代码未修改。
