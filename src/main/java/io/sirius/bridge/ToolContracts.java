package io.sirius.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Frozen-contract logic for the three M1-C perception tools: parameter
 * validation (mirroring the JSON schemas in {@code schema/tools})
 * and response assembly. Pure Gson + JDK only - no Minecraft classes - so the
 * whole thing is covered by the in-process smoke test ({@code SmokeMain}).
 *
 * <p>Validation failures throw {@link InvalidParams}; the tool shell maps
 * that to a {@code -32602} response.
 */
public final class ToolContracts {

    private ToolContracts() {
    }

    /** A schema violation in tool params; message goes into the -32602 response. */
    public static final class InvalidParams extends Exception {
        InvalidParams(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ params

    /** Validated {@code screenshot} params. {@code bbox} is [x,y,w,h] or null. */
    public record ScreenshotParams(String tier, int[] bbox, int quality) {
    }

    /** Default JPEG quality when the caller does not send one. */
    public static final int DEFAULT_QUALITY = 80;

    /**
     * Validates {@code screenshot} params: {@code tier} must be
     * "full"/"crop"; {@code bbox} (when present) an array of 4 finite numbers
     * with positive w/h (required for tier "crop"); {@code quality} an
     * integer 0..100 (default 80).
     */
    public static ScreenshotParams screenshotParams(JsonObject params) throws InvalidParams {
        JsonElement tierElement = params.get("tier");
        if (tierElement == null || !tierElement.isJsonPrimitive() || !tierElement.getAsJsonPrimitive().isString()) {
            throw new InvalidParams("screenshot requires string tier: \"full\"|\"crop\"");
        }
        String tier = tierElement.getAsString();
        if (!"full".equals(tier) && !"crop".equals(tier)) {
            throw new InvalidParams("screenshot tier must be \"full\" or \"crop\", got: " + tier);
        }

        int[] bbox = null;
        JsonElement bboxElement = params.get("bbox");
        if (bboxElement != null && !bboxElement.isJsonNull()) {
            if (!bboxElement.isJsonArray() || bboxElement.getAsJsonArray().size() != 4) {
                throw new InvalidParams("bbox must be an array [x, y, w, h] of 4 numbers");
            }
            bbox = new int[4];
            for (int i = 0; i < 4; i++) {
                JsonElement e = bboxElement.getAsJsonArray().get(i);
                if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                    throw new InvalidParams("bbox[" + i + "] must be a number");
                }
                double v = e.getAsDouble();
                if (!Double.isFinite(v)) {
                    throw new InvalidParams("bbox[" + i + "] must be finite");
                }
                bbox[i] = (int) Math.round(v);
            }
            if (bbox[2] <= 0 || bbox[3] <= 0) {
                throw new InvalidParams("bbox w/h must be positive, got: ["
                        + bbox[0] + "," + bbox[1] + "," + bbox[2] + "," + bbox[3] + "]");
            }
        }
        if ("crop".equals(tier) && bbox == null) {
            throw new InvalidParams("bbox [x, y, w, h] is required when tier is \"crop\"");
        }

        int quality = DEFAULT_QUALITY;
        JsonElement qualityElement = params.get("quality");
        if (qualityElement != null && !qualityElement.isJsonNull()) {
            if (!qualityElement.isJsonPrimitive() || !qualityElement.getAsJsonPrimitive().isNumber()) {
                throw new InvalidParams("quality must be an integer 0..100");
            }
            double q = qualityElement.getAsDouble();
            if (q != Math.floor(q)) {
                throw new InvalidParams("quality must be an integer 0..100, got: " + q);
            }
            if (q < 0 || q > 100) {
                throw new InvalidParams("quality must be within 0..100, got: " + (int) q);
            }
            quality = (int) q;
        }
        return new ScreenshotParams(tier, bbox, quality);
    }

    /**
     * Validated {@code world.query} params. {@code filter} is the M3.5 v1.1
     * extension: {@code null} = absent (v1.0 behaviour); otherwise 1..
     * {@link #MAX_FILTER_ENTRIES} already-NORMALIZED entries (missing
     * namespace got {@code minecraft:} prefixed here, {@code #} tag prefix
     * kept, e.g. {@code ["minecraft:spruce_log", "#minecraft:logs"]}).
     *
     * <p>v1.6 adds the paging triple {@code cursor}/{@code limit} plus
     * {@code aggregate}: the hit sequence is always sorted by distance to the
     * player, so a cursor is literally "the next page" (see
     * {@link #scanBlocksPaged} / {@link #queryEntitiesPaged}).
     */
    public record WorldQueryParams(String type, double range, List<String> filter,
                                   int cursor, int limit, boolean aggregate) {
    }

    /** Default page size for {@code world.query} (v1.6). */
    public static final int DEFAULT_LIMIT = 128;

    /** Hard page cap for {@code world.query} (frozen schema maximum). */
    public static final int MAX_LIMIT = 512;

