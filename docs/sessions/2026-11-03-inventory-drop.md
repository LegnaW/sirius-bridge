# inventory.drop：指定槽位与数量的动作层丢弃

## 根因与方案

DSH 需要 `core.drop {slot,count?}`，但现有 `inventory.select` 只能选手持槽，`container.clickSlot` 又要求已打开容器界面。新增 `inventory.drop`，直接对 `player.inventoryMenu` 使用原版 `handleInventoryMouseClick(..., ClickType.THROW, ...)`，不打开 GUI、不注入 Q 键。

协议升级为 1.12，新增第 33 个 RPC 工具。`slot` 沿用 inventory 的 0..40 容器下标，通过 `menuSlotForPlayerSlot` 转为菜单槽；`count` 省略时丢整组，指定 1..64 时执行对应次数的单件丢弃。空槽、数量超过堆叠、不可拿取、手上已有 carried stack、其它容器占用玩家菜单时均明确拒绝。

实现时补齐了既有映射函数遗漏的盔甲 36..39 → 菜单 5..8、副手 40 → 菜单 45；主背包保持同号，快捷栏仍映射 36..44，避免丢弃落到其它物品槽。

## 结果与证据

请求前复制槽位堆叠，动作后在 WebSocket 线程有界等待并回读客户端槽位。减少数量等于请求值才返回 `dropped`；减少但不足返回 `partial`；无减少或物品身份异常返回 `unknown`。响应包含 item/requested/dropped/before/remaining 和 `evidence=client_snapshot`，不把发包本身冒充服务端确认。

## 验收

- Smoke：参数边界、快捷栏菜单映射、结果形状、能力目录与注册双向一致。
- `./gradlew.bat build` 和 `git diff --check`。最终构建 **747 passed / 0 failed**，能力目录 33/33 且与注册双向一致。
- 真机待验：快捷栏与主背包分别丢 1 个、指定多个、整组；空槽、超量和其它容器打开时不动作；核对地面实体与槽位余量。
