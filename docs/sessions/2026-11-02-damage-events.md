# 2026-11-02 会话：即时伤害事件与独立仓库规范

> 本文是实施后的规范补记。伤害方案先经对话分析并由用户确认“先按照这个思路做一下”，随后实施；独立仓库规范由用户随后要求补建。本文不把补记伪称为事前 spec。

## Plan + Spec（已确认方案）

### 背景

旧 `health_low` 只在生命值不高于 6 时触发，且危险状态约每秒采样；Observer 也只对掉血至少 2 点做延迟报告。agent 等到低血提醒后再思考，往往已经无法及时应对。

目标是任何实际生命值下降都尽快唤醒，附上伤害来源和近期聊天；唤醒默认冷却 30 秒，避免连续受伤反复消耗模型调用。玩家在没有聊天响应时也可以通过一次实际扣血唤醒 agent。

### 分工

- 桥端忠实发出所有观察到的实际扣血事件，不做 30 秒唤醒冷却，原始事件历史因此不会缺失。
- 插件订阅 `damage`，首次伤害强唤醒，之后 30 秒内只抑制唤醒；近期聊天复用现有 `deliverWake`。
- 保留 `health_low` 和 `death`。Observer 不再重复报告掉血。
- 只扣吸收、生命值不变或回血时不发 `damage`。

### 来源采集

不能只依赖 `LivingDamageEvent.Post`：远程服务器在服务端结算伤害，纯客户端模组不能假设收到该事件。

Minecraft 1.21.1 的 `ClientPacketListener.handleDamageEvent` 会从 `ClientboundDamageEventPacket` 还原 `DamageSource`，而 `LivingEntity.handleDamageEvent` 会把它保存到 `lastDamageSource`。因此无需 Mixin：

```java
float health = player.getHealth();
DamageSource source = player.getLastDamageSource();
DamageContracts.SourceFact fresh = source != null && source != previousDamageSource
        ? describeSource(source) : null;
JsonObject data = DamageContracts.damageData(previousHealth, health, fresh);
```

每 tick 比较生命值；只有来源对象较上一 tick 更新时才用于归因，避免把旧的来源猜给一次无来源的生命值变化。

### 事件契约

```json
{
  "health_before": 20.0,
  "health_after": 17.0,
  "amount": 3.0,
  "damage_type": "minecraft:mob_attack",
  "attacker": {"name":"Zombie","type":"minecraft:zombie","uuid":"..."},
  "direct_entity": {"name":"Zombie","type":"minecraft:zombie","uuid":"..."}
}
```

`attacker` 和 `direct_entity` 可为 `null`。无法取得来源时 `damage_type` 为 `unknown`，不根据附近怪物猜测。事件级别为 CRITICAL，协议从 1.7 升至 1.8。

### 验收

- 基线、生命不变和回血不生成事件；满血扣 0.5 也生成事件。
- 伤害类型、攻击者、直接伤害实体和扣减量完整保留；未知来源明确标记。
- 插件在满血少量扣血时强唤醒，30 秒内不重复，冷却边界后再次唤醒。
- 近期聊天沿用既有唤醒链路；低血量、死亡和事件拉取行为不回归。
- 真机使用生存模式验证玩家、怪物和环境伤害；创造模式不能替代扣血验证。

## 收尾日志

### 文件改动

| 文件 | 改动 |
|---|---|
| `src/main/java/io/sirius/bridge/DamageContracts.java` | 新增纯生命值变化判断和 damage JSON 契约 |
| `src/main/java/io/sirius/bridge/EventPusher.java` | 每 tick 采样扣血，配对新鲜 DamageSource 并推送 CRITICAL damage |
| `src/main/java/io/sirius/bridge/Capabilities.java` | 协议升至 1.8 |
| `schema/index.json` | 发布协议版本同步至 1.8 |
| `src/main/java/io/sirius/bridge/SiriusBridge.java` | 更新采样与事件通道说明 |
| `src/test/java/io/sirius/bridge/SmokeMain.java` | 新增扣血、来源和协议版本回归断言 |
| `BRIDGE.md` | 增加 damage 字段、来源语义、冷却分工和协议状态 |
| `AGENTS.md` | 新建独立仓库开发规范 |
| `docs/PROGRESS.md` | 新建跨会话状态锚点 |
| `docs/sessions/2026-11-02-damage-events.md` | 补录本方案与收尾证据 |

插件配套改动位于 `sirius-for-whale`：`EVENT_KINDS` 订阅 damage；Watchdog 格式化来源并执行 `damageCooldownMs=30000`；移除 Observer 掉血报告；README 与测试同步。

### 自动验证

- 桥端 `./gradlew.bat build`：成功，**680 passed / 0 failed**。
- 插件 `npm test`：**212 passed / 0 failed**。
- 两仓库 `git diff --check`：通过。

### 部署

- 构建产物：`build/libs/sirius_bridge-0.1.0.jar`。
- SHA-256：`7b32405d135f2929832bf8de760456288ac759d23299c2f3e62ec0bdd10ef357`。
- 已备份旧 jar 并复制到 `1.21.1-Sirius/mods/sirius_bridge-0.1.0.jar`，源/目标哈希一致。

### 未完成

- 游戏和 DSH 尚未重启，damage 的真机来源、30 秒冷却和近期聊天仍待验收。
- 本轮及此前 Baritone API 重构尚未再次提交；用户未要求提交，不在本轮擅自处理。