    /**
     * Max distinct names reported by {@code aggregate=true}. An aggregate answer
     * is a summary ("what is around me"), so the long tail of one-off block
     * types is cut instead of blowing up the response.
     */
    public static final int AGGREGATE_MAX_TYPES = 32;

    /** Default scan radius (blocks) when the caller does not send one. */
    public static final double DEFAULT_RANGE = 16;

    /** Hard cap on the scan radius - protects the response from exploding. */
    public static final double MAX_RANGE = 64;

    /** Max entries in a {@code world.query} filter array (v1.1). */
    public static final int MAX_FILTER_ENTRIES = 16;

    /** Max length of one {@code world.query} filter entry, in chars (v1.1). */
    public static final int MAX_FILTER_ENTRY_CHARS = 128;

    /**
     * Shape of one filter entry: an optional {@code #} tag marker, then a
     * registry location - a non-empty path of {@code [a-z0-9_.-/]} optionally
     * preceded by a {@code [a-z0-9_.-]+:} namespace. Mirrors the charset
     * {@code ResourceLocation} accepts, so anything that survives here can be
     * resolved in-game without a runtime exception; UPPERCASE and blank
     * entries are rejected up front with a clear message instead.
     */
    private static final java.util.regex.Pattern FILTER_ENTRY =
            java.util.regex.Pattern.compile("[a-z0-9_.-]+(?::[a-z0-9_./-]+)?");

    /**
     * Validates {@code world.query} params: type "blocks"/"entities", 0 < range <= 64 (default 16);
     * optional {@code filter} array (v1.1) of 1..16 strings, each 1..128 chars
     * in registry-id or {@code #tag} form. Entries are returned NORMALIZED:
     * a missing namespace gets {@code minecraft:} prefixed ({@code spruce_log}
     * -> {@code minecraft:spruce_log}, {@code #logs} -> {@code #minecraft:logs}).
     * {@code #tag} entries are only legal for {@code type:"blocks"} - the
     * entities branch matches entity-type registry ids only.
     */
    public static WorldQueryParams worldQueryParams(JsonObject params) throws InvalidParams {
        JsonElement typeElement = params.get("type");
        if (typeElement == null || !typeElement.isJsonPrimitive() || !typeElement.getAsJsonPrimitive().isString()) {
            throw new InvalidParams("world.query requires string type: \"blocks\"|\"entities\"");
        }
        String type = typeElement.getAsString();
        if (!"blocks".equals(type) && !"entities".equals(type)) {
            throw new InvalidParams("world.query type must be \"blocks\" or \"entities\", got: " + type);
        }

        double range = DEFAULT_RANGE;
        JsonElement rangeElement = params.get("range");
        if (rangeElement != null && !rangeElement.isJsonNull()) {
            if (!rangeElement.isJsonPrimitive() || !rangeElement.getAsJsonPrimitive().isNumber()) {
                throw new InvalidParams("range must be a positive number of blocks");
            }
            range = rangeElement.getAsDouble();
            if (!Double.isFinite(range)) {
                throw new InvalidParams("range must be finite");
            }
            if (range <= 0) {
                throw new InvalidParams("range must be > 0, got: " + range);
            }
            if (range > MAX_RANGE) {
                throw new InvalidParams("range must be <= " + (int) MAX_RANGE + " blocks, got: " + range);
            }
        }

        // v1.1 filter: null/absent keeps the v1.0 behaviour; otherwise the
        // entries are validated and normalized here so the scan side never
        // has to re-parse strings on the render thread.
        List<String> filter = null;
        JsonElement filterElement = params.get("filter");
        if (filterElement != null && !filterElement.isJsonNull()) {
            if (!filterElement.isJsonArray()) {
                throw new InvalidParams("filter must be an array of strings (registry ids like"
                        + " \"spruce_log\" or tags like \"#minecraft:logs\"), 1.." + MAX_FILTER_ENTRIES + " entries");
            }
            JsonArray entries = filterElement.getAsJsonArray();
            if (entries.size() < 1) {
                throw new InvalidParams("filter must contain at least 1 entry"
                        + " (omit filter entirely for the unfiltered v1.0 behaviour)");
            }
            if (entries.size() > MAX_FILTER_ENTRIES) {
                throw new InvalidParams("filter must contain at most " + MAX_FILTER_ENTRIES
                        + " entries, got: " + entries.size());
            }
            filter = new ArrayList<>(entries.size());
            for (JsonElement entry : entries) {
                if (entry == null || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                    throw new InvalidParams("filter entries must be strings (registry ids like"
                            + " \"spruce_log\" or tags like \"#minecraft:logs\")");
                }
                String raw = entry.getAsString();
                if (raw.length() < 1) {
                    throw new InvalidParams("filter entries must not be empty");
                }
                if (raw.length() > MAX_FILTER_ENTRY_CHARS) {
                    throw new InvalidParams("filter entries must be at most " + MAX_FILTER_ENTRY_CHARS
                            + " chars, got: " + raw.length());
                }
                boolean tag = raw.startsWith("#");
                String id = tag ? raw.substring(1) : raw;
                if (id.isEmpty() || !FILTER_ENTRY.matcher(id).matches()) {
                    throw new InvalidParams("filter entry \"" + raw + "\" is not a valid registry id or #tag"
                            + " (expected lowercase [a-z0-9_.-] with optional namespace, e.g."
                            + " \"spruce_log\", \"minecraft:spruce_log\", \"#minecraft:logs\")");
                }
                if (tag && "entities".equals(type)) {
                    // EntityType-tag matching is a deliberate non-goal for v1.1
                    // (collect_block needs block tags only); reject loudly
                    // instead of silently returning nothing.
                    throw new InvalidParams("filter #tag entries are not supported for type \"entities\""
                            + " (use entity type ids like \"minecraft:zombie\")");
                }
                // Namespace completion: a bare path lives in the minecraft
                // namespace, exactly like commands do (#logs -> minecraft:logs).
                filter.add((tag ? "#" : "") + (id.indexOf(':') >= 0 ? id : "minecraft:" + id));
            }
        }
        return new WorldQueryParams(type, range, filter,
                integerMember(params, "cursor", "world.query", 0, Integer.MAX_VALUE, 0),
                integerMember(params, "limit", "world.query", 1, MAX_LIMIT, DEFAULT_LIMIT),
                booleanMember(params, "aggregate", false));
    }

