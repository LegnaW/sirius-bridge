package io.sirius.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

/**
 * Frozen-contract logic for the M4.4 v1.4 {@code place} tool: parameter
 * validation, the support/hit-point geometry, the {@code result} vocabulary
 * and response assembly. Pure Gson + JDK only - no Minecraft classes - so the
 * smoke test covers the whole decision surface without a game.
 *
 * <p><b>Why no {@code BlockPos}/{@code Direction} here</b> (the spec skeleton
 * sketched them): the contract half must stay loadable in the in-process smoke
 * test, whose runtime classpath carries gson only, not the Minecraft
 * artifacts. The geometry is expressed over {@link Cell} (integer block
 * coordinates) and {@link Hit} (a world point); the shell ({@code PlaceTools})
 * converts to {@code BlockPos}/{@code Vec3}/{@code Direction} on the main
 * thread. The MATH is identical to the spec's sketch, and the smoke test now
 * pins it with hand-computed cases.
 *
 * <p><b>Geometry (the part that must never be confused)</b>:
 * {@code support = target.relative(face)} - {@code face} names where the
 * SUPPORT block sits relative to the target cell. The clicked face is the one
 * of the support block that POINTS AT the target, i.e.
 * {@code face.getOpposite()}, and the hit point is the support block's centre
 * pushed 0.5 out along that face. {@code face="down"} therefore means "support
 * below, click its UP face, the new block lands on top of it" - the most
 * common case (placing onto the ground) and the default.
 */
public final class PlaceContracts {

    private PlaceContracts() {
    }

    // ------------------------------------------------------------------ constants

    /** Default {@code timeout_ms}: placing is ONE interaction, unlike dig's multi-second hold. */
    public static final int DEFAULT_TIMEOUT_MS = 3_000;
    public static final int MIN_TIMEOUT_MS = 600;
    public static final int MAX_TIMEOUT_MS = 10_000;

    /** Reach: eye position to the HIT POINT (vanilla survival 4.5, same budget as dig). */
    public static final double REACH = 4.5;

    /**
     * Aim speed while placing (deg/s). Same 300 deg/s as dig's aim: placing is
     * orientation-sensitive (stairs/doors/beds/signs take their facing from the
     * player's yaw), so the view must LAND on the support face before the use
     * packet goes out - snapping is what the dig aim already proved reliable.
     */
    public static final double AIM_TURN_SPEED_DEG_S = 300.0;

    /** Aim stall budget: 100 ticks (5 s) without convergence -> {@code not_placing}. */
    public static final int AIM_STALL_TICKS = 100;

    /** Aim tolerance: the view counts as "on the hit point" within this many degrees per axis. */
    public static final double AIM_TOLERANCE_DEG = 2.0;

    /** Max length of an {@code item} entry, in chars (mirrors the world.query filter limit). */
    public static final int MAX_ITEM_CHARS = 128;

    /**
     * Shape of an item id: an optional {@code [a-z0-9_.-]+:} namespace followed
     * by a non-empty {@code [a-z0-9_./-]+} path - the charset
     * {@code ResourceLocation} accepts, so a validated id can always be
     * resolved in-game without a runtime exception.
     */
    private static final java.util.regex.Pattern ITEM_ID =
            java.util.regex.Pattern.compile("[a-z0-9_.-]+(?::[a-z0-9_./-]+)?");

    // ------------------------------------------------------------------ block faces

    /**
     * A block face in protocol spelling. Deliberately its own enum instead of
     * {@code net.minecraft.core.Direction}: the contract half must stay free of
     * Minecraft classes (see the class comment), and the wire names are the
     * frozen schema's {@code enum} values, not an implementation detail.
     */
    public enum Face {
        UP(0, 1, 0),
        DOWN(0, -1, 0),
        NORTH(0, 0, -1),
        SOUTH(0, 0, 1),
        EAST(1, 0, 0),
        WEST(-1, 0, 0);

        private final int stepX;
        private final int stepY;
        private final int stepZ;

        Face(int stepX, int stepY, int stepZ) {
            this.stepX = stepX;
            this.stepY = stepY;
            this.stepZ = stepZ;
        }

        /** Protocol spelling ("down", "east", ...), identical to {@code Direction.byName}. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public int stepX() {
            return stepX;
        }

        public int stepY() {
            return stepY;
        }

        public int stepZ() {
            return stepZ;
        }

        /** The face pointing the other way - the face of the SUPPORT block we click. */
        public Face opposite() {
            return switch (this) {
                case UP -> DOWN;
                case DOWN -> UP;
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case EAST -> WEST;
                case WEST -> EAST;
            };
        }

        /** Parses the protocol spelling; {@code null} for anything else (case-sensitive). */
        public static Face parse(String raw) {
            if (raw == null) {
                return null;
            }
            for (Face face : values()) {
                if (face.wireName().equals(raw)) {
                    return face;
                }
            }
            return null;
        }

        /** The accepted values, for {@code -32602} messages. */
        public static String allowed() {
            return "up/down/north/south/east/west";
        }
    }

