package io.sirius.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

/**
 * Frozen-contract logic for the M4.5 v1.5 {@code use} tool: parameter
 * validation, the click-point geometry, the {@code result}/{@code effect}/
 * {@code interaction} vocabularies, the per-tick monitor and response
 * assembly. Pure Gson + JDK only - no Minecraft classes - so the smoke test
 * covers the whole decision surface without a game.
 *
 * <p><b>Why this tool exists</b>: protocol 1.4 had an atomic primitive for
 * BREAKING ({@code dig}) and one for PLACING ({@code place}), but the third
 * right-click verb - USE - only existed as {@code input.click button=1}: the
 * event layer, which neither aims nor reports whether anything happened. A
 * brain that has to hand-roll {@code lookAt + click} nudges the crosshair for
 * several turns and then cannot tell a chest screen from a stone block that
 * refused the interaction. {@code use} owns the same three things {@code
 * place} owns: the aim (the clicked FACE decides what the block does - a bed's
 * half, a lever's direction, a chest's side are all face-sensitive), the
 * single action-layer {@code useItemOn}, and an OBSERVED verdict.
 *
 * <p><b>The verdict is three independent fields, not one word</b> (a single
 * string cannot say "the button was pressed and the door opened" and "the
 * chest screen appeared" while staying honest about neither being visible):
 * <ul>
 *   <li>{@code result} - what the tool decided happened: {@code used} (an
 *       effect was observed or the interaction was consumed),
 *       {@code screen_opened} (a GUI appeared - carry {@code screen_class} so
 *       the brain can go straight to {@code getGuiState} slots),
 *       {@code no_effect} (nothing observable and the interaction was not
 *       consumed - the block refused), plus the refusals
 *       {@code out_of_reach} / {@code gui_open} / {@code rejected} /
 *       {@code not_using}.</li>
 *   <li>{@code effect} - the EVIDENCE: {@code screen} / {@code block_changed}
 *       / {@code none_visible}. The last one is honest about "the game
 *       answered SUCCESS but this tool sees no visible change" (eating,
 *       lighting, a mod whose effect lands a tick later): claiming
 *       {@code no_effect} there would teach the brain to retry a working
 *       action forever.</li>
 *   <li>{@code interaction} - the {@code InteractionResult} the client got
 *       ({@code success}/{@code consume}/{@code pass}/{@code fail}). {@code
 *       pass} is the common "this block has no interaction" answer, which is
 *       exactly the teaching signal for "try another face / another
 *       block".</li>
 * </ul>
 *
 * <p><b>Face semantics differ from {@code place} on purpose</b>: here {@code
 * face} is the face OF THE TARGET BLOCK that gets clicked ("click the chest's
 * top face"), because that is what vanilla's hit result carries. Omitting it
 * means AUTO: the axis from the block centre toward the eye (the block side
 * actually facing the player), which is what a human clicks by habit.
 */
public final class UseContracts {

    private UseContracts() {
    }

    // ------------------------------------------------------------------ constants

    /**
     * Default {@code timeout_ms}. Shorter than {@code place}'s 3 s: a
     * right-click answer is either a screen (opens within a few ticks of the
     * server round trip) or a block-state flip; the only case that genuinely
     * waits is "SUCCESS but nothing visible", which is worth one second, not
     * three.
     */
    public static final int DEFAULT_TIMEOUT_MS = 2_000;
    public static final int MIN_TIMEOUT_MS = 600;
    public static final int MAX_TIMEOUT_MS = 10_000;

    /** Reach: eye position to the CLICK POINT (vanilla survival 4.5, same budget as dig/place). */
    public static final double REACH = 4.5;

    /** Aim speed (deg/s): the dig/place 300 deg/s - face-sensitive blocks need the view to LAND first. */
    public static final double AIM_TURN_SPEED_DEG_S = 300.0;

    /** Aim stall budget: 100 ticks (5 s) without convergence -> {@code not_using}. */
    public static final int AIM_STALL_TICKS = 100;

    /** Aim tolerance: the view counts as "on the click point" within this many degrees per axis. */
    public static final double AIM_TOLERANCE_DEG = 2.0;

    // ------------------------------------------------------------------ params

    /** Which hand performs the use. Off-hand matters for shields/totems and dual-wield mods. */
    public enum Hand {
        MAIN("main"),
        OFF("off");

        private final String wireName;

