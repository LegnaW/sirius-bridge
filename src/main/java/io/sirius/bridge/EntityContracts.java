package io.sirius.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Locale;

/**
 * Frozen-contract logic for the M5.0 v1.6 entity primitives
 * ({@code entity.use} / {@code entity.attack}): parameter validation, the
 * shared target-resolution rule, the reach test, both tick monitors, the
 * result vocabularies and response assembly. Pure Gson + JDK only - no
 * Minecraft classes - so the smoke test pins all of it without a game.
 *
 * <p><b>Key vanilla fact this tool leans on</b>: the SERVER validates only the
 * eye-to-collision-box DISTANCE (3 blocks), never the view direction. The
 * crosshair only decides what the CLIENT would pick. So aiming is cosmetic
 * here: we still turn the head (it looks right and a few mods read the look
 * vector), but a hit does not depend on it - which is exactly why
 * {@code lookAt + input.click} was a bad way to pet a horse.
 *
 * <p><b>Target resolution</b> (both tools, one rule): {@code uuid} first, then
 * {@code name} (exact, then case-insensitive substring), else the NEAREST
 * entity within {@code radius} (default 6). Nothing found -> {@code gone} with
 * the searched radius in the reason.
 */
public final class EntityContracts {

    private EntityContracts() {
    }

    // ------------------------------------------------------------------ constants

    /** Default search radius (blocks). */
    public static final double DEFAULT_RADIUS = 6.0;
    /** Schema maximum for {@code radius}. */
    public static final double MAX_RADIUS = 64.0;

    /** Vanilla interaction reach: eye to the entity's bounding box. */
    public static final double REACH = 3.0;

    /** {@code wait_cooldown}: attack at >= 90% charge (below that vanilla scales the damage). */
    public static final float ATTACK_CHARGE_THRESHOLD = 0.9F;

    /** A press needs this long for a server round trip to turn into a screen. */
    public static final long SCREEN_GRACE_MS = 250L;

    public static final int DEFAULT_TIMEOUT_MS = 2_000;
    public static final int MIN_TIMEOUT_MS = 200;
    public static final int MAX_TIMEOUT_MS = 10_000;

    public static final int DEFAULT_ATTACK_INTERVAL_MS = 600;
    public static final int MAX_INTERVAL_MS = 5_000;
    public static final int MAX_COUNT = 10;

    /** One game tick in milliseconds (the cooldown accounting unit). */
    public static final long TICK_MS = 50L;

    // ------------------------------------------------------------------ result vocabulary

    public static final String RESULT_USED = "used";
    public static final String RESULT_SCREEN_OPENED = "screen_opened";
    public static final String RESULT_NO_EFFECT = "no_effect";
    public static final String RESULT_OUT_OF_RANGE = "out_of_range";
    public static final String RESULT_GONE = "gone";
    public static final String RESULT_GUI_OPEN = "gui_open";
    public static final String RESULT_NOT_IN_GAME = "not_in_game";
    public static final String RESULT_HIT = "hit";
    public static final String RESULT_DEAD = "dead";

    // ------------------------------------------------------------------ params

    /** Validated {@code entity.use} params. */
    public record EntityUseParams(String uuid, String name, double radius,
                                  UseContracts.Hand hand, boolean atCenter, int timeoutMs) {
    }

    /** Validated {@code entity.attack} params. */
    public record EntityAttackParams(String uuid, String name, double radius,
                                     int count, int intervalMs, boolean waitCooldown) {
    }