    /** Integer block coordinates (the pure stand-in for {@code BlockPos}). */
    public record Cell(int x, int y, int z) {

        /** This cell shifted one block along {@code face}. */
        public Cell relative(Face face) {
            return new Cell(x + face.stepX(), y + face.stepY(), z + face.stepZ());
        }

        public String shortString() {
            return x + "," + y + "," + z;
        }
    }

    /** A world-space point (the pure stand-in for {@code Vec3}). */
    public record Hit(double x, double y, double z) {
    }

    // ------------------------------------------------------------------ params

    /** Validated {@code place} params: target cell + support face + optional item/timeout. */
    public record PlaceParams(int x, int y, int z, Face face, String item, int timeoutMs) {

        public Cell target() {
            return new Cell(x, y, z);
        }
    }

    /**
     * Validates {@code place} params per the frozen schema: REQUIRED integer
     * {@code x}/{@code y}/{@code z}; optional {@code face} (default
     * {@code "down"}, anything but the six wire names is a {@code -32602} that
     * lists them); optional {@code item} (registry id, bare paths get the
     * {@code minecraft:} prefix, {@code #tags} are refused because a place
     * needs ONE concrete item); optional {@code timeout_ms} 600..10000
     * (default 3000).
     */
    public static PlaceParams placeParams(JsonObject params) throws ToolContracts.InvalidParams {
        int x = blockCoord(params, "x");
        int y = blockCoord(params, "y");
        int z = blockCoord(params, "z");

        Face face = Face.DOWN;
        JsonElement faceElement = params.get("face");
        if (faceElement != null && !faceElement.isJsonNull()) {
            if (!faceElement.isJsonPrimitive() || !faceElement.getAsJsonPrimitive().isString()) {
                throw new ToolContracts.InvalidParams("place face must be a string, one of "
                        + Face.allowed());
            }
            String raw = faceElement.getAsString();
            Face parsed = Face.parse(raw);
            if (parsed == null) {
                throw new ToolContracts.InvalidParams("place face must be one of " + Face.allowed()
                        + " (the direction of the SUPPORT block relative to the target, e.g."
                        + " \"down\" = place on top of the block below), got: \"" + raw + "\"");
            }
            face = parsed;
        }

        return new PlaceParams(x, y, z, face, item(params), timeoutMs(params));
    }