        Hand(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        /** Parses the wire spelling; {@code null} for anything else (case-sensitive). */
        public static Hand parse(String raw) {
            if (raw == null) {
                return null;
            }
            for (Hand hand : values()) {
                if (hand.wireName.equals(raw)) {
                    return hand;
                }
            }
            return null;
        }

        public static String allowed() {
            return "main/off";
        }
    }

    /**
     * Validated {@code use} params. {@code face} is {@code null} for AUTO -
     * the shell resolves it from the eye position on the main thread (the
     * contract half has no eye), so the resolved face is echoed in the
     * response as {@code face}.
     */
    public record UseParams(int x, int y, int z, PlaceContracts.Face face, Hand hand, int timeoutMs) {

        public PlaceContracts.Cell target() {
            return new PlaceContracts.Cell(x, y, z);
        }
    }

    /**
     * Validates {@code use} params per the frozen schema: REQUIRED integer
     * {@code x}/{@code y}/{@code z}; optional {@code face} (one of the six
     * wire names, default AUTO); optional {@code hand} (default {@code
     * main}); optional {@code timeout_ms} 600..10000 (default 2000).
     */
    public static UseParams useParams(JsonObject params) throws ToolContracts.InvalidParams {
        int x = blockCoord(params, "x");
        int y = blockCoord(params, "y");
        int z = blockCoord(params, "z");

        PlaceContracts.Face face = null; // AUTO
        JsonElement faceElement = params.get("face");
        if (faceElement != null && !faceElement.isJsonNull()) {
            if (!faceElement.isJsonPrimitive() || !faceElement.getAsJsonPrimitive().isString()) {
                throw new ToolContracts.InvalidParams("use face must be a string, one of "
                        + PlaceContracts.Face.allowed() + " (or omit it for auto)");
            }
            String raw = faceElement.getAsString();
            face = PlaceContracts.Face.parse(raw);
            if (face == null) {
                throw new ToolContracts.InvalidParams("use face must be one of "
                        + PlaceContracts.Face.allowed()
                        + " (the face of the TARGET block to click, e.g. \"up\" = click its top"
                        + " face), or omitted for auto (the side facing you), got: \"" + raw + "\"");
            }
        }

        Hand hand = Hand.MAIN;
        JsonElement handElement = params.get("hand");
        if (handElement != null && !handElement.isJsonNull()) {
            if (!handElement.isJsonPrimitive() || !handElement.getAsJsonPrimitive().isString()) {
                throw new ToolContracts.InvalidParams("use hand must be a string, one of "
                        + Hand.allowed());
            }
            String raw = handElement.getAsString();
            Hand parsed = Hand.parse(raw);
            if (parsed == null) {
                throw new ToolContracts.InvalidParams("use hand must be one of " + Hand.allowed()
                        + ", got: \"" + raw + "\"");
            }
            hand = parsed;
        }

        return new UseParams(x, y, z, face, hand, timeoutMs(params));
    }

    private static int blockCoord(JsonObject params, String member) throws ToolContracts.InvalidParams {
        JsonElement e = params.get(member);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new ToolContracts.InvalidParams("use requires integer block coordinate " + member);
        }
        double v = e.getAsDouble();
        if (!Double.isFinite(v) || v != Math.floor(v)) {
            throw new ToolContracts.InvalidParams("use " + member
                    + " must be an integer block coordinate, got: " + v);
        }
        if (v < -30_000_000.0 || v > 30_000_000.0) {
            throw new ToolContracts.InvalidParams("use " + member
                    + " is outside the world border: " + (long) v);
        }
        return (int) v;
    }

    private static int timeoutMs(JsonObject params) throws ToolContracts.InvalidParams {
        JsonElement e = params.get("timeout_ms");
        if (e == null || e.isJsonNull()) {
            return DEFAULT_TIMEOUT_MS;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new ToolContracts.InvalidParams("use timeout_ms must be an integer "
                    + MIN_TIMEOUT_MS + ".." + MAX_TIMEOUT_MS);
        }
        double v = e.getAsDouble();
        if (!Double.isFinite(v) || v != Math.floor(v)) {
            throw new ToolContracts.InvalidParams("use timeout_ms must be an integer "
                    + MIN_TIMEOUT_MS + ".." + MAX_TIMEOUT_MS + ", got: " + v);
        }
        if (v < MIN_TIMEOUT_MS || v > MAX_TIMEOUT_MS) {
            throw new ToolContracts.InvalidParams("use timeout_ms must be within "
                    + MIN_TIMEOUT_MS + ".." + MAX_TIMEOUT_MS + ", got: " + (long) v);
        }
        return (int) v;
    }