    /**
     * Reads an optional integer member with default + range check. Powers the
     * v1.6 paging params; the -32602 text names both the bound and the schema.
     */
    static int integerMember(JsonObject params, String member, String tool, int min, int max, int fallback)
            throws InvalidParams {
        JsonElement e = params.get(member);
        if (e == null || e.isJsonNull()) {
            return fallback;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new InvalidParams(tool + " " + member + " must be an integer " + min + ".." + max);
        }
        double v = e.getAsDouble();
        if (!Double.isFinite(v) || v != Math.floor(v)) {
            throw new InvalidParams(tool + " " + member + " must be an integer " + min + ".." + max
                    + ", got: " + v);
        }
        if (v < min || v > max) {
            throw new InvalidParams(tool + " " + member + " must be within " + min + ".." + max
                    + ", got: " + (long) v);
        }
        return (int) v;
    }

    /** Reads an optional boolean member with default (absent/null -> {@code fallback}). */
    static boolean booleanMember(JsonObject params, String member, boolean fallback) throws InvalidParams {
        JsonElement e = params.get(member);
        if (e == null || e.isJsonNull()) {
            return fallback;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new InvalidParams(member + " must be a boolean (true/false)");
        }
        return e.getAsBoolean();
    }

    // ------------------------------------------------------------------ results

    /** {@code {"in_game": false}} - the shared "not in a world" answer (not an error). */
    public static JsonObject notInGame() {
        JsonObject result = new JsonObject();
        result.addProperty("in_game", false);
        return result;
    }

    /**
     * {@code screenshot} result:
     * {@code {"image_b64","format":"jpeg","width","height","taken_at","quality","downscaled"}}.
     */
    public static JsonObject screenshotResult(String imageBase64, int width, int height,
                                               long takenAtMs, int quality, boolean downscaled) {
        JsonObject result = new JsonObject();
        result.addProperty("image_b64", imageBase64);
        result.addProperty("format", "jpeg");
        result.addProperty("width", width);
        result.addProperty("height", height);
        result.addProperty("taken_at", takenAtMs);
        result.addProperty("quality", quality);
        result.addProperty("downscaled", downscaled);
        return result;
    }

    /** One active potion effect. */
    public record EffectFact(String id, int duration, int amplifier) {
    }

    /**
     * Player stats extracted on the main thread (see PerceptionTools).
     * {@code yaw}/{@code pitch} are the M4.1 v1.3 additions (movement-direction
     * diagnostics, the unstuck fan base, natural-head-turn verification) -
     * additive response fields, no protocol break for older brains.
     *
     * <p>v1.6 (M5.0) appends the environment facts the agent had to screenshot
     * for: {@code windowFocused} (vanilla hover and drag need focus),
     * the day clock ({@code dayTime}/{@code day}/{@code isDay}/{@code hasSky} -
     * the nether/end have no daylight, hence the separate sky flag), weather
     * and the biome id.
     */
    public record StatsSnapshot(float health, int food, float saturation, int air,
                                int xpLevel, float xpProgress,
                                double x, double y, double z,
                                String dimension, String gameMode,
                                List<EffectFact> effects, boolean alive,
                                float yaw, float pitch,
                                boolean windowFocused, int dayTime, int day, boolean isDay,
                                boolean hasSky, boolean raining, boolean thundering, String biome) {
    }