    /** Validates {@code entity.use} params per the frozen schema. */
    public static EntityUseParams entityUseParams(JsonObject params) throws ToolContracts.InvalidParams {
        String uuid = optionalText(params, "uuid", "entity.use");
        String name = optionalText(params, "name", "entity.use");
        double radius = radius(params, "entity.use");
        UseContracts.Hand hand = UseContracts.Hand.MAIN;
        JsonElement handElement = params.get("hand");
        if (handElement != null && !handElement.isJsonNull()) {
            if (!handElement.isJsonPrimitive() || !handElement.getAsJsonPrimitive().isString()) {
                throw new ToolContracts.InvalidParams("entity.use hand must be a string, one of "
                        + UseContracts.Hand.allowed());
            }
            String raw = handElement.getAsString();
            hand = UseContracts.Hand.parse(raw);
            if (hand == null) {
                throw new ToolContracts.InvalidParams("entity.use hand must be one of "
                        + UseContracts.Hand.allowed() + ", got: \"" + raw + "\"");
            }
        }
        boolean atCenter = false; // "auto" (the default) = gameMode.interact
        JsonElement atElement = params.get("at");
        if (atElement != null && !atElement.isJsonNull()) {
            if (!atElement.isJsonPrimitive() || !atElement.getAsJsonPrimitive().isString()) {
                throw new ToolContracts.InvalidParams("entity.use at must be a string: \"auto\" or \"center\"");
            }
            String raw = atElement.getAsString();
            if ("center".equals(raw)) {
                atCenter = true;
            } else if (!"auto".equals(raw)) {
                throw new ToolContracts.InvalidParams("entity.use at must be \"auto\" (default,"
                        + " gameMode.interact) or \"center\" (interactAt the box centre), got: \"" + raw + "\"");
            }
        }
        int timeoutMs = ToolContracts.integerMember(params, "timeout_ms", "entity.use",
                MIN_TIMEOUT_MS, MAX_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
        return new EntityUseParams(uuid, name, radius, hand, atCenter, timeoutMs);
    }

    /** Validates {@code entity.attack} params per the frozen schema. */
    public static EntityAttackParams entityAttackParams(JsonObject params) throws ToolContracts.InvalidParams {
        String uuid = optionalText(params, "uuid", "entity.attack");
        String name = optionalText(params, "name", "entity.attack");
        double radius = radius(params, "entity.attack");
        int count = ToolContracts.integerMember(params, "count", "entity.attack", 1, MAX_COUNT, 1);
        int intervalMs = ToolContracts.integerMember(params, "interval_ms", "entity.attack",
                0, MAX_INTERVAL_MS, DEFAULT_ATTACK_INTERVAL_MS);
        boolean waitCooldown = true; // the schema default: better damage, no attack-rate penalty
        JsonElement waitElement = params.get("wait_cooldown");
        if (waitElement != null && !waitElement.isJsonNull()) {
            if (!waitElement.isJsonPrimitive() || !waitElement.getAsJsonPrimitive().isBoolean()) {
                throw new ToolContracts.InvalidParams("entity.attack wait_cooldown must be a boolean");
            }
            waitCooldown = waitElement.getAsBoolean();
        }
        return new EntityAttackParams(uuid, name, radius, count, intervalMs, waitCooldown);
    }

    /** Optional non-blank string member (absent/null/blank -> null = "not given"). */
    private static String optionalText(JsonObject params, String member, String tool)
            throws ToolContracts.InvalidParams {
        JsonElement e = params.get(member);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new ToolContracts.InvalidParams(tool + " " + member + " must be a string");
        }
        String raw = e.getAsString();
        return raw.isBlank() ? null : raw;
    }