    // ------------------------------------------------------------------ geometry

    /**
     * The click point: the target block's centre pushed 0.5 out along
     * {@code face} - the middle of the face being clicked. (For {@code place}
     * the point sat on the SUPPORT block; here the TARGET block is what the
     * crosshair must hit, so no relative() is involved.)
     */
    public static PlaceContracts.Hit hitPoint(PlaceContracts.Cell target, PlaceContracts.Face face) {
        return new PlaceContracts.Hit(
                target.x() + 0.5 + face.stepX() * 0.5,
                target.y() + 0.5 + face.stepY() * 0.5,
                target.z() + 0.5 + face.stepZ() * 0.5);
    }

    /**
     * AUTO face: the block side that points at the eye. Vanilla's raycast
     * would pick the same face for a straight-on look, and the largest
     * |axis| delta is that decision already made. Ties resolve in the
     * enum's declaration order (up before down, north before south) so the
     * answer is deterministic - the smoke test pins it.
     */
    public static PlaceContracts.Face autoFace(PlaceContracts.Hit eye, PlaceContracts.Cell target) {
        double dx = eye.x() - (target.x() + 0.5);
        double dy = eye.y() - (target.y() + 0.5);
        double dz = eye.z() - (target.z() + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return dy >= 0 ? PlaceContracts.Face.UP : PlaceContracts.Face.DOWN;
        }
        if (ax >= az) {
            return dx >= 0 ? PlaceContracts.Face.EAST : PlaceContracts.Face.WEST;
        }
        return dz >= 0 ? PlaceContracts.Face.SOUTH : PlaceContracts.Face.NORTH;
    }

    /** Eye-to-click-point distance beyond {@link #REACH} (the {@code -32602} teaching text). */
    public static String outOfReachMessage(PlaceContracts.Cell target, PlaceContracts.Face face,
                                           String distance) {
        return "use click point (face " + face.wireName() + " of block " + target.shortString()
                + ") is " + distance + " blocks away - beyond the " + REACH + "-block reach."
                + " Walk closer first (e.g. walkTo a spot ~1.5 blocks beside the target), then use again";
    }

    // ------------------------------------------------------------------ results

    /** The {@code result} vocabulary; the brain maps each to a teaching phrase. */
    public static final String RESULT_USED = "used";
    public static final String RESULT_SCREEN_OPENED = "screen_opened";
    public static final String RESULT_NO_EFFECT = "no_effect";
    public static final String RESULT_REJECTED = "rejected";
    public static final String RESULT_OUT_OF_REACH = "out_of_reach";
    public static final String RESULT_GUI_OPEN = "gui_open";
    public static final String RESULT_NOT_USING = "not_using";

    /** The {@code effect} (evidence) vocabulary. */
    public static final String EFFECT_SCREEN = "screen";
    public static final String EFFECT_BLOCK_CHANGED = "block_changed";
    public static final String EFFECT_NONE_VISIBLE = "none_visible";

    /** The {@code interaction} vocabulary (lower-cased {@code InteractionResult} names). */
    public static final String INTERACTION_SUCCESS = "success";
    public static final String INTERACTION_CONSUME = "consume";
    public static final String INTERACTION_PASS = "pass";
    public static final String INTERACTION_FAIL = "fail";

    /** True for the two answers that mean "the game took the click". */
    public static boolean consumed(String interaction) {
        return INTERACTION_SUCCESS.equals(interaction) || INTERACTION_CONSUME.equals(interaction);
    }

    /**
     * The verdict for a use that ran out of time with nothing observable: a
     * CONSUMED interaction still counts as {@code used} (with {@code
     * effect=none_visible}) because the game itself said it handled the
     * click; only an unconsumed one is an honest {@code no_effect}.
     */
    public static String timeoutVerdict(String interaction) {
        return consumed(interaction) ? RESULT_USED : RESULT_NO_EFFECT;
    }