    /** Daytime ticks per Minecraft day. */
    public static final long TICKS_PER_DAY = 24_000L;

    /** {@code is_day} cutoff: vanilla considers ticks 0..12999 daylight. */
    public static final int IS_DAY_CUTOFF = 13_000;

    /** {@code level.getDayTime() % 24000} - the position inside the current day. */
    public static int dayTimeOf(long dayTime) {
        return (int) Math.floorMod(dayTime, TICKS_PER_DAY);
    }

    /** {@code level.getDayTime() / 24000} - the day counter. */
    public static int dayNumberOf(long dayTime) {
        return (int) Math.floorDiv(dayTime, TICKS_PER_DAY);
    }

    /** Whether the given intra-day tick counts as daylight ({@code < 13000}). */
    public static boolean isDayTime(int dayTime) {
        return dayTime < IS_DAY_CUTOFF;
    }

    /** {@code getStats} result (in-game shape; see {@link #notInGame()} for the other one). */
    public static JsonObject statsResult(StatsSnapshot s) {
        JsonObject position = new JsonObject();
        position.addProperty("x", s.x());
        position.addProperty("y", s.y());
        position.addProperty("z", s.z());

        JsonArray effects = new JsonArray();
        for (EffectFact effect : s.effects()) {
            JsonObject e = new JsonObject();
            e.addProperty("id", effect.id());
            e.addProperty("duration", effect.duration());
            e.addProperty("amplifier", effect.amplifier());
            effects.add(e);
        }

        JsonObject result = new JsonObject();
        result.addProperty("in_game", true);
        result.addProperty("health", s.health());
        result.addProperty("food", s.food());
        result.addProperty("saturation", s.saturation());
        result.addProperty("air", s.air());
        result.addProperty("xp_level", s.xpLevel());
        result.addProperty("xp_progress", s.xpProgress());
        result.add("position", position);
        result.addProperty("dimension", s.dimension());
        result.addProperty("game_mode", s.gameMode());
        result.add("effects", effects);
        result.addProperty("alive", s.alive());
        result.addProperty("yaw", s.yaw());
        result.addProperty("pitch", s.pitch());
        // v1.6 environment facts (purely additive: older brains ignore them).
        result.addProperty("window_focused", s.windowFocused());
        result.addProperty("day_time", s.dayTime());
        result.addProperty("day", s.day());
        result.addProperty("is_day", s.isDay());
        result.addProperty("has_sky", s.hasSky());
        result.addProperty("raining", s.raining());
        result.addProperty("thundering", s.thundering());
        result.addProperty("biome", s.biome());
        return result;
    }

    // ------------------------------------------------------------------ world.query

    /** Max entries returned for a blocks scan. */
    public static final int BLOCKS_CAP = 512;

    /** Max entries returned for an entities query. */
    public static final int ENTITIES_CAP = 128;

    /**
     * Max entries returned for a FILTERED blocks scan (v1.1). A filter narrows
     * the world down to "targets", and the caller (e.g. collect_block) wants
     * the NEAREST ones - 32 slots is plenty for one decision round while
     * keeping the response small even inside a forest of tag matches.
     */
    public static final int FILTERED_BLOCKS_CAP = 32;

    /**
     * Memory guard for the filtered scan's collect-all phase (T6): the whole
     * cube's matches are collected before sorting, so a pathological filter
     * (e.g. a 64-range query over solid stone filtering for stone) must not
     * build an unbounded list. 4096 matches is far beyond any real decision
     * round (32 are returned); hitting the guard flags {@code truncated:true}
     * exactly like exceeding the response cap.
     */
    public static final int FILTERED_MATCH_GUARD = 4096;

    /**
     * Block access for {@link #scanBlocks(int, int, int, double, BlockProbe)}:
     * returns the block's registry name ("minecraft:stone") or {@code null}
     * for air / unloaded chunk. Implemented over {@code ClientLevel.getBlockState}
     * in PerceptionTools.
     */
    @FunctionalInterface
    public interface BlockProbe {
        String blockAt(int x, int y, int z);
    }

    /**
     * Tag membership test for the filtered block scan: is the block TYPE named
     * {@code blockName} ("minecraft:oak_log") inside tag {@code tagId}
     * ("minecraft:logs")? Name-keyed (not position-keyed) because block tags
     * live on the registry holder, i.e. every state of one block type answers
     * identically - letting the implementation memoize per distinct name and
     * reuse the name the probe already resolved. Implemented over
     * {@code BlockState.is(TagKey)} in PerceptionTools; may be {@code null}
     * when the filter contains no {@code #tag} entries (never invoked then).
     */
    @FunctionalInterface
    public interface TagProbe {
        boolean blockHasTag(String blockName, String tagId);
    }

