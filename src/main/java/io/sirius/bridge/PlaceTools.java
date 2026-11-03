package io.sirius.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The M4.4 v1.4 {@code place} tool: an INTELLIGENT block-placing primitive
 * (spec §8.2 分工原则 layer 3 - the dig/look precedent).
 *
 * <p><b>Why an action-layer tool</b>: the brain could hand-roll
 * {@code lookAt + input.click}, but that reports "I pressed the button", not
 * "a block exists at that cell now" - and placement is the one action where
 * failing silently is expensive (a door/bed/stair lands on the wrong face, a
 * container click opens a GUI instead of placing). Vanilla decides the placed
 * block's orientation from the PLAYER'S YAW, so the view must be pointed at
 * the support face BEFORE the use packet goes out; that ordering is exactly
 * what this tool owns.
 *
 * <p><b>Lifecycle of one call</b> (all game access on the client main thread):
 * <ol>
 *   <li><b>Pre-checks</b> (start task): in-world; permission tier / {@code
 *       gui_open} (a screen would swallow the click - or turn it into a GUI
 *       click); eye-to-HIT-POINT distance &le; {@link PlaceContracts#REACH}
 *       (else {@code -32602} with a "walk closer" teaching message); the target
 *       already holding the wanted block -> {@code already_placed} (idempotent
 *       success - checked BEFORE {@code blocked}, because a filled cell is by
 *       definition not replaceable); target not replaceable -> {@code blocked}
 *       (a container/workbench there would open its GUI instead); support cell
 *       air or liquid -> {@code support_missing}; item resolution - an explicit
 *       {@code item} is searched in the hotbar first (0-8); v1.6 起主背包（9-35）
 *       里有时会**自动换到当前选中格**（三次容器点击），换不动才报 {@code not_in_hotbar}，
 *       全背包都没有报 {@code no_item}。</li>
 *   <li><b>Smooth aim</b> at the hit point (300 deg/s, the dig aim speed):
 *       the orientation of stairs/doors/beds/signs comes from the yaw, so the
 *       view has to land there first.</li>
 *   <li><b>Held-slot switch, then one tick of settling</b>: {@code
 *       Inventory.selected} is written on the client, but the
 *       {@code ServerboundSetCarriedItemPacket} rides the connection flush of
 *       the tick - waiting one tick after the switch guarantees the server
 *       resolves the use against the NEW hand item instead of the old one.</li>
 *   <li><b>The use</b>: {@code gameMode.useItemOn(player, MAIN_HAND, hit)} +
 *       {@code player.swing(MAIN_HAND)} - the exact statement pair vanilla's
 *       right-click path runs, so it works while the window is unfocused (the
 *       M3.5-T6 finding that killed event-layer holds).</li>
 *   <li><b>Verdict</b>: every tick the target cell's registry id is compared
 *       with the wanted block - equal -> {@code placed}. On timeout the target
 *       is re-tested: still replaceable -> {@code not_placing} (the use never
 *       landed), otherwise {@code timeout} (something else filled it) - see
 *       {@link PlaceContracts#timeoutVerdict}.</li>
 * </ol>
 *
 * <p><b>Currency + audit</b>: one call = one input rate-limit token (the whole
 * aim+place sequence is one injection), plus an {@code INPUT} audit line
 * {@code method=place x,y,z face=.. result=..}.
 *
 * <p><b>Concurrency</b>: exactly one active place (class-level slot, main
 * thread confined). A new place supersedes the old one - the old waiter is
 * released with {@code not_placing} immediately. A screen opening mid-flight
 * aborts the same way (nothing is held down, so "abort" here means "stop
 * monitoring", never "leak a click into a GUI").
 */
final class PlaceTools {

    private PlaceTools() {
    }

    /** Registers the tool; called once from the BridgeServer constructor. */
    static void registerAll(ToolRegistry tools, InputGuard guard) {
        tools.register("place", (ctx, params) -> place(ctx, params, guard));
    }

    // ------------------------------------------------------------------ the tool call

    /** What the start task decided (main-thread outcome; the shell translates it). */
    private record StartOutcome(String kind, String detail, String block, Integer slot, ActivePlace place) {
        static StartOutcome of(String kind) {
            return new StartOutcome(kind, null, null, null, null);
        }
    }

    private static final String STARTED = "started";
    private static final String NOT_IN_GAME = "not_in_game";
    private static final String DENIED = "denied";
    private static final String OUT_OF_REACH = PlaceContracts.RESULT_OUT_OF_REACH;

    /** The tool handler: guard preamble -> main-thread start task -> latch wait -> response. */
    private static JsonObject place(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        final PlaceContracts.PlaceParams p;
        try {
            p = PlaceContracts.placeParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        String summary = p.x() + "," + p.y() + "," + p.z() + " face=" + p.face().wireName();
        if (!guard.enabled()) {
            ctx.audit("INPUT", "method=place " + summary + " result=input_disabled");
            return Json.errorResponse(ctx.id(), Json.INPUT_DISABLED,
                    InputContracts.inputDisabledMessage(), null);
        }
        if (PermissionContracts.deniedRegardlessOfScreen(guard.tier(), PermissionContracts.Action.INPUT)) {
            return permissionDenied(ctx, guard, summary, null);
        }
        if (!guard.tryAcquire()) {
            ctx.audit("INPUT", "method=place " + summary + " result=rate_limited");
            return Json.errorResponse(ctx.id(), Json.RATE_LIMITED,
                    InputContracts.rateLimitedMessage(guard.limitPerSecond()), null);
        }

        final long startedMs = System.currentTimeMillis();
        final StartOutcome start = PerceptionTools.callOnMainThread(ctx, () -> startPlace(p, guard));
        switch (start.kind()) {
            case NOT_IN_GAME:
                return Json.okResponse(ctx.id(), ToolContracts.notInGame());
            case DENIED:
                return permissionDenied(ctx, guard, summary, Boolean.valueOf(start.detail()));
            case OUT_OF_REACH:
                ctx.audit("INPUT", "method=place " + summary + " result=out_of_reach dist=" + start.detail());
                return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS,
                        PlaceContracts.outOfReachMessage(p.target(), p.face(), start.detail()), null);
            case STARTED:
                break;
            default:
                // A pre-check verdict that needs no action: an ok response with the
                // result string the brain maps to a teaching phrase (never an error -
                // "blocked"/"no_item" are body-state facts, not protocol failures).
                return preCheckResult(ctx, p, summary, start, System.currentTimeMillis() - startedMs);
        }

        // The monitor runs on game ticks (50 ms); the latch is bounded by the
        // timeout plus slack for one late frame. On a latch timeout (iconified
        // window -> no ticks) we answer not_placing WITHOUT touching game state
        // from this thread: the monitor's own timeout check releases the latch
        // on the next tick that does run.
        ActivePlace active = start.place();
        boolean completed = active.done.await(p.timeoutMs() + 1500L, TimeUnit.MILLISECONDS);
        String result = completed && active.result != null ? active.result : PlaceContracts.RESULT_NOT_PLACING;
        long elapsedMs = completed ? active.elapsedMs() : System.currentTimeMillis() - startedMs;
        String block = active.block != null ? active.block : start.block();
        JsonObject response = PlaceContracts.placeResult(p, result, block, elapsedMs,
                active.slot >= 0 ? Integer.valueOf(active.slot) : null,
                completed ? active.note : "monitor did not finish (window iconified? self-cleans next tick)");
        ctx.audit("INPUT", "method=place " + summary + " timeout_ms=" + p.timeoutMs()
                + (block != null ? " item=" + block : "")
                + " result=" + result
                + " elapsed_ms=" + elapsedMs
                + (active.slot >= 0 ? " slot=" + active.slot : ""));
        return Json.okResponse(ctx.id(), response);
    }

    /** Responds with one of the no-action pre-check verdicts, audited like any INPUT call. */
    private static JsonObject preCheckResult(ToolContext ctx, PlaceContracts.PlaceParams p, String summary,
                                             StartOutcome start, long elapsedMs) {
        JsonObject response = PlaceContracts.placeResult(p, start.kind(), start.block(), elapsedMs,
                start.slot(), start.detail());
        ctx.audit("INPUT", "method=place " + summary + " result=" + start.kind()
                + (start.detail() != null ? " reason=" + start.detail() : ""));
        return Json.okResponse(ctx.id(), response);
    }

    // ------------------------------------------------------------------ start (main thread)

    /**
     * The start task: pre-checks, item resolution, aim turn and monitor
     * installation. MUST run on the client main thread (called via
     * {@code PerceptionTools.callOnMainThread}).
     */
    private static StartOutcome startPlace(PlaceContracts.PlaceParams p, InputGuard guard) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return StartOutcome.of(NOT_IN_GAME);
        }
        // Screen-dependent tiers decide HERE (the screen can change any tick) -
        // exactly the dig/input.* discipline. The tier verdict comes first: a
        // security denial outranks an operational refusal.
        boolean screenOpen = mc.screen != null;
        if (!PermissionContracts.allows(guard.tier(), screenOpen, PermissionContracts.Action.INPUT)) {
            return new StartOutcome(DENIED, String.valueOf(screenOpen), null, null, null);
        }
        if (screenOpen) {
            // place is a world action: an open screen would swallow the use (or, on
            // a container screen, turn it into a GUI interaction). Refuse with a
            // teaching result instead of clicking blind.
            return new StartOutcome(PlaceContracts.RESULT_GUI_OPEN,
                    "screen=" + mc.screen.getClass().getSimpleName(), null, null, null);
        }

        PlaceContracts.Cell target = p.target();
        PlaceContracts.Cell support = PlaceContracts.supportOf(target, p.face());
        PlaceContracts.Hit hit = PlaceContracts.hitPoint(support, p.face().opposite());
        BlockPos targetPos = new BlockPos(target.x(), target.y(), target.z());
        BlockPos supportPos = new BlockPos(support.x(), support.y(), support.z());
        BlockState targetState = level.getBlockState(targetPos);
        BlockState supportState = level.getBlockState(supportPos);

        Vec3 eye = player.getEyePosition();
        Vec3 hitVec = new Vec3(hit.x(), hit.y(), hit.z());
        double dist = eye.distanceTo(hitVec);
        if (dist > PlaceContracts.REACH) {
            return new StartOutcome(OUT_OF_REACH,
                    String.format(Locale.ROOT, "%.1f", dist), blockId(targetState), null, null);
        }

        Inventory inventory = player.getInventory();
        String wantItem = p.item();
        if (wantItem == null) {
            ItemStack held = inventory.getSelected();
            wantItem = held.isEmpty() ? null : itemId(held);
        }
        // already_placed BEFORE blocked: a cell that already holds the wanted block
        // is by definition not replaceable, and repeating the same place is the
        // most common idempotent retry ("did it work?").
        if (wantItem != null && wantItem.equals(blockId(targetState))) {
            // No slot reported: nothing was used, the cell already holds the block.
            return new StartOutcome(PlaceContracts.RESULT_ALREADY_PLACED, null, wantItem, null, null);
        }
        if (!targetState.canBeReplaced()) {
            String occupied = blockId(targetState);
            return new StartOutcome(PlaceContracts.RESULT_BLOCKED,
                    occupied + " at " + target.shortString() + " is not replaceable - placing there"
                            + " would open its GUI or replace nothing. Pick an empty cell next to a"
                            + " solid block", occupied, null, null);
        }
        if (supportState.isAir() || !level.getFluidState(supportPos).isEmpty()) {
            return new StartOutcome(PlaceContracts.RESULT_SUPPORT_MISSING,
                    "support " + support.shortString() + " (" + p.face().wireName() + " of the target)"
                            + " is " + (supportState.isAir() ? "air" : "a liquid")
                            + " - the new block needs a solid neighbour on that side"
                            + " (for face=down: the block you are standing on)",
                    null, null, null);
        }

        final int slot;
        if (p.item() != null) {
            int hotbar = findItem(inventory, p.item(),
                    InventoryContracts.HOTBAR_START, InventoryContracts.HOTBAR_END);
            if (hotbar >= 0) {
                slot = hotbar;
            } else {
                int main = findItem(inventory, p.item(),
                        InventoryContracts.MAIN_START, InventoryContracts.MAIN_END);
                if (main >= 0) {
                    // v1.6: 主背包里有就直接换到当前选中格 —— agent 不该为了放一个方块
                    // 先手搓 GUI 拖物品（用户2026-11-01："主背包里的东西我没法直接用"）。
                    int selected = inventory.selected;
                    if (moveToSelected(player, main, p.item())) {
                        slot = selected;
                    } else {
                        // 换不动（没有 gameMode / 菜单对不上 / 点击被服务端拒绝）→ 退回旧结果词，
                        // 但话术必须说清"试过换了"，否则模型会以为东西不在背包里。
                        return new StartOutcome(PlaceContracts.RESULT_NOT_IN_HOTBAR,
                                p.item() + " sits in main slot " + main + " - tried to swap it into the"
                                        + " selected hotbar slot " + selected + " but the swap did not"
                                        + " take effect; move it into the hotbar (slots 0-8) yourself,"
                                        + " then place again", p.item(), null, null);
                    }
                } else {
                    return new StartOutcome(PlaceContracts.RESULT_NO_ITEM,
                            "no " + p.item() + " in the inventory (hotbar 0-8 / main 9-35) - craft or"
                                    + " collect it first", p.item(), null, null);
                }
            }
        } else {
            slot = inventory.selected;
            if (wantItem == null) {
                return new StartOutcome(PlaceContracts.RESULT_NO_ITEM,
                        "the current hand is empty - hold the block to place, or pass item",
                        null, Integer.valueOf(slot), null);
            }
        }

        double[] rotation = LookContracts.rotationTowards(eye.x, eye.y, eye.z, hitVec.x, hitVec.y, hitVec.z);
        ActivePlace active = installMonitor(p, target, support, hit, wantItem, slot);
        active.turn = TurnController.begin(rotation[0], rotation[1], PlaceContracts.AIM_TURN_SPEED_DEG_S);
        return new StartOutcome(STARTED, null, wantItem, Integer.valueOf(slot), active);
    }

    /** First slot in [from, to] holding {@code wanted}, or -1. */
    private static int findItem(Inventory inventory, String wanted, int from, int to) {
        for (int slot = from; slot <= to; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && wanted.equals(itemId(stack))) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * v1.6：把主背包第 {@code mainSlot} 格里的东西换到**当前选中的快捷栏格**。
     *
     * <p>为什么用容器点击而不是直接写 {@code Inventory}：服务端只认它自己那套 slot 操作，
     * 客户端改字段会被下一次同步覆盖；而 {@code handleInventoryMouseClick} 就是 GUI 拖物品
     * 走的同一条路（containerId 0 = 玩家背包菜单，主背包 9-35 与菜单 9-35 一一对应，
     * 快捷栏 i 对应菜单 36+i）。
     *
     * <p><b>槽位基准必须换算</b>：这里传进来的是背包下标，而点击认的是菜单槽，
     * 所以两边都过一遍 {@link InventoryContracts#menuSlotForPlayerSlot}。
     * 直接把背包下标（0-8）当菜单槽用会点到合成输出/合成格/盔甲格上——
     * 2026-11-01 真机就是这个 bug：主背包里的方块永远换不到手上。
     *
     * <p>三次点击才是"交换"：取起源格 → 点目标格（若目标非空则互换）→ 把手里剩下的放回源格。
     * 目标格原本为空时第三次点击是空操作（光标本来就是空的）。
     *
     * <p><b>成功判据是"目标格现在真的是想要的那个物品"</b>，不是"目标格非空"：
     * 真机上选中格原本放着沙子，于是旧判据（非空即成功）把"换了但没换过去"误报成成功，
     * 接着又拿沙子去放石头，模型永远不知道东西根本没到手上。
     *
     * @param wanted 想换到手上的物品 id（{@code p.item()}）
     * @return 选中格现在确实握着 {@code wanted}
     */
    private static boolean moveToSelected(LocalPlayer player, int mainSlot, String wanted) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player != player) {
            return false;
        }
        Inventory inventory = player.getInventory();
        int selected = inventory.selected;
        int containerId = player.inventoryMenu.containerId;
        int menuSource = InventoryContracts.menuSlotForPlayerSlot(mainSlot);
        int menuTarget = InventoryContracts.menuSlotForPlayerSlot(selected);
        mc.gameMode.handleInventoryMouseClick(containerId, menuSource, 0, ClickType.PICKUP, player);
        mc.gameMode.handleInventoryMouseClick(containerId, menuTarget, 0, ClickType.PICKUP, player);
        mc.gameMode.handleInventoryMouseClick(containerId, menuSource, 0, ClickType.PICKUP, player);
        return wanted.equals(itemId(inventory.getItem(selected)));
    }

    private static String itemId(ItemStack stack) {
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null ? key.toString() : null;
    }

    private static String blockId(BlockState state) {
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null ? key.toString() : "unknown";
    }

    // ------------------------------------------------------------------ monitor + tick pump

    /** The single active place; main-thread confined. */
    private static final class ActivePlace {
        final PlaceContracts.PlaceParams params;
        final PlaceContracts.Cell target;
        final PlaceContracts.Cell support;
        final PlaceContracts.Hit hit;
        final String block;
        final int slot;
        final long startedMs = System.currentTimeMillis();
        final CountDownLatch done = new CountDownLatch(1);
        /** The aim turn currently owned by this place (re-issued on drift). */
        TurnController.Turn turn;
        /** True once the use packet went out; from then on we only watch the cell. */
        boolean actionSent;
        /** Tick index of the held-slot write (-1 = never switched). */
        long switchedAtTick = -1;
        int aimTicks;
        volatile String result;
        volatile String note;

        ActivePlace(PlaceContracts.PlaceParams params, PlaceContracts.Cell target,
                    PlaceContracts.Cell support, PlaceContracts.Hit hit, String block, int slot) {
            this.params = params;
            this.target = target;
            this.support = support;
            this.hit = hit;
            this.block = block;
            this.slot = slot;
        }

        long elapsedMs() {
            return System.currentTimeMillis() - startedMs;
        }

        void complete(String result, String note) {
            this.result = result;
            this.note = note;
            done.countDown();
        }
    }

    private static ActivePlace active;

    /** Client-tick counter (the held-slot settling window is measured in ticks). */
    private static long tick;

    /**
     * Installs a new place (returning the handle the caller waits on),
     * superseding any active one. Main thread only.
     */
    private static ActivePlace installMonitor(PlaceContracts.PlaceParams p, PlaceContracts.Cell target,
                                              PlaceContracts.Cell support, PlaceContracts.Hit hit,
                                              String block, int slot) {
        ActivePlace previous = active;
        if (previous != null) {
            active = null;
            previous.complete(PlaceContracts.RESULT_NOT_PLACING, "superseded by another place");
        }
        ActivePlace place = new ActivePlace(p, target, support, hit, block, slot);
        active = place;
        return place;
    }

    /** Ends the active place now (waiter released). Main thread only. */
    private static void finishActive(ActivePlace place, String result, String note) {
        if (active == place) {
            active = null;
        }
        place.complete(result, note);
    }

    private static void abortActive(String note) {
        ActivePlace place = active;
        if (place != null) {
            finishActive(place, PlaceContracts.RESULT_NOT_PLACING, note);
        }
    }

    /**
     * The per-tick pump (from {@code SiriusBridge.onClientTick}): observe the
     * target cell, drive the aim, switch the hand, then act. Main thread only;
     * no-op when idle.
     */
    static void onClientTick() {
        tick++;
        ActivePlace place = active;
        if (place == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            abortActive("left the world mid-place");
            return;
        }
        if (mc.screen != null) {
            // A GUI opened mid-place: stop monitoring honestly (nothing is held
            // down, but a use must never be attributed to a screen state).
            abortActive("screen opened mid-place");
            return;
        }

        BlockPos targetPos = new BlockPos(place.target.x(), place.target.y(), place.target.z());
        BlockState state = level.getBlockState(targetPos);
        // Main signal: the cell now holds the wanted block (client prediction or
        // the server's confirmation - both land in the client level).
        if (place.block != null && place.block.equals(blockId(state))) {
            finishActive(place, PlaceContracts.RESULT_PLACED, null);
            return;
        }
        long elapsed = place.elapsedMs();
        if (elapsed >= place.params.timeoutMs()) {
            boolean stillReplaceable = state.canBeReplaced();
            finishActive(place, PlaceContracts.timeoutVerdict(stillReplaceable),
                    stillReplaceable
                            ? "the use never landed (target still replaceable after " + elapsed + " ms)"
                            : "the target cell was filled by something that is not " + place.block);
            return;
        }
        if (place.actionSent) {
            return; // waiting for the block to appear; the timeout above bounds it
        }

        Vec3 eye = player.getEyePosition();
        double[] want = LookContracts.rotationTowards(
                eye.x, eye.y, eye.z, place.hit.x(), place.hit.y(), place.hit.z());
        boolean aimed = Math.abs(LookContracts.yawDelta(player.getYRot(), want[0]))
                < PlaceContracts.AIM_TOLERANCE_DEG
                && Math.abs(player.getXRot() - want[1]) < PlaceContracts.AIM_TOLERANCE_DEG;
        if (!aimed) {
            // Re-aim on drift (movement-look or another mod owning the view): the
            // initial turn may have converged and finished already, so a live
            // turn that no longer matches the target gets replaced - the dig rule.
            TurnController.Turn turn = place.turn;
            if (turn == null || turn.isFinished() || !turn.matchesTarget(want[0], want[1], 1.0)) {
                place.turn = TurnController.begin(want[0], want[1], PlaceContracts.AIM_TURN_SPEED_DEG_S);
            }
            if (++place.aimTicks >= PlaceContracts.AIM_STALL_TICKS) {
                finishActive(place, PlaceContracts.RESULT_NOT_PLACING,
                        "aim never converged on the hit point (something else owns the view?)");
            }
            return;
        }

        Inventory inventory = player.getInventory();
        if (inventory.selected != place.slot) {
            // Orientation of stairs/doors/beds comes from the yaw (already aimed),
            // and the held item decides WHAT gets placed - so the switch happens
            // first, then one tick of settling (below).
            inventory.selected = place.slot;
            place.switchedAtTick = tick;
            return;
        }
        if (place.switchedAtTick >= 0 && tick - place.switchedAtTick < 1) {
            // The ServerboundSetCarriedItemPacket rides the connection flush of the
            // tick that saw the write; waiting one tick keeps the server's hand
            // item in sync before it resolves the use packet.
            return;
        }
        sendUse(mc, player, place);
    }

    /** The action-layer use: vanilla's right-click statements, aimed at the support face. */
    private static void sendUse(Minecraft mc, LocalPlayer player, ActivePlace place) {
        if (mc.gameMode == null) {
            finishActive(place, PlaceContracts.RESULT_NOT_PLACING, "no gameMode (not in a world?)");
            return;
        }
        BlockPos supportPos = new BlockPos(place.support.x(), place.support.y(), place.support.z());
        Vec3 hitVec = new Vec3(place.hit.x(), place.hit.y(), place.hit.z());
        BlockHitResult hitResult = new BlockHitResult(
                hitVec, direction(place.params.face().opposite()), supportPos, false);
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        player.swing(InteractionHand.MAIN_HAND);
        place.actionSent = true;
        if (result == InteractionResult.FAIL) {
            // The client-side branch already refused (world border / a FAIL from the
            // block's own useOn): no use packet is worth waiting for. Server-side
            // rejections are not visible here and surface as timeout/not_placing.
            finishActive(place, PlaceContracts.RESULT_REJECTED_BY_SERVER,
                    "useItemOn answered FAIL (outside the world border, or the block refused)");
        }
    }

    /** Protocol face -> vanilla direction (explicit switch: no name-lookup ambiguity). */
    private static Direction direction(PlaceContracts.Face face) {
        return switch (face) {
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
        };
    }

    // ------------------------------------------------------------------ helpers

    /** The {@code -32012} denial + INPUT_DENIED audit line (mirrors the input.* preamble). */
    private static JsonObject permissionDenied(ToolContext ctx, InputGuard guard,
                                               String detail, Boolean screenOpen) {
        ctx.audit("INPUT_DENIED", "reason=permission tier=" + guard.tier().configName()
                + " method=place " + detail
                + (screenOpen != null ? " screen=" + screenOpen : ""));
        boolean screen = screenOpen != null && screenOpen;
        return Json.errorResponse(ctx.id(), Json.PERMISSION_DENIED,
                PermissionContracts.deniedMessage(guard.tier(), screen, PermissionContracts.Action.INPUT), null);
    }
}