    private static double radius(JsonObject params, String tool) throws ToolContracts.InvalidParams {
        double radius = DEFAULT_RADIUS;
        JsonElement e = params.get("radius");
        if (e != null && !e.isJsonNull()) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                throw new ToolContracts.InvalidParams(tool + " radius must be a positive number of blocks");
            }
            radius = e.getAsDouble();
            if (!Double.isFinite(radius) || radius <= 0 || radius > MAX_RADIUS) {
                throw new ToolContracts.InvalidParams(tool + " radius must be within (0, "
                        + (int) MAX_RADIUS + "] blocks, got: " + radius);
            }
        }
        return radius;
    }

    // ------------------------------------------------------------------ target resolution

    /** One entity the shell could see: identity + its eye-to-box distance. */
    public record Candidate(String uuid, String name, double distance) {
    }

    /**
     * Index of the entity the caller asked for, or {@code -1} (-> {@code gone}).
     *
     * <p>Only candidates within {@code radius} are eligible (all three modes -
     * the radius is "how far the agent is willing to look"). {@code uuid} is
     * matched case-insensitively (UUIDs are hex; clients format them
     * lowercase, brains sometimes uppercase). {@code name} prefers an EXACT
     * case-sensitive match over a case-insensitive substring, so "Cow" does not
     * accidentally pick "Cowtipper". No selector at all -> nearest eligible.
     */
    public static int resolve(List<Candidate> candidates, String uuid, String name, double radius) {
        if (candidates == null || candidates.isEmpty()) {
            return -1;
        }
        if (uuid != null && !uuid.isBlank()) {
            for (int i = 0; i < candidates.size(); i++) {
                Candidate candidate = candidates.get(i);
                if (candidate.distance() > radius) {
                    continue;
                }
                if (candidate.uuid() != null && candidate.uuid().equalsIgnoreCase(uuid.trim())) {
                    return i;
                }
            }
            return -1;
        }
        if (name != null && !name.isBlank()) {
            String needle = name.trim();
            for (int i = 0; i < candidates.size(); i++) {
                Candidate candidate = candidates.get(i);
                if (candidate.distance() <= radius && needle.equals(candidate.name())) {
                    return i; // exact match wins
                }
            }
            String lower = needle.toLowerCase(Locale.ROOT);
            for (int i = 0; i < candidates.size(); i++) {
                Candidate candidate = candidates.get(i);
                if (candidate.distance() > radius || candidate.name() == null) {
                    continue;
                }
                if (candidate.name().toLowerCase(Locale.ROOT).contains(lower)) {
                    return i; // substring, first one in the caller's order
                }
            }
            return -1;
        }
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (candidate.distance() > radius || candidate.distance() >= bestDistance) {
                continue;
            }
            best = i;
            bestDistance = candidate.distance();
        }
        return best;
    }

    // ------------------------------------------------------------------ reach geometry

    /**
     * Distance from the eye to an axis-aligned box - vanilla's own reach test
     * ({@code AABB.distanceToSqr}): zero inside the box, otherwise the clamped
     * Euclidean distance to the nearest face/edge/corner. Reimplemented in
     * doubles so the reach rule is smoke-testable.
     */
    public static double distanceToBox(double eyeX, double eyeY, double eyeZ,
                                       double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        double dx = Math.max(Math.max(minX - eyeX, 0.0), eyeX - maxX);
        double dy = Math.max(Math.max(minY - eyeY, 0.0), eyeY - maxY);
        double dz = Math.max(Math.max(minZ - eyeZ, 0.0), eyeZ - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Whether a measured distance is inside the vanilla interaction reach. */
    public static boolean inReach(double distance) {
        return distance <= REACH;
    }

    /** {@code -32602}/{@code out_of_range} teaching text: how far the target is and how far reach goes. */
    public static String outOfRangeMessage(String label, double distance) {
        return label + " is " + format(distance) + " blocks away (eye to collision box) - beyond the "
                + format(REACH) + "-block reach, still " + format(distance - REACH)
                + " blocks too far. Walk closer first, then retry";
    }

    /** Two-decimal formatting without locale surprises (and never NaN). */
    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    // ------------------------------------------------------------------ use monitor

    /** What the shell should do this tick. */
    public enum Action {
        /** Send {@code gameMode.useItem}/{@code interact} now. */
        USE,
        /** Send {@code gameMode.attack} now. */
        ATTACK,
        /** Nothing yet (waiting for the screen grace / the cooldown). */
        WAIT,
        /** Terminal: release the waiter with {@link Verdict#result()}. */
        FINISH
    }

    /** One tick's decision. */
    public record Verdict(Action action, String result) {
        static Verdict of(Action action) {
            return new Verdict(action, null);
        }

        public boolean terminal() {
            return action == Action.FINISH;
        }
    }

    /** The per-tick observation for {@code entity.use}. */
    public record UseView(boolean inGame, boolean screenOpen, boolean pressed, boolean entityPresent,
                          boolean entityAlive, String interaction, long elapsedMs) {
    }

    /**
     * The {@code entity.use} monitor: press once, then decide from evidence.
     *
     * <p>Precedence: left the world -> a screen (the answer: villager trade, horse,
     * chest-boat) -> the entity vanished/died -> an unconsumed interaction
     * ({@code no_effect}) -> the screen grace period after a consumed press
     * ({@code used}) -> the timeout.
     */
    public static final class UseMonitor {

        private final int timeoutMs;

        public UseMonitor(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Verdict tick(UseView view) {
            if (!view.inGame()) {
                return new Verdict(Action.FINISH, RESULT_NOT_IN_GAME);
            }
            if (!view.pressed()) {
                return Verdict.of(Action.USE);
            }
            if (view.screenOpen()) {
                return new Verdict(Action.FINISH, RESULT_SCREEN_OPENED);
            }
            if (!view.entityPresent()) {
                return new Verdict(Action.FINISH, RESULT_GONE);
            }
            if (UseContracts.INTERACTION_PASS.equals(view.interaction())
                    || UseContracts.INTERACTION_FAIL.equals(view.interaction())) {
                return new Verdict(Action.FINISH, RESULT_NO_EFFECT);
            }
            if (UseContracts.consumed(view.interaction()) && view.elapsedMs() >= SCREEN_GRACE_MS) {
                // A consumed interaction with no screen: done (feeding, milking,
                // shearing, mounting). The grace window exists because the screen
                // of a trade/horse arrives one server round trip later.
                return new Verdict(Action.FINISH, RESULT_USED);
            }
            if (view.elapsedMs() >= timeoutMs) {
                return new Verdict(Action.FINISH,
                        UseContracts.consumed(view.interaction()) ? RESULT_USED : RESULT_NO_EFFECT);
            }
            return Verdict.of(Action.WAIT);
        }
    }

    // ------------------------------------------------------------------ attack monitor

    /** The per-tick observation for {@code entity.attack}. */
    public record AttackView(boolean inGame, boolean entityPresent, boolean entityAlive, boolean inReach,
                             float attackStrength, long elapsedMs) {
    }

    /**
     * The {@code entity.attack} pacer: {@code count} attacks spaced by
     * {@code interval_ms}, each gated on the attack cooldown while
     * {@code wait_cooldown} is on ({@code getAttackStrengthScale(0) >= 0.9}).
     */
    public static final class AttackMonitor {

        private final int count;
        private final int intervalMs;
        private final boolean waitCooldown;
        private int hits;
        private long nextAtMs;
        private long cooldownWaitedMs;

        public AttackMonitor(EntityAttackParams p) {
            this.count = p.count();
            this.intervalMs = p.intervalMs();
            this.waitCooldown = p.waitCooldown();
        }

        public Verdict tick(AttackView view) {
            if (!view.inGame()) {
                return new Verdict(Action.FINISH, RESULT_NOT_IN_GAME);
            }
            if (!view.entityPresent()) {
                return new Verdict(Action.FINISH, RESULT_GONE);
            }
            if (!view.entityAlive()) {
                return new Verdict(Action.FINISH, RESULT_DEAD);
            }
            if (hits >= count) {
                return new Verdict(Action.FINISH, RESULT_HIT);
            }
            if (!view.inReach()) {
                return new Verdict(Action.FINISH, RESULT_OUT_OF_RANGE);
            }
            if (view.elapsedMs() < nextAtMs) {
                return Verdict.of(Action.WAIT);
            }
            if (waitCooldown && view.attackStrength() < ATTACK_CHARGE_THRESHOLD) {
                cooldownWaitedMs += TICK_MS; // honest accounting: one game tick spent waiting
                return Verdict.of(Action.WAIT);
            }
            return Verdict.of(Action.ATTACK);
        }

        /** Records one delivered attack and schedules the next one. */
        public void noteAttack(long elapsedMs) {
            hits++;
            nextAtMs = elapsedMs + intervalMs;
        }

        public int hits() {
            return hits;
        }

        /** Scheduled attacks that were never delivered (entity died / left / out of reach). */
        public int miss() {
            return Math.max(0, count - hits);
        }

        public long cooldownWaitedMs() {
            return cooldownWaitedMs;
        }
    }

    // ------------------------------------------------------------------ results

    /** Identity/position facts of the resolved entity, for the response. */
    public record EntityFacts(String name, String type, String uuid, double distance) {
    }

    /** {@code {name,type,uuid,distance}} (null fields become JSON null). */
    public static JsonObject entityFactsJson(EntityFacts facts) {
        JsonObject out = new JsonObject();
        out.add("name", facts.name() != null ? new JsonPrimitive(facts.name()) : JsonNull.INSTANCE);
        out.add("type", facts.type() != null ? new JsonPrimitive(facts.type()) : JsonNull.INSTANCE);
        out.add("uuid", facts.uuid() != null ? new JsonPrimitive(facts.uuid()) : JsonNull.INSTANCE);
        out.addProperty("distance", Double.isFinite(facts.distance()) ? facts.distance() : 0.0);
        return out;
    }

    /** {@code entity.use} response. {@code facts} is null when nothing was resolved ({@code gone}). */
    public static JsonObject entityUseResult(String result, EntityFacts facts, String interaction,
                                            String screenClass, long elapsedMs, String reason) {
        JsonObject out = new JsonObject();
        out.addProperty("in_game", true);
        out.addProperty("result", result);
        out.add("entity", facts != null ? entityFactsJson(facts) : JsonNull.INSTANCE);
        out.addProperty("elapsed_ms", elapsedMs);
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

    /** {@code entity.attack} response. */
    public static JsonObject entityAttackResult(String result, EntityFacts facts, int hits, int miss,
                                                long cooldownWaitedMs, String reason) {
        JsonObject out = new JsonObject();
        out.addProperty("in_game", true);
        out.addProperty("result", result);
        out.add("entity", facts != null ? entityFactsJson(facts) : JsonNull.INSTANCE);
        out.addProperty("hits", hits);
        out.addProperty("miss", miss);
        out.addProperty("cooldown_waited_ms", cooldownWaitedMs);
        if (reason != null) {
            out.addProperty("reason", reason);
        }
        return out;
    }
}