    /**
     * Assembles the {@code use} response:
     * {@code {"in_game":true,"result":...,"at":{x,y,z},"face":"up",
     * "block":"minecraft:chest","hand":"main","elapsed_ms":...}} plus
     * {@code effect} + {@code interaction} when a use was actually sent,
     * {@code screen_class} for {@code screen_opened}, and {@code reason} for
     * the refusals/aborts. {@code block} is JSON null when the cell was empty
     * or unknown.
     */
    public static JsonObject useResult(UseParams p, String result, String block,
                                       PlaceContracts.Face resolvedFace, String hand,
                                       String effect, String interaction, String screenClass,
                                       long elapsedMs, String reason) {
        JsonObject out = new JsonObject();
        out.addProperty("in_game", true);
        out.addProperty("result", result);
        out.add("at", PlaceContracts.cellJson(p.target()));
        out.add("block", block != null ? new JsonPrimitive(block) : JsonNull.INSTANCE);
        if (resolvedFace != null) {
            out.addProperty("face", resolvedFace.wireName());
        }
        out.addProperty("hand", hand);
        out.addProperty("elapsed_ms", elapsedMs);
        if (effect != null) {
            out.addProperty("effect", effect);
        }
        if (interaction != null) {
            out.addProperty("interaction", interaction);
        }
        if (screenClass != null) {
            out.addProperty("screen_class", screenClass);
        }
        if (reason != null) {
            out.addProperty("reason", reason);
        }
        return out;
    }

    // ------------------------------------------------------------------ monitor state machine

    /** What the shell should do with the crosshair/right button this tick. */
    public enum Action {
        /** Aiming not done (or waiting for the evidence); nothing to send. */
        WAIT,
        /** Aim landed - send the use packet now (once per call). */
        PRESS,
        /** Terminal: release nothing (no button is held), stop monitoring. */
        FINISH
    }

    /** One tick's monitor verdict: an action, the terminal result and its evidence. */
    public record Verdict(Action action, String result, String effect) {
        static Verdict of(Action action) {
            return new Verdict(action, null, null);
        }

        boolean terminal() {
            return action == Action.FINISH;
        }
    }

    /** The per-tick observation the shell extracts on the main thread. */
    public record TickView(boolean aimed, boolean pressed, boolean screenOpen,
                           boolean blockChanged, String interaction, long elapsedMs) {
    }

    /**
     * The pure use monitor: fed one {@link TickView} per client tick (main
     * thread); answers {@link Action#PRESS} once, then the terminal verdict.
     *
     * <p>Precedence is deliberate: a screen opening or the block's state
     * flipping is EVIDENCE and outranks the timeout (a slow server that
     * answers at 2001 ms still succeeded); {@code FAIL} is the only
     * immediate refusal; a timeout with a consumed interaction is still a
     * use (see {@link #timeoutVerdict}).
     */
    public static final class UseMonitor {

        private final int timeoutMs;
        private int aimTicks;

        public UseMonitor(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        /** One tick; pure except for its own aim counter - fully smoke-testable. */
        public Verdict tick(TickView view) {
            if (view.pressed() && view.screenOpen()) {
                return new Verdict(Action.FINISH, RESULT_SCREEN_OPENED, EFFECT_SCREEN);
            }
            if (view.pressed() && view.blockChanged()) {
                return new Verdict(Action.FINISH, RESULT_USED, EFFECT_BLOCK_CHANGED);
            }
            if (view.pressed() && INTERACTION_FAIL.equals(view.interaction())) {
                return new Verdict(Action.FINISH, RESULT_REJECTED, null);
            }
            if (view.elapsedMs() >= timeoutMs) {
                if (!view.pressed()) {
                    // Never even reached the press: the aim owns the failure.
                    return new Verdict(Action.FINISH, RESULT_NOT_USING, null);
                }
                return new Verdict(Action.FINISH, timeoutVerdict(view.interaction()),
                        consumed(view.interaction()) ? EFFECT_NONE_VISIBLE : null);
            }
            if (!view.aimed()) {
                if (++aimTicks >= AIM_STALL_TICKS) {
                    return new Verdict(Action.FINISH, RESULT_NOT_USING, null);
                }
                return Verdict.of(Action.WAIT);
            }
            if (!view.pressed()) {
                return Verdict.of(Action.PRESS);
            }
            return Verdict.of(Action.WAIT);
        }
    }

    /** {@code Face} wire helper for the audit line ("auto" when unresolved). */
    public static String faceName(PlaceContracts.Face face) {
        return face == null ? "auto" : face.wireName().toLowerCase(Locale.ROOT);
    }
}
