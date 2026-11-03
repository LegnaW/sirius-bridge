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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The M4.5 v1.5 {@code use} tool: an INTELLIGENT right-click-on-block
 * primitive - the third verb next to {@code dig} (break) and {@code place}
 * (put down). Spec: {@link UseContracts}' class comment carries the reasoning
 * for the verdict surface; this class is the thin shell that owns the game
 * access.
 *
 * <p><b>Lifecycle of one call</b> (all game access on the client main thread):
 * <ol>
 *   <li><b>Pre-checks</b> (start task): in-world; permission tier / {@code
 *       gui_open} (a screen would swallow the use - or turn it into a GUI
 *       click, exactly the {@code place} discipline); target cell not air
 *       ({@code no_effect} + teaching text - clicking air is the ITEM's own
 *       right-click, not a block use); eye-to-click-point distance &le;
 *       {@link UseContracts#REACH} (else {@code -32602} "walk closer"); the
 *       click face resolved here ({@code face} given, or AUTO = the side
 *       facing the eye, see {@link UseContracts#autoFace}).</li>
 *   <li><b>Smooth aim</b> at the click point (300 deg/s, the dig/place speed):
 *       the FACE decides what the block does, so the view must land on that
 *       face before the use packet goes out.</li>
 *   <li><b>The use</b>: one {@code gameMode.useItemOn(player, hand, hit)} +
 *       {@code player.swing(hand)} - the exact statement pair vanilla's
 *       right-click path runs, so it works while the window is unfocused (the
 *       M3.5-T6 finding that killed event-layer holds and the reason {@code
 *       input.click(button=1)} was never good enough for this verb).</li>
 *   <li><b>Evidence</b> (per tick, {@code SiriusBridge.onClientTick} ->
 *       {@link #onClientTick()}): a screen appearing ({@code screen_opened} +
 *       {@code screen_class}), the target block state flipping
 *       ({@code used} + {@code effect=block_changed} - property-only changes
 *       count: a door's {@code open=true} keeps the same registry id), an
 *       {@code InteractionResult.FAIL} ({@code rejected}), or the timeout
 *       ({@code used}/{@code none_visible} when the interaction was consumed,
 *       else {@code no_effect}).</li>
 * </ol>
 *
 * <p><b>A screen mid-aim is a refusal, a screen after the press is the
 * answer</b>: unlike {@code place} (which aborts on any screen because it is
 * waiting for a BLOCK to appear), {@code use} treats a screen that opens
 * before the press as somebody else's GUI ({@code gui_open}) and a screen
 * that opens after it as the verdict ({@code screen_opened}). That
 * distinction is the whole point of the tool.
 *
 * <p><b>Currency + audit</b>: one call = one input rate-limit token, plus an
 * {@code INPUT} audit line {@code method=use x,y,z face=.. hand=.. result=..
 * elapsed_ms=..}.
 *
 * <p><b>Concurrency</b>: one active use at a time (class-level slot, main
 * thread confined). A new use supersedes the old one (waiter released with
 * {@code not_using}).
 */
final class UseTools {

    private UseTools() {
    }

    /** Registers the tool; called once from the BridgeServer constructor. */
    static void registerAll(ToolRegistry tools, InputGuard guard) {
        tools.register("use", (ctx, params) -> use(ctx, params, guard));
    }

    // ------------------------------------------------------------------ the tool call

    /** What the start task decided (main-thread outcome; the shell translates). */
    private record StartOutcome(String kind, String detail, String block, PlaceContracts.Face face,
                                ActiveUse use) {
        static StartOutcome of(String kind) {
            return new StartOutcome(kind, null, null, null, null);
        }
    }

    private static final String STARTED = "started";
    private static final String NOT_IN_GAME = "not_in_game";
    private static final String DENIED = "denied";
    private static final String AIR = "air";
    private static final String OUT_OF_REACH = UseContracts.RESULT_OUT_OF_REACH;

    /** The tool handler: guard preamble -> main-thread start task -> latch wait -> response. */
    private static JsonObject use(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        final UseContracts.UseParams p;
        try {
            p = UseContracts.useParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        String summary = p.x() + "," + p.y() + "," + p.z()
                + " face=" + UseContracts.faceName(p.face()) + " hand=" + p.hand().wireName();
        if (!guard.enabled()) {
            ctx.audit("INPUT", "method=use " + summary + " result=input_disabled");
            return Json.errorResponse(ctx.id(), Json.INPUT_DISABLED,
                    InputContracts.inputDisabledMessage(), null);
        }
        if (PermissionContracts.deniedRegardlessOfScreen(guard.tier(), PermissionContracts.Action.INPUT)) {
            return permissionDenied(ctx, guard, summary, null);
        }
        if (!guard.tryAcquire()) {
            ctx.audit("INPUT", "method=use " + summary + " result=rate_limited");
            return Json.errorResponse(ctx.id(), Json.RATE_LIMITED,
                    InputContracts.rateLimitedMessage(guard.limitPerSecond()), null);
        }

        final long startedMs = System.currentTimeMillis();
        final StartOutcome start = PerceptionTools.callOnMainThread(ctx, () -> startUse(p, guard));
        switch (start.kind()) {
            case NOT_IN_GAME:
                return Json.okResponse(ctx.id(), ToolContracts.notInGame());
            case DENIED:
                return permissionDenied(ctx, guard, summary, Boolean.valueOf(start.detail()));
            case OUT_OF_REACH:
                ctx.audit("INPUT", "method=use " + summary + " result=out_of_reach dist=" + start.detail());
                return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS,
                        UseContracts.outOfReachMessage(p.target(),
                                start.face() != null ? start.face() : PlaceContracts.Face.UP,
                                start.detail()),
                        null);
            case AIR, UseContracts.RESULT_GUI_OPEN:
                return preCheckResult(ctx, p, summary, start, System.currentTimeMillis() - startedMs);
            case STARTED:
                break;
            default:
                return preCheckResult(ctx, p, summary, start, System.currentTimeMillis() - startedMs);
        }

        // The monitor runs on game ticks (50 ms); the latch is bounded by the
        // timeout plus slack for one late frame. On a latch timeout (iconified
        // window -> no ticks) we answer not_using WITHOUT touching game state
        // from this thread: the monitor's own timeout check releases the latch
        // on the next tick that does run.
        ActiveUse active = start.use();
        boolean completed = active.done.await(p.timeoutMs() + 1500L, TimeUnit.MILLISECONDS);
        String result = completed && active.result != null ? active.result : UseContracts.RESULT_NOT_USING;
        long elapsedMs = completed ? active.elapsedMs() : System.currentTimeMillis() - startedMs;
        String block = active.block != null ? active.block : start.block();
        PlaceContracts.Face face = start.face() != null ? start.face() : active.face;
        JsonObject response = UseContracts.useResult(p, result, block, face, p.hand().wireName(),
                active.effect, active.interaction, active.screenClass, elapsedMs,
                completed ? active.note : "monitor did not finish (window iconified? self-cleans next tick)");
        ctx.audit("INPUT", "method=use " + summary + " timeout_ms=" + p.timeoutMs()
                + " face=" + UseContracts.faceName(face)
                + (block != null ? " block=" + block : "")
                + (active.screenClass != null ? " screen=" + active.screenClass : "")
                + " result=" + result
                + (active.effect != null ? " effect=" + active.effect : "")
                + (active.interaction != null ? " interaction=" + active.interaction : "")
                + " elapsed_ms=" + elapsedMs);
        return Json.okResponse(ctx.id(), response);
    }

    /** Responds with one of the no-action pre-check verdicts, audited like any INPUT call. */
    private static JsonObject preCheckResult(ToolContext ctx, UseContracts.UseParams p, String summary,
                                             StartOutcome start, long elapsedMs) {
        String result = AIR.equals(start.kind()) ? UseContracts.RESULT_NO_EFFECT : start.kind();
        JsonObject response = UseContracts.useResult(p, result, start.block(), start.face(),
                p.hand().wireName(), null, null, null, elapsedMs, start.detail());
        ctx.audit("INPUT", "method=use " + summary + " result=" + result
                + (start.detail() != null ? " reason=" + start.detail() : ""));
        return Json.okResponse(ctx.id(), response);
    }

    // ------------------------------------------------------------------ start (main thread)

    /**
     * The start task: pre-checks, face resolution, aim turn and monitor
     * installation. MUST run on the client main thread (called via
     * {@code PerceptionTools.callOnMainThread}).
     */
    private static StartOutcome startUse(UseContracts.UseParams p, InputGuard guard) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return StartOutcome.of(NOT_IN_GAME);
        }
        // Screen-dependent tiers decide HERE (screen can change any tick) - the
        // dig/place/input.* discipline; a security denial outranks an
        // operational refusal.
        boolean screenOpen = mc.screen != null;
        if (!PermissionContracts.allows(guard.tier(), screenOpen, PermissionContracts.Action.INPUT)) {
            return new StartOutcome(DENIED, String.valueOf(screenOpen), null, null, null);
        }
        if (screenOpen) {
            // An open screen would swallow the use (or turn it into a GUI click).
            return new StartOutcome(UseContracts.RESULT_GUI_OPEN,
                    "screen=" + mc.screen.getClass().getSimpleName(), null, null, null);
        }

        BlockPos targetPos = new BlockPos(p.x(), p.y(), p.z());
        BlockState state = level.getBlockState(targetPos);
        String blockName = blockId(state);
        Vec3 eyeVec = player.getEyePosition();
        PlaceContracts.Hit eye = new PlaceContracts.Hit(eyeVec.x, eyeVec.y, eyeVec.z);
        PlaceContracts.Face face = p.face() != null
                ? p.face()
                : UseContracts.autoFace(eye, p.target());
        PlaceContracts.Hit hit = UseContracts.hitPoint(p.target(), face);
        Vec3 hitVec = new Vec3(hit.x(), hit.y(), hit.z());
        double dist = eyeVec.distanceTo(hitVec);
        if (dist > UseContracts.REACH) {
            return new StartOutcome(OUT_OF_REACH,
                    String.format(Locale.ROOT, "%.1f", dist), blockName, face, null);
        }
        if (state.isAir()) {
            // Clicking air is the ITEM's right-click (bow, bucket, book), which this
            // tool deliberately does not own: it takes a BLOCK target but cannot tell
            // which item the brain meant. Teach instead of silently no-op'ing.
            return new StartOutcome(AIR,
                    "the cell " + p.target().shortString() + " is air - use needs a block target"
                            + " (item-in-air use, e.g. eating/a bow, is input.click button=right"
                            + " with an empty crosshair)", null, face, null);
        }

        double[] rotation = LookContracts.rotationTowards(
                eyeVec.x, eyeVec.y, eyeVec.z, hitVec.x, hitVec.y, hitVec.z);
        ActiveUse active = installMonitor(p, targetPos, state, face);
        active.turn = TurnController.begin(rotation[0], rotation[1], UseContracts.AIM_TURN_SPEED_DEG_S);
        return new StartOutcome(STARTED, null, blockName, face, active);
    }

    private static String blockId(BlockState state) {
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null ? key.toString() : "unknown";
    }

    // ------------------------------------------------------------------ monitor + tick pump

    /** The single active use; main-thread confined. */
    private static final class ActiveUse {
        final UseContracts.UseParams params;
        final BlockPos target;
        /** The target block's state at press time - the block_changed baseline. */
        final BlockState before;
        final PlaceContracts.Face face;
        final String block;
        /** The pure per-tick state machine (aim stall, evidence precedence, timeout). */
        final UseContracts.UseMonitor monitor;
        final long startedMs = System.currentTimeMillis();
        final CountDownLatch done = new CountDownLatch(1);
        /** The aim turn currently owned by this use (re-issued on drift). */
        TurnController.Turn turn;
        /** True once the use packet went out; from then on we only watch for evidence. */
        boolean actionSent;
        /** The interaction answer of the press (UseContracts.INTERACTION_*). */
        volatile String interaction;
        volatile String result;
        volatile String effect;
        volatile String screenClass;
        volatile String note;

        ActiveUse(UseContracts.UseParams params, BlockPos target, BlockState before,
                  PlaceContracts.Face face, String block) {
            this.params = params;
            this.target = target;
            this.before = before;
            this.face = face;
            this.block = block;
            this.monitor = new UseContracts.UseMonitor(params.timeoutMs());
        }

        long elapsedMs() {
            return System.currentTimeMillis() - startedMs;
        }

        void complete(String result, String effect, String screenClass, String note) {
            this.result = result;
            this.effect = effect;
            this.screenClass = screenClass;
            this.note = note;
            done.countDown();
        }
    }

    private static ActiveUse active;

    /** Installs a new use (returning the handle the caller waits on), superseding any active one. */
    private static ActiveUse installMonitor(UseContracts.UseParams p, BlockPos target,
                                            BlockState before, PlaceContracts.Face face) {
        ActiveUse previous = active;
        if (previous != null) {
            active = null;
            previous.complete(UseContracts.RESULT_NOT_USING, null, null, "superseded by another use");
        }
        ActiveUse use = new ActiveUse(p, target, before, face, blockId(before));
        active = use;
        return use;
    }

    /** Ends the active use now (waiter released). Main thread only. */
    private static void finishActive(ActiveUse use, String result, String effect,
                                     String screenClass, String note) {
        if (active == use) {
            active = null;
        }
        use.complete(result, effect, screenClass, note);
    }

    private static void abortActive(String note) {
        ActiveUse use = active;
        if (use != null) {
            finishActive(use, UseContracts.RESULT_NOT_USING, null, null, note);
        }
    }

    /**
     * The per-tick pump (from {@code SiriusBridge.onClientTick}): drive the
     * aim, send the single use, then collect evidence. Main thread only;
     * no-op when idle.
     */
    static void onClientTick() {
        ActiveUse use = active;
        if (use == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            abortActive("left the world mid-use");
            return;
        }
        boolean screenOpen = mc.screen != null;
        if (screenOpen && !use.actionSent) {
            // A screen appeared BEFORE our press: it is not our evidence, it is
            // somebody else's GUI (another mod, a queued open, the human). Refuse
            // honestly - clicking into it would be blind GUI input.
            finishActive(use, UseContracts.RESULT_GUI_OPEN, null,
                    mc.screen.getClass().getSimpleName(), "screen opened before the use was sent");
            return;
        }

        BlockState state = level.getBlockState(use.target);
        // BlockState instances are interned per property combination, so equals()
        // is exactly "same block with the same properties" - a door's open=true
        // flips this without changing its registry id.
        boolean blockChanged = !state.equals(use.before);

        Vec3 eye = player.getEyePosition();
        PlaceContracts.Hit hit = UseContracts.hitPoint(use.params.target(), use.face);
        double[] want = LookContracts.rotationTowards(eye.x, eye.y, eye.z, hit.x(), hit.y(), hit.z());
        boolean aimed = Math.abs(LookContracts.yawDelta(player.getYRot(), want[0]))
                < UseContracts.AIM_TOLERANCE_DEG
                && Math.abs(player.getXRot() - want[1]) < UseContracts.AIM_TOLERANCE_DEG;
        if (!aimed && !use.actionSent) {
            // Re-aim on drift (movement-look, Baritone finishing its approach, another
            // mod owning the view): a finished turn that no longer points at the click
            // point gets replaced - the dig/place rule.
            TurnController.Turn turn = use.turn;
            if (turn == null || turn.isFinished() || !turn.matchesTarget(want[0], want[1], 1.0)) {
                use.turn = TurnController.begin(want[0], want[1], UseContracts.AIM_TURN_SPEED_DEG_S);
            }
        } else if (!use.actionSent) {
            sendUse(mc, player, use);
            // Re-read after the press: a singleplayer host may already have opened the
            // screen or flipped the block in this very tick.
            screenOpen = mc.screen != null;
            blockChanged = !level.getBlockState(use.target).equals(use.before);
        }

        UseContracts.Verdict verdict = use.monitor.tick(new UseContracts.TickView(
                aimed, use.actionSent, screenOpen, blockChanged, use.interaction, use.elapsedMs()));
        if (verdict.terminal()) {
            String screenClass = UseContracts.EFFECT_SCREEN.equals(verdict.effect()) && mc.screen != null
                    ? mc.screen.getClass().getSimpleName()
                    : null;
            finishActive(use, verdict.result(), verdict.effect(), screenClass, null);
        }
    }

    /** The action-layer use: vanilla's right-click statement pair, aimed at the click face. */
    private static void sendUse(Minecraft mc, LocalPlayer player, ActiveUse use) {
        if (mc.gameMode == null) {
            finishActive(use, UseContracts.RESULT_NOT_USING, null, null, "no gameMode (not in a world?)");
            return;
        }
        Vec3 hitVec = new Vec3(use.params.target().x() + 0.5 + use.face.stepX() * 0.5,
                use.params.target().y() + 0.5 + use.face.stepY() * 0.5,
                use.params.target().z() + 0.5 + use.face.stepZ() * 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitVec, direction(use.face), use.target, false);
        InteractionHand hand = use.params.hand() == UseContracts.Hand.OFF
                ? InteractionHand.OFF_HAND
                : InteractionHand.MAIN_HAND;
        InteractionResult result = mc.gameMode.useItemOn(player, hand, hitResult);
        player.swing(hand);
        use.actionSent = true;
        use.interaction = interactionName(result);
    }

    /**
     * {@code InteractionResult} -> the {@code interaction} wire word. Written
     * against the four constants 1.21.1 has, with {@code consumesAction()} as
     * the fallback so a future constant cannot silently become "pass".
     *
     * <p>Package-private (was private): the v1.6 {@code useItem} tool reports
     * the same vocabulary and must not re-implement the mapping.
     */
    static String interactionName(InteractionResult result) {
        if (result == InteractionResult.FAIL) {
            return UseContracts.INTERACTION_FAIL;
        }
        if (result == InteractionResult.SUCCESS) {
            return UseContracts.INTERACTION_SUCCESS;
        }
        if (result == InteractionResult.CONSUME) {
            return UseContracts.INTERACTION_CONSUME;
        }
        if (result == InteractionResult.PASS) {
            return UseContracts.INTERACTION_PASS;
        }
        return result != null && result.consumesAction()
                ? UseContracts.INTERACTION_SUCCESS
                : UseContracts.INTERACTION_PASS;
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
                + " method=use " + detail
                + (screenOpen != null ? " screen=" + screenOpen : ""));
        boolean screen = screenOpen != null && screenOpen;
        return Json.errorResponse(ctx.id(), Json.PERMISSION_DENIED,
                PermissionContracts.deniedMessage(guard.tier(), screen, PermissionContracts.Action.INPUT), null);
    }
}