    private static int blockCoord(JsonObject params, String member) throws ToolContracts.InvalidParams {
        JsonElement e = params.get(member);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new ToolContracts.InvalidParams("place requires integer block coordinate " + member);
        }
        double v = e.getAsDouble();
        if (!Double.isFinite(v) || v != Math.floor(v)) {
            throw new ToolContracts.InvalidParams("place " + member
                    + " must be an integer block coordinate, got: " + v);
        }
        if (v < -30_000_000.0 || v > 30_000_000.0) {
            throw new ToolContracts.InvalidParams("place " + member
                    + " is outside the world border: " + (long) v);
        }
        return (int) v;
    }

    /**
     * Optional {@code item}: normalized to a full registry id ({@code oak_planks}
     * -> {@code minecraft:oak_planks}). {@code null} = "whatever is in the
     * current hand", the backward-friendly default.
     */
    private static String item(JsonObject params) throws ToolContracts.InvalidParams {
        JsonElement e = params.get("item");
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new ToolContracts.InvalidParams("place item must be a string registry id"
                    + " (e.g. \"oak_planks\" or \"minecraft:oak_planks\")");
        }
        String raw = e.getAsString();
        if (raw.length() > MAX_ITEM_CHARS) {
            throw new ToolContracts.InvalidParams("place item must be at most " + MAX_ITEM_CHARS
                    + " chars, got: " + raw.length());
        }
        if (raw.startsWith("#")) {
            // A place needs ONE concrete item; a tag would mean "any of these",
            // which the tool cannot resolve into a hotbar slot deterministically.
            throw new ToolContracts.InvalidParams("place item must be a concrete item id, not a"
                    + " #tag (got: \"" + raw + "\") - pick the exact block item, e.g."
                    + " \"minecraft:oak_planks\"");
        }
        if (!ITEM_ID.matcher(raw).matches()) {
            throw new ToolContracts.InvalidParams("place item \"" + raw + "\" is not a valid item id"
                    + " (expected lowercase [a-z0-9_.-] with optional namespace, e.g. \"oak_planks\")");
        }
        return raw.indexOf(':') >= 0 ? raw : "minecraft:" + raw;
    }

    private static int timeoutMs(JsonObject params) throws ToolContracts.InvalidParams {
        JsonElement e = params.get("timeout_ms");
        if (e == null || e.isJsonNull()) {
            return DEFAULT_TIMEOUT_MS;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new ToolContracts.InvalidParams("place timeout_ms must be an integer "
                    + MIN_TIMEOUT_MS + ".." + MAX_TIMEOUT_MS);
        }
        double v = e.getAsDouble();
        if (!Double.isFinite(v) || v != Math.floor(v)) {
            throw new ToolContracts.InvalidParams("place timeout_ms must be an integer "
                    + MIN_TIMEOUT_MS + ".." + MAX_TIMEOUT_MS + ", got: " + v);
        }
        if (v < MIN_TIMEOUT_MS || v > MAX_TIMEOUT_MS) {
            throw new ToolContracts.InvalidParams("place timeout_ms must be within "
                    + MIN_TIMEOUT_MS + ".." + MAX_TIMEOUT_MS + ", got: " + (long) v);
        }
        return (int) v;
    }

    // ------------------------------------------------------------------ geometry

    /** The support cell: {@code target.relative(face)} (face = where the support sits). */
    public static Cell supportOf(Cell target, Face face) {
        return target.relative(face);
    }

    /**
     * The click point: the SUPPORT block's centre pushed 0.5 out along
     * {@code hitFace} - i.e. the middle of the face the block is clicked on.
     * {@code hitFace} is the {@link Face#opposite()} of the request's
     * {@code face}, because that is the side of the support block facing the
     * target (see {@link #aimPoint}).
     */
    public static Hit hitPoint(Cell support, Face hitFace) {
        return new Hit(
                support.x() + 0.5 + hitFace.stepX() * 0.5,
                support.y() + 0.5 + hitFace.stepY() * 0.5,
                support.z() + 0.5 + hitFace.stepZ() * 0.5);
    }

    /** Convenience: the hit point for a whole (target, face) request - what the aim turns to. */
    public static Hit aimPoint(Cell target, Face face) {
        return hitPoint(supportOf(target, face), face.opposite());
    }

    /** Eye-to-hit-point distance beyond {@link #REACH} (the {@code -32602} teaching text). */
    public static String outOfReachMessage(Cell target, Face face, String distance) {
        Cell support = supportOf(target, face);
        return "place hit point (face " + face.wireName() + " of support block "
                + support.shortString() + ", target " + target.shortString() + ") is " + distance
                + " blocks away - beyond the " + REACH + "-block reach. Walk closer first"
                + " (e.g. walkTo a spot ~1.5 blocks beside the target), then place again";
    }

    // ------------------------------------------------------------------ results

    /** The {@code result} vocabulary; the brain maps each to a teaching phrase. */
    public static final String RESULT_PLACED = "placed";
    public static final String RESULT_ALREADY_PLACED = "already_placed";
    public static final String RESULT_NO_ITEM = "no_item";
    public static final String RESULT_NOT_IN_HOTBAR = "not_in_hotbar";
    public static final String RESULT_BLOCKED = "blocked";
    public static final String RESULT_SUPPORT_MISSING = "support_missing";
    public static final String RESULT_OUT_OF_REACH = "out_of_reach";
    public static final String RESULT_GUI_OPEN = "gui_open";
    public static final String RESULT_REJECTED_BY_SERVER = "rejected_by_server";
    public static final String RESULT_TIMEOUT = "timeout";
    public static final String RESULT_NOT_PLACING = "not_placing";

    /**
     * The verdict for a place that ran out of time. Distinguishing the two cases
     * matters for teaching: a target that is STILL replaceable means the use
     * never landed at all ({@code not_placing} - aim/timing/hand item problem),
     * while a target that is no longer replaceable means something else ended
     * up there ({@code timeout} - the server took a different block than ours).
     */
    public static String timeoutVerdict(boolean targetStillReplaceable) {
        return targetStillReplaceable ? RESULT_NOT_PLACING : RESULT_TIMEOUT;
    }

    /**
     * Assembles the {@code place} response:
     * {@code {"in_game":true,"result":...,"block":...,"at":{x,y,z},"face":"down",
     * "support":{x,y,z},"elapsed_ms":...}} plus {@code "slot"} (the hotbar slot
     * actually used) and {@code "reason"} (human-readable detail for
     * blocked/support_missing/aborts). {@code block} is JSON null when unknown
     * (nothing placed and no item resolved).
     */
    public static JsonObject placeResult(PlaceParams p, String result, String block, long elapsedMs,
                                         Integer slot, String reason) {
        Cell target = p.target();
        Cell support = supportOf(target, p.face());
        JsonObject out = new JsonObject();
        out.addProperty("in_game", true);
        out.addProperty("result", result);
        out.add("block", block != null ? new JsonPrimitive(block) : JsonNull.INSTANCE);
        out.add("at", cellJson(target));
        out.addProperty("face", p.face().wireName());
        out.add("support", cellJson(support));
        out.addProperty("elapsed_ms", elapsedMs);
        if (slot != null) {
            out.addProperty("slot", slot);
        }
        if (reason != null) {
            out.addProperty("reason", reason);
        }
        return out;
    }

    /** {@code {x,y,z}} object for a cell. */
    public static JsonObject cellJson(Cell cell) {
        JsonObject out = new JsonObject();
        out.addProperty("x", cell.x());
        out.addProperty("y", cell.y());
        out.addProperty("z", cell.z());
        return out;
    }
}
