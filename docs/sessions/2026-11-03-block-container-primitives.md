# 2026-11-03 会话：block.get 与容器事务原语

## Plan + Spec（用户已确认）

v2 开发目标分为“完善接口底层 → 编写自带插件”两个板块。本轮完成脚本底层优先项前两条：

1. `block.get`：读取指定坐标，返回 registry id、完整方块状态和加载状态。
2. `container.clickSlot` / `container.transfer` / `container.close`：通过真实 `AbstractContainerMenu` 和 `MultiPlayerGameMode.handleInventoryMouseClick` 操作菜单，不再依赖 GUI 缩放、窗口焦点和物理鼠标。

本轮只增加 bridge 原语，不新增写死的 Sirius 高层模型工具，不实现自动合成或自动整理背包。

## 协议与接口

协议从 1.9 升为 **1.10**，能力目录由 28 增为 32。新增四个 schema 并注册实现。

### `block.get({x,y,z})`

坐标必须是世界整数范围。游戏外返回 `{"in_game":false}`；游戏内返回：

- `x/y/z`。
- `status`: `ok` / `unloaded` / `out_of_world`。
- `loaded`: 该坐标所在区块当前是否可用。
- `block`: registry id；未加载或越界时为 null，真实空气为 `minecraft:air`（或实际空气变种）。
- `state`: 完整属性名到字符串值的对象；未加载或越界时为空对象。
- `min_y` / `max_y`: 世界建筑上下界，便于调用方区分永久越界和暂时未加载。

### `container.clickSlot({slot,button?,mode?})`

- `slot` 是当前菜单的 `Slot.index`，与 `getGuiState.slots[].index` 一致，不是背包 `container_slot`。
- `button` 默认 0；`mode` 支持 pickup / quick_move / swap / clone / throw / quick_craft / pickup_all。
- 仅在当前 `AbstractContainerScreen` 与其菜单仍一致时执行；没有容器、槽位越界和动态状态问题返回明确 result。
- 返回 container id、界面、点击参数、鼠标携带物前后值和实际变化槽位。

### `container.transfer({from_slot,to_slot,count?})`

- 默认移动来源整组；count 可指定 1..128。
- 拒绝同槽、空来源、非空鼠标携带物、物品不兼容和目标空间不足，避免先动再发现不能放。
- 整组使用左键 pickup；部分转移用右键逐个放置，再把剩余携带物放回来源。
- 返回请求数量、目标槽实际增量、变化槽位和携带物终态；异常终态报 unknown，不把“点击已发”当成全部成功。

### `container.close({})`

仅关闭当前容器界面。没有容器时返回 no_container；成功时返回 closed 和已关闭界面类名。

### 证据口径

所有容器动作在客户端主线程执行并立即回读槽位。`handleInventoryMouseClick` 会先进行客户端预测，因此响应使用 `evidence:"client_snapshot"`，只证明客户端动作层已执行及当前快照，**不宣称服务端已确认**。后续可用 slot_update 或服务端回包增强确认，不在本轮伪造 confirmation。

## 代码边界

- `BlockContracts` / `ContainerContracts`：纯 Gson + JDK 参数校验、状态分类、变化 diff 和结果形状，SmokeMain 覆盖。
- `BlockTools` / `ContainerTools`：Minecraft 主线程读取与真实动作调用。
- 复用 `GuiContracts.SlotFact` 与 `GuiTools.collectSlots` 的槽位角色和 registry 规则。
- 容器动作共享 `InputGuard`，逐项审计；block.get 为只读感知，不消耗动作限额。

## 验收

- 参数缺项、非整数、越界、未知点击模式。
- block 的真实空气、带属性方块、未加载、上下界外、游戏外。
- click 的槽位变化 diff 与携带物变化。
- transfer 的整组、部分、目标合并、空来源、不兼容、空间不足、携带物非空、同槽和意外终态。
- close 的有容器与无容器。
- capabilities/list 与注册实现双向一致，schema 同步进 jar。
- `./gradlew.bat build`、`git diff --check`。
- 真机另验：箱子整组/部分转移、不同物品拒绝、关闭界面，以及门和作物的 block state。构建通过不代替真机确认。

## 收尾日志

### 文件改动

- 新增 `BlockContracts.java` / `BlockTools.java` 与 `schema/tools/block.get.json`：单格属性、真实空气、未加载和越界分类。
- 新增 `ContainerContracts.java` / `ContainerTools.java` 与三个容器 schema：真实菜单点击、整组/部分转移、关闭、携带物与槽位 diff。
- `GuiTools.collectSlots` 改为包内复用，保持菜单槽、角色和物品 registry 口径一致。
- `PerceptionTools` 注册 block.get，`BridgeServer` 注册容器动作并接入 InputGuard。
- `Capabilities`、`schema/index.json` 和 `BRIDGE.md` 升为协议 1.10、32 个能力。
- `SmokeMain` 增加新接口与目录回归；`docs/PROGRESS.md` 同步当前验证和真机待验。
- 插件仓 `docs/sessions/2026-11-02-v2-script-runtime.md` 已写明“完善接口底层 → 编写自带插件”两大板块。

### 自动验证

- 首轮 `./gradlew.bat build` 在测试编译发现 closeParams 的 void lambda 不符合 expectInvalid 返回值要求；修正测试后重跑，不涉及实现变更。
- 收尾审查把转移前拒绝规则提取为纯 `transferRefusal`，补同槽、携带物、空来源、数量、菜单规则、兼容性和容量回归；目标增量同时核对物品 id，避免异常窗口仅凭数量误报成功。
- 最终 `./gradlew.bat build`：成功，**727 passed / 0 failed**。
- 最终两仓 `git diff --check` 均通过；jar 内已核对包含 index 与四个新 schema。