    /**
     * A parsed block filter (v1.1): explicit registry ids + {@code #tag} ids
     * (both already namespace-normalized by {@link #worldQueryParams}). A
     * block matches when its registry name is in {@code ids} OR it is a
     * member of any tag in {@code tagIds}.
     *
     * @param ids    full registry ids, e.g. {@code minecraft:spruce_log}
     * @param tagIds tag ids without the {@code #}, e.g. {@code minecraft:logs}
     */
    public record BlockFilter(Set<String> ids, List<String> tagIds) {

        /** Splits normalized filter entries into id set + tag list. */
        public static BlockFilter parse(List<String> normalizedEntries) {
            Set<String> ids = new LinkedHashSet<>();
            List<String> tags = new ArrayList<>();
            for (String entry : normalizedEntries) {
                if (entry.startsWith("#")) {
                    tags.add(entry.substring(1));
                } else {
                    ids.add(entry);
                }
            }
            return new BlockFilter(ids, List.copyOf(tags));
        }
    }

    /** One match of a filtered scan, ordered by {@code distSq} to the player. */
    private record BlockHit(double distSq, int x, int y, int z, String name) {
    }

    /**
     * Cubic scan around ({@code cx,cy,cz}) with radius {@code range} blocks,
     * listing non-air blocks as {@code {x,y,z,block}}. Stops at
     * {@link #BLOCKS_CAP} entries and flags {@code truncated:true}.
     *
     * <p>v1.0 path - byte-for-byte the pre-filter behaviour; kept separate so
     * the default call cannot drift.
     */
    public static JsonObject scanBlocks(int cx, int cy, int cz, double range, BlockProbe probe) {
        int r = (int) Math.ceil(range);
        JsonArray blocks = new JsonArray();
        boolean truncated = false;
        scan:
        for (int x = cx - r; x <= cx + r; x++) {
            for (int y = cy - r; y <= cy + r; y++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    String name = probe.blockAt(x, y, z);
                    if (name == null) {
                        continue; // air / unloaded
                    }
                    if (blocks.size() >= BLOCKS_CAP) {
                        truncated = true;
                        break scan;
                    }
                    JsonObject block = new JsonObject();
                    block.addProperty("x", x);
                    block.addProperty("y", y);
                    block.addProperty("z", z);
                    block.addProperty("block", name);
                    blocks.add(block);
                }
            }
        }

        JsonObject result = new JsonObject();
        result.add("blocks", blocks);
        result.addProperty("count", blocks.size());
        result.addProperty("truncated", truncated);
        return result;
    }

    /**
     * FILTERED cubic scan (v1.1, T6 truncation fix): same cube as the v1.0
     * scan around the player's exact position ({@code floor(px)..}, radius
     * {@code ceil(range)}), but only blocks matching {@code filter} are
     * reported, ordered by squared distance to the player (block CENTER
     * {@code x+0.5,y+0.5,z+0.5} vs {@code px,py,pz} - the same point lookAt
     * aims at), ascending. Cap {@link #FILTERED_BLOCKS_CAP}; more matches
     * than that -> the NEAREST ones win and {@code truncated:true}.
     *
     * <p><b>T6 fix (M3.5-T5a evidence):</b> the filtered path now collects
     * ALL matches first (bounded only by the {@link #FILTERED_MATCH_GUARD}
     * memory guard), THEN sorts by distance, THEN caps. The previous bounded
     * heap kept the nearest 32 incrementally, which was distance-correct but
     * still enumerated-and-dropped matches mid-scan; the collect-all shape
     * makes "truncated means the nearest 32 are exact" trivially auditable,
     * and the smoke test pins the dense-scene regression (a near target must
     * survive a >512-match query - the T5a symptom where range 5.5 lost a
     * 3.71-block target that range 4.0 could see). The unfiltered v1.0 path
     * keeps its documented stop-at-512 semantics untouched.
     */
    public static JsonObject scanBlocks(double px, double py, double pz, double range,
                                        BlockProbe probe, BlockFilter filter, TagProbe tagProbe) {
        int cx = (int) Math.floor(px);
        int cy = (int) Math.floor(py);
        int cz = (int) Math.floor(pz);
        int r = (int) Math.ceil(range);
        List<BlockHit> matches = new ArrayList<>();
        boolean truncated = false;
        scan:
        for (int x = cx - r; x <= cx + r; x++) {
            for (int y = cy - r; y <= cy + r; y++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    String name = probe.blockAt(x, y, z);
                    if (name == null) {
                        continue; // air / unloaded
                    }
                    boolean match = filter.ids().contains(name);
                    if (!match && !filter.tagIds().isEmpty()) {
                        for (String tagId : filter.tagIds()) {
                            if (tagProbe.blockHasTag(name, tagId)) {
                                match = true;
                                break;
                            }
                        }
                    }
                    if (!match) {
                        continue;
                    }
                    if (matches.size() >= FILTERED_MATCH_GUARD) {
                        truncated = true; // memory guard: some match went uncollected
                        break scan;
                    }
                    double dx = x + 0.5 - px;
                    double dy = y + 0.5 - py;
                    double dz = z + 0.5 - pz;
                    matches.add(new BlockHit(dx * dx + dy * dy + dz * dz, x, y, z, name));
                }
            }
        }

