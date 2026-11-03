# 2026-11-02 会话：能力表与工具注册一致性修复

## Plan + Spec（用户已确认修复）

现状：`schema/index.json` 广告 `events.watch`，但 `BridgeServer` 未注册该处理器，`BRIDGE.md` 又将它列为未来工作。调用方会根据能力表发起必然失败的请求。

方案：协议 1.8 中撤下这个从未实现的能力广告，保留未来 schema 文件但不列入运行时目录；实际广告工具数从 28 修正为 27。增加能力与注册表的纯一致性校验，服务器构造时发现不一致会拒绝启动；协议内置的 `capabilities/list` 不参与普通工具目录对账。smoke test 固定当前目录不再包含 `events.watch`。

兼容性：这是修正错误能力广告，不新增或改变已实现工具的字段与行为，协议版本保持 1.8。旧调用方不应再把 `events.watch` 当作可用能力；调用它本来就只会得到 `-32601`。

验收：27 个广告工具均有注册处理器，普通注册工具也均有 schema；完整 `./gradlew.bat build` 通过；`BRIDGE.md`、进度与测试数量同步。

## 收尾日志

### 文件改动

- `schema/index.json`：移除 `events.watch` 的广告及文件索引，保留草案文件本身。
- `Capabilities.java`：新增 `registryProblems`，双向检查广告与普通工具注册，排除内置 `capabilities/list`。
- `BridgeServer.java`：构造完成后执行一致性检查，发现错位立即拒绝启动。
- `SmokeMain.java`：固定 27 个工具、无 `events.watch`、完整注册集合及双向错位检测。
- `BRIDGE.md`：同步协议 1.8、27 个工具、启动校验和未来 `events.watch` 边界。

### 自动验证

- `./gradlew.bat build`：成功，**683 passed / 0 failed**。
- 能力目录 27/27 schema 可加载，广告与普通注册处理器一致；内置 `capabilities/list` 边界已纳入回归。

### 部署与未完成

- 本轮构建出的新 jar 尚未复制到游戏实例；部署后需重启 Minecraft。
- 启动校验和真实 `capabilities/list` 响应仍需真机确认；此前 damage 真机验收项保持不变。