### 部署与真机验收

- 最终 jar 已部署到 `1.21.1-NeoForge_21.1.265`，源/目标 SHA-256 均为 `3291ac2638cf716f9159f9b8c1b2bef6acacc8fdcf1865aa0166e4e4f3515759`；修复前版本备份在 `build/runtime-backup/20261103-095327/`。
- 无关的 Minecraft server 进程未再操作。PCL 的 `quickPlaySingleplayer` 世界名出现编码问题后，最终测试使用同一客户端正常进入标题页并选择世界，不使用 quickPlay。
- 能力目录实测 32 个，包含 block.get 和三个容器接口。
- block.get 实测：`oak_door{facing:north,open:true,half:lower}`、全部属性；`wheat{age:7}`；空气为 `minecraft:air`；Y=1000 为 `out_of_world`。
- 远坐标第一次被 `ClientLevel.hasChunkAt` 错报为 `ok/void_air`。改为 `getChunkAt(pos) instanceof EmptyLevelChunk` 后复测返回 `unloaded`。曾尝试 ChunkStatus，但 1.21.1 的 LevelChunk 没有该 API，编译后已及时改回正确路线。
- 容器实测：整组 64 石头与部分泥土实际入箱，失焦状态下也能执行；直接 quick_move 能搬动整组，close 返回 closed，无容器返回 no_container，花岗岩对石头返回 incompatible，额外 1 石头对满组返回 not_enough_space。
- 首次转移结果曾因来源 ItemStack 被就地修改而报 moved=0/unknown，同时整组分支在首次点击后读到 sourceCount=0。改为点击前保存 sourceCount 和物品 id 后，复测整组 `moved=64/requested=64`、部分 `moved=3/requested=3`，均为 transferred。
- 测试客户端已正常关闭，无关 server 仍在运行。临时 RPC 脚本、Gradle runClient 目录和本轮 GUI 证据截图已删除。测试方块保留在 `(547,64,-129/-128/-127)` 供后续复测。
- 容器证据本轮为客户端即时快照，服务端确认事件属于后续核心事件工作。

## 模组容器追加真机测试（用户布置场景）

同一创造模式客户端、同一协议 1.10 jar，玩家位于 `(544.58,64,-127.86)`。仅使用背包原有 `container_slot=31` 的 16 个花岗岩；每次转移后等待 600ms 再读，最后全部归回原槽，未修改其他物品。未操作无关 Minecraft server。

- `sophisticatedstorage:gold_barrel` / `StorageScreen`：117 个菜单槽，其中 81 个 storage、27 player、9 hotbar。花岗岩槽为 `Slot.index=103`。整组 `16/16`、部分 `5/5` 均 transferred，归位后等待回读原槽仍为 16。槽 63..80 的 GUI Y=-97，是模组暂未显示的槽，但结构仍完整枚举；调用方选择可见目标时可结合坐标。
- `sophisticatedbackpacks:netherite_backpack` / `BackpackScreen`：156 槽，其中 120 storage、27 player、9 hotbar；花岗岩 `Slot.index=142`。整组 `16/16` 与部分 `5/5` 均 transferred；600ms 后分别为容器槽 0=16、槽 0=11+槽 1=5，无回滚；恢复原槽 16。槽 84..119 同样为模组隐藏槽。
- `farmersdelight:cooking_pot` / `CookingPotScreen`：45 槽，其中 9 container、27 player、9 hotbar；花岗岩索引与背包索引都为 31。0→1 输入槽整组和部分转移通过，600ms 回读无回滚；对槽 6（成品输出）与槽 8（燃料）放入花岗岩均 `slot_forbidden/moved=0/changed_slots=[]`，最后归位。
- `netmusic:cd_burner` / `CDBurnerMenuScreen`：38 槽，其中 2 container、9 hotbar、27 player；花岗岩 `Slot.index=33`。对容器槽 0 和槽 1 都正确返回 `slot_forbidden/moved=0`，没有先执行再回滚。
- 刻录机补充实测 `getGuiState.widgets`：标准 `EditBox` 返回 `message:"Music ID Box"`、当前 `text:""`、坐标 `(134,53)`、尺寸 `132x16`、`visible/active:true`；另有 `message:"只读"` 的 Checkbox 和 `message:"制作唱片"` 的 Button。一次 `getGuiState` 可同时读取槽位、输入框当前文本、复选框与按钮；`active/visible` 只描述界面状态，不代表模组业务规则允许提交。
- 模组槽位的通用 `role:"container"` 不等于输入/输出/燃料语义。目前没有独立的槽位接受物查询；`container.transfer` 在执行前调用真实菜单的 `mayPickup/mayPlace` 与兼容性检查，拒绝时返回 `slot_forbidden/moved=0`，不移动物品。因此 agent 可安全试探，但不能仅凭 `getGuiState` 预知全部模组业务规则。非标准 `AbstractContainerScreen` 不保证提供槽位。
- `netmusic:music_player`：首次右键 `used/none_visible`，后续 `rejected`，两种情况下 `getGuiState` 都是 `screen_open:false`。它不是本轮可验证的标准 `AbstractContainerScreen`，没有槽位通过结论；有唱片等有效内容时的交互仍需另测。
- 以上各标准容器均 `container.close=closed`。延迟回读稳定只能证明整合服同步后客户端快照未回滚，不能替代未来的服务端 slot_update 确认事件。
- 本次仅真机测试与日志，无 Java、schema 或插件运行时代码变更，不重复运行构建。临时 RPC/探针脚本及截图已列入收尾清理项。
