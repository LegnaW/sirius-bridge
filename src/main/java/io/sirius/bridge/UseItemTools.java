package io.sirius.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The M5.0 v1.6 {@code useItem} tool: use whatever the player is HOLDING, with
 * no target block or entity - eating, potions, fishing rod, ender pearl,
 * shears, buckets, boats. The contract half (params, pacing, verdict
 * vocabulary) is {@link UseItemContracts}; this class owns the game access.
 *
 * <p><b>Lifecycle of one call</b> (all game access on the client main thread):
 * <ol>
 *   <li><b>Pre-checks</b> (start task): in-world; {@code input_enabled};
 *       permission tier (screen-aware); a screen already open -> {@code
 *       gui_open} (a click would land in the GUI, the dig/place/use rule).</li>
 *   <li><b>Press</b>: {@code gameMode.useItem} advanced one per client tick by
 *       {@link #onClientTick()} so the render thread never blocks.</li>
 *   <li><b>Hold</b> (v1.6.2, 2026-11-01 真机 bug): a press that puts the player
 *       into {@code isUsingItem()} has only STARTED the item (food/potion return
 *       CONSUME immediately; the real -1 happens after the server counts
 *       {@code getUseDuration()} ticks = 32 ticks/1.6 s). So the pump then HOLDS:
 *       budget {@code getUseDuration() + 10} ticks, no further press while
 *       holding, and {@code releaseUsingItem()} if the budget runs out - one
 *       hold counts as one use of {@code times}.</li>
 *   <li><b>Evidence</b>: the {@link InteractionResult} of each press, the held
 *       stack's count ({@code item_before}/{@code item_after}), the hunger level
 *       ({@code food_before}/{@code food_after}), saturation and the helmet
 *       slot's item id - see {@link UseItemContracts#RESULT_INCOMPLETE}: only a
 *       real change is {@code used}, an accepted-but-nothing-changed press is
 *       {@code incomplete}.</li>
 *   <li><b>Budget</b>: {@code timeout_ms} caps the whole call, EXCEPT while
 *       holding: a hold raises the effective deadline to
 *       {@link UseItemContracts#holdAwareTimeoutMs} (hold budget + 500 ms), so a
 *       32-tick bread bar is never cut short by the 2000 ms default. The waiter
 *       answers {@code not_using} when no tick ran within the latch budget.</li>
 * </ol>
 *
 * <p><b>Guard rails</b>: exactly the {@code use}/{@code place} preamble - one
 * rate token per call, {@code input_enabled} > permission > rate, and one
 * {@code INPUT} audit line with the verdict.
 *
 * <p><b>Concurrency</b>: one active call at a time (class-level slot). A new
 * call supersedes the old one, whose waiter is released with
 * {@code not_using}.
 */
final class UseItemTools {

    private UseItemTools() {
    }

    /** Registers the tool; called once from the BridgeServer constructor. */
    static void registerAll(ToolRegistry tools, InputGuard guard) {
        tools.register("useItem", (ctx, params) -> useItem(ctx, params, guard));
    }

    // ------------------------------------------------------------------ the tool call

    /** What the start task decided (main-thread outcome; the shell translates). */
    private record StartOutcome(String kind, String detail, UseItemContracts.UseSnapshot before, ActiveUse use) {
        static StartOutcome of(String kind) {
            return new StartOutcome(kind, null, UseItemContracts.UseSnapshot.ofCount(0), null);
        }
    }

    private static final String STARTED = "started";
    private static final String NOT_IN_GAME = UseItemContracts.RESULT_NOT_IN_GAME;
    private static final String DENIED = "denied";

    private static JsonObject useItem(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        final UseItemContracts.UseItemParams p;
        try {
            p = UseItemContracts.useItemParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        String summary = "method=useItem hand=" + p.hand().wireName() + " times=" + p.times()
                + " interval_ms=" + p.intervalMs();
        if (!guard.enabled()) {
            ctx.audit("INPUT", summary + " result=input_disabled");
            return Json.errorResponse(ctx.id(), Json.INPUT_DISABLED, InputContracts.inputDisabledMessage(), null);
        }
        if (PermissionContracts.deniedRegardlessOfScreen(guard.tier(), PermissionContracts.Action.INPUT)) {
            ctx.audit("INPUT_DENIED", "reason=permission tier=" + guard.tier().configName() + " " + summary);
            return Json.errorResponse(ctx.id(), Json.PERMISSION_DENIED,
                    PermissionContracts.deniedMessage(guard.tier(), false, PermissionContracts.Action.INPUT), null);
        }
        if (!guard.tryAcquire()) {
            ctx.audit("INPUT", summary + " result=rate_limited");
            return Json.errorResponse(ctx.id(), Json.RATE_LIMITED,
                    InputContracts.rateLimitedMessage(guard.limitPerSecond()), null);
        }

        final long startedMs = System.currentTimeMillis();
        final StartOutcome start = PerceptionTools.callOnMainThread(ctx, () -> startUse(p, guard));
        switch (start.kind()) {
            case DENIED:
                ctx.audit("INPUT_DENIED", "reason=permission tier=" + guard.tier().configName()
                        + " " + summary + " screen=" + start.detail());
                return Json.errorResponse(ctx.id(), Json.PERMISSION_DENIED,
                        PermissionContracts.deniedMessage(guard.tier(), Boolean.parseBoolean(start.detail()),
                                PermissionContracts.Action.INPUT), null);
            case NOT_IN_GAME:
                ctx.audit("INPUT", summary + " result=not_in_game");
                return Json.okResponse(ctx.id(), ToolContracts.notInGame());
            case UseItemContracts.RESULT_GUI_OPEN:
                ctx.audit("INPUT", summary + " result=gui_open screen=" + start.detail());
                return Json.okResponse(ctx.id(), UseItemContracts.useItemResult(p,
                        UseItemContracts.RESULT_GUI_OPEN, 0, 0, null, start.detail(),
                        start.before().itemCount(), start.before().itemCount(),
                        start.before().food(), start.before().food(),
                        System.currentTimeMillis() - startedMs,
                        "a screen is open - close it (or use getGuiState) before using an item"));
            case STARTED:
                break;
            default:
                break;
        }

        ActiveUse active = start.use();
        // The monitor is tick-driven (50 ms); the latch budget must cover BOTH the
        // press schedule and the hold (a food's progress bar is 32 ticks ≈ 1.6 s,
        // and a hold raises the call deadline to hold-aware value). Worst case is
        // "press schedule + hold budget", each bounded by timeout_ms, hence 2x.
        // On a latch timeout (iconified window -> no ticks) answer not_using
        // WITHOUT touching game state from this thread: the monitor's own timeout
        // check releases the latch on the next tick that runs.
        long patienceMs = p.timeoutMs() * 2L + 2_000L;
        boolean completed = active.done.await(patienceMs, TimeUnit.MILLISECONDS);
        String result = completed && active.result != null ? active.result : UseItemContracts.RESULT_NOT_USING;
        long elapsedMs = completed ? active.elapsedMs() : System.currentTimeMillis() - startedMs;
        int itemAfter = active.itemAfter != null ? active.itemAfter : active.monitor.itemBefore();
        int foodBefore = active.monitor.foodBefore();
        int foodAfter = active.foodAfter != null ? active.foodAfter : foodBefore;
        // incomplete 必须自带人话：模型看到"没吃成"要知道是"没按住/饱了/被拒"。
        String note = completed ? active.note
                : "monitor did not finish (window iconified? self-cleans next tick)";
        if (note == null && UseItemContracts.RESULT_INCOMPLETE.equals(result)) {
            note = UseItemContracts.INCOMPLETE_REASON;
        }
        JsonObject response = UseItemContracts.useItemResult(p, result, active.monitor.presses(),
                active.holdTicks, active.monitor.lastInteraction(), active.screenClass,
                active.monitor.itemBefore(), itemAfter, foodBefore, foodAfter, elapsedMs, note);
        ctx.audit("INPUT", summary + " timeout_ms=" + p.timeoutMs()
                + " pressed=" + active.monitor.presses()
                + " hold_ticks=" + active.holdTicks
                + (active.monitor.lastInteraction() != null ? " interaction=" + active.monitor.lastInteraction() : "")
                + (active.screenClass != null ? " screen=" + active.screenClass : "")
                + " item=" + active.monitor.itemBefore() + "->" + itemAfter
                + " food=" + foodBefore + "->" + foodAfter
                + " result=" + result
                + " elapsed_ms=" + elapsedMs);
        return Json.okResponse(ctx.id(), response);
    }

    // ------------------------------------------------------------------ start (main thread)

    /** Pre-checks + monitor installation. MUST run on the client main thread. */
    private static StartOutcome startUse(UseItemContracts.UseItemParams p, InputGuard guard) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null) {
            return StartOutcome.of(NOT_IN_GAME);
        }
        UseItemContracts.UseSnapshot before = snapshot(player, p.hand());
        boolean screenOpen = mc.screen != null;
        if (!PermissionContracts.allows(guard.tier(), screenOpen, PermissionContracts.Action.INPUT)) {
            return new StartOutcome(DENIED, String.valueOf(screenOpen), before, null);
        }
        if (screenOpen) {
            return new StartOutcome(UseItemContracts.RESULT_GUI_OPEN,
                    mc.screen.getClass().getSimpleName(), before, null);
        }
        ActiveUse use = installMonitor(p, before);
        return new StartOutcome(STARTED, null, before, use);
    }

    /**
     * 开始使用前的四项证据快照：手持数量、饱食等级、饱和度（取整成 int，
     * 结果体里不放浮点）、盔甲槽（背包 39 = 头盔）的物品 id。
     */
    private static UseItemContracts.UseSnapshot snapshot(LocalPlayer player, UseContracts.Hand hand) {
        ItemStack stack = player.getItemInHand(interactionHand(hand));
        return new UseItemContracts.UseSnapshot(
                stack.isEmpty() ? 0 : stack.getCount(),
                player.getFoodData().getFoodLevel(),
                Math.round(player.getFoodData().getSaturationLevel()),
                armorId(player));
    }

    /**
     * 盔甲槽（背包 39 = 头盔）的物品 id。空槽给<b>空串</b>（"空 → 戴上头盔"本身
     * 就是要有证据的变化），注册名取不到时也退化成空串（宁可漏一次装备证据，
     * 也不要把"取不到名字"误报成"换了装备"）。
     */
    private static String armorId(LocalPlayer player) {
        ItemStack stack = player.getInventory().getItem(InventoryContracts.ARMOR_END);
        if (stack.isEmpty()) {
            return "";
        }
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null ? key.toString() : "";
    }

    private static InteractionHand interactionHand(UseContracts.Hand hand) {
        return hand == UseContracts.Hand.OFF ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    // ------------------------------------------------------------------ monitor + tick pump

    /** The single active call; main-thread confined. */
    private static final class ActiveUse {
        final UseItemContracts.UseItemParams params;
        final UseItemContracts.UseItemMonitor monitor;
        final long startedMs = System.currentTimeMillis();
        final CountDownLatch done = new CountDownLatch(1);
        /** 正在按住进度条（true 时本 tick 不发下一次 press）。 */
        boolean holding;
        /** 剩余按住预算（tick）：{@code getUseDuration() + 10}，长使用物品再夹一次调用预算。 */
        int holdBudget;
        /** 实际按住过多少 tick（多次使用累加，结果体的 {@code hold_ticks}）。 */
        int holdTicks;
        volatile String result;
        volatile String screenClass;
        volatile String note;
        volatile Integer itemAfter;
        volatile Integer foodAfter;
        volatile Integer saturationAfter;
        volatile String armorAfter;

        ActiveUse(UseItemContracts.UseItemParams params, UseItemContracts.UseSnapshot before) {
            this.params = params;
            this.monitor = new UseItemContracts.UseItemMonitor(params, before);
        }

        long elapsedMs() {
            return System.currentTimeMillis() - startedMs;
        }

        void complete(String result, String screenClass, String note) {
            this.result = result;
            this.screenClass = screenClass;
            this.note = note;
            done.countDown();
        }

        /** 本 tick 观察到的证据，装成契约层要的 TickView。 */
        UseItemContracts.TickView view(boolean inGame, boolean screenOpen) {
            return new UseItemContracts.TickView(inGame, screenOpen, elapsedMs(),
                    itemAfter != null ? itemAfter : monitor.itemBefore(),
                    foodAfter != null ? foodAfter : UseItemContracts.UNKNOWN,
                    saturationAfter != null ? saturationAfter : UseItemContracts.UNKNOWN,
                    armorAfter);
        }
    }

    private static ActiveUse active;

    /** Installs a new call (superseding any active one) and returns its handle. */
    private static ActiveUse installMonitor(UseItemContracts.UseItemParams p,
                                            UseItemContracts.UseSnapshot before) {
        ActiveUse previous = active;
        if (previous != null) {
            active = null;
            previous.complete(UseItemContracts.RESULT_NOT_USING, null, "superseded by another useItem");
        }
        ActiveUse use = new ActiveUse(p, before);
        active = use;
        return use;
    }

    private static void finishActive(ActiveUse use, String result, String screenClass, String note) {
        if (active == use) {
            active = null;
        }
        use.complete(result, screenClass, note);
    }

    /**
     * Per-tick pump (from {@link SiriusBridge#onClientTick}): one press when
     * the schedule allows - or, while an item is being held down, just advance
     * the hold - then the monitor's verdict. Main thread only; a no-op when idle.
     */
    static void onClientTick() {
        ActiveUse use = active;
        if (use == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null) {
            finishActive(use, UseItemContracts.RESULT_NOT_IN_GAME, null, "left the world mid-use");
            return;
        }
        // 证据每 tick 都重读一次（数量/饱食/饱和度/盔甲槽），判定只认它。
        observe(player, use);
        if (use.holding) {
            if (advanceHold(mc, player, use)) {
                // 仍在按住：本 tick 不发 press，也不让 monitor 结算——
                // 一次 hold 是"一次完整使用"，结算要等进度条走完。
                return;
            }
            // 刚结束按住：这一次使用算完成，落到下面正常结算。
        }
        UseItemContracts.Verdict verdict = use.monitor.tick(use.view(true, mc.screen != null));
        if (verdict.action() == UseItemContracts.Action.PRESS) {
            press(mc, player, use);
            // Re-read right after the press: the host may have eaten the item or
            // opened a screen in this very tick.
            observe(player, use);
            if (beginHold(player, use)) {
                return; // 按下只是"开始使用"，真正扣物品要等进度条走完
            }
            verdict = use.monitor.terminal(use.view(true, mc.screen != null));
            if (verdict == null) {
                return;
            }
        }
        if (verdict.terminal()) {
            String screenClass = UseItemContracts.RESULT_SCREEN_OPENED.equals(verdict.result()) && mc.screen != null
                    ? mc.screen.getClass().getSimpleName()
                    : null;
            finishActive(use, verdict.result(), screenClass, null);
        }
    }

    /**
     * 按下之后检查是否进入"按住使用"状态。为真则记下按住预算并返回 true。
     *
     * <p>预算 = {@code max(1, getUseDuration()) + HOLD_SLACK_TICKS}（食物 32 tick ≈ 1.6 s）。
     * <b>再夹一层调用预算</b>：弓 72000 tick、望远镜 1200 tick 这类长使用物品的
     * duration 远超 {@code timeout_ms}，不夹的话一次 useItem 会把客户端按住几十分钟
     * （调用方早就超时返回，物品还举在手里）。夹住不改变语义，只是让"预算用尽 →
     * releaseUsingItem"这条路同时守住调用预算。
     *
     * <p><b>同时抬高本次调用的截止时间</b>（{@link UseItemContracts#holdAwareTimeoutMs}）：
     * 光食物就要 1.6 s+余量，比默认 {@code timeout_ms}=2000 还长，"按住到走完"
     * 不能因为调用方的默认预算而被判成 {@code timeout}——那只是把假成功换成假超时。
     */
    private static boolean beginHold(LocalPlayer player, ActiveUse use) {
        if (!player.isUsingItem()) {
            return false;
        }
        ItemStack held = player.getUseItem();
        int duration = Math.max(1, held.isEmpty() ? 1 : held.getUseDuration(player))
                + UseItemContracts.HOLD_SLACK_TICKS;
        int callBudget = Math.max(1, use.params.timeoutMs() / 50 + UseItemContracts.HOLD_SLACK_TICKS);
        use.holding = true;
        use.holdBudget = Math.min(duration, callBudget);
        use.monitor.raiseTimeoutMs(
                UseItemContracts.holdAwareTimeoutMs(use.params.timeoutMs(), use.holdBudget));
        return true;
    }

    /**
     * 推进按住状态一 tick。返回 true 表示还在按住。
     *
     * <p>三条结束路径：道具自己结束（吃完/喝药完成/服务端取消）→ 正常结束；
     * 界面在中途打开 → 松手交给 monitor 报 {@code screen_opened}；
     * 预算用尽仍在按住 → {@code releaseUsingItem()} 松手（否则客户端一直举着，
     * 后续调用永远发不出 press）。
     */
    private static boolean advanceHold(Minecraft mc, LocalPlayer player, ActiveUse use) {
        use.holdTicks++;
        use.holdBudget--;
        if (mc.screen != null) {
            player.releaseUsingItem();
            use.holding = false;
            return false;
        }
        if (!player.isUsingItem()) {
            use.holding = false; // 自然结束：服务端把进度条数满了（或取消了）
            return false;
        }
        if (use.holdBudget <= 0) {
            player.releaseUsingItem();
            use.holding = false;
            return false;
        }
        return true;
    }

    /** 记录"现在"的证据：手持数量、饱食等级、饱和度、盔甲槽物品 id。 */
    private static void observe(LocalPlayer player, ActiveUse use) {
        UseItemContracts.UseSnapshot now = snapshot(player, use.params.hand());
        use.itemAfter = now.itemCount();
        use.foodAfter = now.food();
        use.saturationAfter = now.saturation();
        use.armorAfter = now.armor();
    }

    /**
     * One press: the vanilla statement pair for an item use
     * ({@code gameMode.useItem}, plus the swing vanilla performs when the
     * result says so).
     */
    private static void press(Minecraft mc, LocalPlayer player, ActiveUse use) {
        InteractionHand hand = interactionHand(use.params.hand());
        InteractionResult result = mc.gameMode.useItem(player, hand);
        if (result != null && result.shouldSwing()) {
            player.swing(hand);
        }
        use.monitor.notePress(UseTools.interactionName(result), use.elapsedMs());
    }
}