        // Ascending distance; ties broken by (x,y,z) so the order is
        // deterministic (symmetric positions share distSq all the time).
        matches.sort(Comparator.comparingDouble(BlockHit::distSq)
                .thenComparingInt(BlockHit::x)
                .thenComparingInt(BlockHit::y)
                .thenComparingInt(BlockHit::z));
        if (matches.size() > FILTERED_BLOCKS_CAP) {
            truncated = true; // more matches than the response cap - the tail is dropped
            matches = new ArrayList<>(matches.subList(0, FILTERED_BLOCKS_CAP));
        }
        JsonArray blocks = new JsonArray();
        for (BlockHit hit : matches) {
            JsonObject block = new JsonObject();
            block.addProperty("x", hit.x());
            block.addProperty("y", hit.y());
            block.addProperty("z", hit.z());
            block.addProperty("block", hit.name());
            blocks.add(block);
        }

        JsonObject result = new JsonObject();
        result.add("blocks", blocks);
        result.addProperty("count", blocks.size());
        result.addProperty("truncated", truncated);
        return result;
    }

    /**
     * Entity data extracted on the main thread; {@code health} is NaN when
     * unknown. {@code item}/{@code count} (T7) are only set for
     * {@code minecraft:item} entities - the dropped stack's registry id and
     * stack size; {@code null}/0 for everything else. The display {@code name}
     * is client-localized ("橡木原木") and therefore useless for brain-side
     * id matching, exactly why the registry id rides along (same philosophy as
     * blocks reporting {@code minecraft:oak_log}).
     *
     * <p>M4 adds {@code category} ({@code EntityType.getCategory()} name,
     * lowercase - "monster"/"creature"/"ambient"/"water_creature"/"misc", a
     * registry datum so modded hostiles classify themselves) and {@code width}
     * (collision-box width, the brain's flee reflex approximates melee reach
     * as width/2 + 1.5). Both additive; null/0 for pre-M4 constructors.
     */
    public record EntityFact(String uuid, String name, String type,
                             double x, double y, double z, float health,
                             String item, int count,
                             String category, double width) {

        /** Non-item entity convenience (item fields default to null/0). */
        public EntityFact(String uuid, String name, String type,
                          double x, double y, double z, float health) {
            this(uuid, name, type, x, y, z, health, null, 0, null, 0.0);
        }

        /** Item entity convenience (M4 category/width default to null/0). */
        public EntityFact(String uuid, String name, String type,
                          double x, double y, double z, float health,
                          String item, int count) {
            this(uuid, name, type, x, y, z, health, item, count, null, 0.0);
        }
    }

    /**
     * Filters {@code facts} to those within {@code range} blocks of
     * ({@code cx,cy,cz}) (squared-3D-distance) and caps at
     * {@link #ENTITIES_CAP} entries. {@code typeFilter} (v1.1, may be null =
     * keep every entity) holds normalized entity-type registry ids
     * ({@code minecraft:zombie}); an entity passes only when its
     * {@code EntityType.getKey} id is in the set.
     *
     * <p>{@code truncated} (new in v1.1 - previously the cap silently dropped
     * the tail) is true when at least one further in-range (and type-matching)
     * entity had to be dropped beyond the cap. Result order stays the
     * caller's fact order (render-list order); unlike blocks, entity lists
     * are small and the nearest-first contract is not part of this branch.
     */
    public static JsonObject filterEntities(List<EntityFact> facts, double cx, double cy, double cz,
                                            double range, List<String> typeFilter) {
        double maxDistSq = range * range;
        Set<String> wantedTypes = typeFilter == null ? null : new LinkedHashSet<>(typeFilter);
        JsonArray entities = new JsonArray();
        boolean truncated = false;
        for (EntityFact fact : facts) {
            if (wantedTypes != null && !wantedTypes.contains(fact.type())) {
                continue;
            }
            double dx = fact.x() - cx;
            double dy = fact.y() - cy;
            double dz = fact.z() - cz;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) {
                continue;
            }
            if (entities.size() >= ENTITIES_CAP) {
                truncated = true; // one more in-range match than the cap allows
                break;
            }
            entities.add(entityJson(fact));
        }

        JsonObject result = new JsonObject();
        result.add("entities", entities);
        result.addProperty("count", entities.size());
        result.addProperty("truncated", truncated);
        return result;
    }

    /** One entity entry of a {@code world.query} response (shared by both paths). */
    private static JsonObject entityJson(EntityFact fact) {
        JsonObject position = new JsonObject();
        position.addProperty("x", fact.x());
        position.addProperty("y", fact.y());
        position.addProperty("z", fact.z());

        JsonObject entity = new JsonObject();
        entity.addProperty("uuid", fact.uuid());
        entity.addProperty("name", fact.name());
        entity.addProperty("type", fact.type());
        entity.add("position", position);
        if (!Float.isNaN(fact.health())) {
            entity.addProperty("health", fact.health());
        }
        if (fact.item() != null) {
            // T7 pure addition: item entities only. Non-item entries keep
            // the exact pre-T7 shape (backwards compatible, additive).
            entity.addProperty("item", fact.item());
            entity.addProperty("count", fact.count());
        }
        if (fact.category() != null) {
            // M4 pure addition: mob-category registry datum (all entities).
            entity.addProperty("category", fact.category());
        }
        if (fact.width() > 0.0) {
            // M4 pure addition: collision-box width (flee-radius input).
            entity.addProperty("width", fact.width());
        }
        return entity;
    }

    // ------------------------------------------------------------------ world.query paging (v1.6)

    /**
     * PAGED {@code world.query} blocks scan (v1.6). Same cube as the filtered
     * scan ({@code floor(pos)} centre, radius {@code ceil(range)}) but with a
     * single unified contract:
     * <ul>
     *   <li>{@code filter == null} means "every non-air block" (the v1.0
     *       behaviour, now also distance-ordered);</li>
     *   <li>matches are ALWAYS sorted by distance to the player (block centre
     *       vs the exact player position), so {@code cursor} is a plain page
     *       index into a stable sequence;</li>
     *   <li>{@code count} is the total number of matches (after filtering),
     *       NOT the page length - the caller learns "how many exist" and
     *       "how many I got" from {@code count} vs the array size;</li>
     *   <li>{@code has_more}/{@code next_cursor} describe the remainder;
     *       {@code truncated} carries the same meaning (the v1.1 field is kept
     *       so old brains keep working);</li>
     *   <li>{@code aggregate} replaces the coordinate list with
     *       {@code counts:[{name,count}]} (descending by count, at most
     *       {@link #AGGREGATE_MAX_TYPES} names) - "what is around me", not
     *       "where is each one".</li>
     * </ul>
     * The collect-all phase is bounded by {@link #FILTERED_MATCH_GUARD}: past
     * that the totals are honest lower bounds and {@code truncated} is forced.
     */
    public static JsonObject scanBlocksPaged(double px, double py, double pz, double range,
                                             BlockProbe probe, BlockFilter filter, TagProbe tagProbe,
                                             int cursor, int limit, boolean aggregate) {
        int cx = (int) Math.floor(px);
        int cy = (int) Math.floor(py);
        int cz = (int) Math.floor(pz);
        int r = (int) Math.ceil(range);
        List<BlockHit> matches = new ArrayList<>();
        boolean guarded = false;
        scan:
        for (int x = cx - r; x <= cx + r; x++) {
            for (int y = cy - r; y <= cy + r; y++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    String name = probe.blockAt(x, y, z);
                    if (name == null) {
                        continue; // air / unloaded
                    }
                    if (filter != null && !matchesFilter(filter, tagProbe, name)) {
                        continue;
                    }
                    if (matches.size() >= FILTERED_MATCH_GUARD) {
                        guarded = true; // memory guard: some match went uncollected
                        break scan;
                    }
                    double dx = x + 0.5 - px;
                    double dy = y + 0.5 - py;
                    double dz = z + 0.5 - pz;
                    matches.add(new BlockHit(dx * dx + dy * dy + dz * dz, x, y, z, name));
                }
            }
        }
        matches.sort(Comparator.comparingDouble(BlockHit::distSq)
                .thenComparingInt(BlockHit::x)
                .thenComparingInt(BlockHit::y)
                .thenComparingInt(BlockHit::z));

        if (aggregate) {
            // Aggregate pages are complete by nature: no coordinates, so one
            // call answers the whole query (paging an aggregate makes no sense).
            List<NameCount> counts = countByName(matches.stream().map(BlockHit::name).toList());
            JsonObject result = new JsonObject();
            result.add("counts", countsJson(counts));
            result.addProperty("count", matches.size());
            result.addProperty("truncated", guarded || counts.size() > AGGREGATE_MAX_TYPES);
            result.addProperty("cursor", 0);
            result.add("next_cursor", JsonNull.INSTANCE);
            result.addProperty("has_more", false);
            result.addProperty("limit", limit);
            result.addProperty("aggregate", true);
            return result;
        }

        int from = Math.min(cursor, matches.size());
        int to = Math.min(from + limit, matches.size());
        JsonArray blocks = new JsonArray();
        for (BlockHit hit : matches.subList(from, to)) {
            JsonObject block = new JsonObject();
            block.addProperty("x", hit.x());
            block.addProperty("y", hit.y());
            block.addProperty("z", hit.z());
            block.addProperty("block", hit.name());
            blocks.add(block);
        }
        // has_more must also be true when the memory guard cut the collection
        // short (there are provably more matches than anything we can report).
        boolean hasMore = to < matches.size() || guarded;
        JsonObject result = new JsonObject();
        result.add("blocks", blocks);
        result.addProperty("count", matches.size());
        result.addProperty("truncated", hasMore);
        result.addProperty("cursor", cursor);
        result.add("next_cursor", hasMore ? new JsonPrimitive(to) : JsonNull.INSTANCE);
        result.addProperty("has_more", hasMore);
        result.addProperty("limit", limit);
        return result;
    }

    /** Id-or-tag membership of one block name (shared by both filtered paths). */
    private static boolean matchesFilter(BlockFilter filter, TagProbe tagProbe, String name) {
        if (filter.ids().contains(name)) {
            return true;
        }
        if (filter.tagIds().isEmpty() || tagProbe == null) {
            return false;
        }
        for (String tagId : filter.tagIds()) {
            if (tagProbe.blockHasTag(name, tagId)) {
                return true;
            }
        }
        return false;
    }

    /** One aggregate bucket: a registry id and how many matches carried it. */
    public record NameCount(String name, int count) {
    }

    /** Groups names into count buckets, ordered by count desc then name asc (deterministic). */
    private static List<NameCount> countByName(List<String> names) {
        Map<String, Integer> counts = new HashMap<>();
        for (String name : names) {
            counts.merge(name, 1, Integer::sum);
        }
        List<NameCount> out = new ArrayList<>(counts.size());
        counts.forEach((name, count) -> out.add(new NameCount(name, count)));
        out.sort(Comparator.comparingInt(NameCount::count).reversed()
                .thenComparing(NameCount::name));
        return out;
    }

    /** {@code counts} array, capped at {@link #AGGREGATE_MAX_TYPES} entries. */
    private static JsonArray countsJson(List<NameCount> counts) {
        JsonArray array = new JsonArray();
        int emitted = 0;
        for (NameCount entry : counts) {
            if (emitted++ >= AGGREGATE_MAX_TYPES) {
                break;
            }
            JsonObject item = new JsonObject();
            item.addProperty("name", entry.name());
            item.addProperty("count", entry.count());
            array.add(item);
        }
        return array;
    }

    /** One entity match, ordered by {@code distSq} to the player. */
    private record EntityHit(double distSq, EntityFact fact) {
    }

    /**
     * PAGED {@code world.query} entities path (v1.6): range + type filter,
     * distance-ascending (ties broken by uuid so the order is deterministic),
     * then the same {@code cursor}/{@code limit}/{@code aggregate} contract as
     * {@link #scanBlocksPaged}. Aggregate buckets key on the entity TYPE id
     * (dropped items therefore aggregate as {@code minecraft:item}, which is
     * what "how many drops are lying around" asks for).
     */
    public static JsonObject queryEntitiesPaged(List<EntityFact> facts, double cx, double cy, double cz,
                                                double range, List<String> typeFilter,
                                                int cursor, int limit, boolean aggregate) {
        double maxDistSq = range * range;
        Set<String> wantedTypes = typeFilter == null ? null : new LinkedHashSet<>(typeFilter);
        List<EntityHit> matches = new ArrayList<>();
        for (EntityFact fact : facts) {
            if (wantedTypes != null && !wantedTypes.contains(fact.type())) {
                continue;
            }
            double dx = fact.x() - cx;
            double dy = fact.y() - cy;
            double dz = fact.z() - cz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > maxDistSq) {
                continue;
            }
            matches.add(new EntityHit(distSq, fact));
        }
        matches.sort(Comparator.comparingDouble(EntityHit::distSq)
                .thenComparing(hit -> hit.fact().uuid() == null ? "" : hit.fact().uuid()));

        if (aggregate) {
            List<NameCount> counts = countByName(matches.stream().map(hit -> hit.fact().type()).toList());
            JsonObject result = new JsonObject();
            result.add("counts", countsJson(counts));
            result.addProperty("count", matches.size());
            result.addProperty("truncated", counts.size() > AGGREGATE_MAX_TYPES);
            result.addProperty("cursor", 0);
            result.add("next_cursor", JsonNull.INSTANCE);
            result.addProperty("has_more", false);
            result.addProperty("limit", limit);
            result.addProperty("aggregate", true);
            return result;
        }

        int from = Math.min(cursor, matches.size());
        int to = Math.min(from + limit, matches.size());
        JsonArray entities = new JsonArray();
        for (EntityHit hit : matches.subList(from, to)) {
            entities.add(entityJson(hit.fact()));
        }
        boolean hasMore = to < matches.size();
        JsonObject result = new JsonObject();
        result.add("entities", entities);
        result.addProperty("count", matches.size());
        result.addProperty("truncated", hasMore);
        result.addProperty("cursor", cursor);
        result.add("next_cursor", hasMore ? new JsonPrimitive(to) : JsonNull.INSTANCE);
        result.addProperty("has_more", hasMore);
        result.addProperty("limit", limit);
        return result;
    }
}
