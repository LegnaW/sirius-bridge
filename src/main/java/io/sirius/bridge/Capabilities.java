package io.sirius.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Capability catalogue for {@code capabilities/list} (protocol v1.0).
 *
 * <p>Single source of truth: the frozen JSON schemas checked into this
 * repository. The build copies {@code schema/index.json} and
 * {@code schema/tools/*.json} into the mod jar under
 * {@code schema/} (see the {@code syncToolSchemas} task in build.gradle), and
 * this class assembles the response from those resources at runtime - the mod
 * never re-declares tool names or schemas by hand.
 *
 * <p>{@code index.json} lists {@code categories.tools} as an ordered
 * name -> path map; each referenced file is the tool's {@code input_schema}
 * verbatim (it is the exported pydantic {@code model_json_schema()} of that
 * tool's params).
 */
public final class Capabilities {

    /**
     * Protocol version negotiated via hello/hello_ack and capabilities/list.
     * Bumped 1.0 -> 1.1 in M3.5 (world.query filter + input.click hold_ms),
     * then 1.1 -> 1.2 in M3.5 T6 (the {@code dig} primitive + lookAt
     * {@code turn_speed_deg_s} smooth turning), then 1.2 -> 1.3 in M4.1
     * ({@code chat.send} direct chat + getStats {@code yaw}/{@code pitch}),
     * then 1.3 -> 1.4 in M4.4 (three body primitives: {@code place}
     * action-layer block placement, {@code inventory} structured backpack read
     * - slots on the {@code Inventory} container index, same basis as
     * getGuiState's {@code container_slot} - and {@code players} as the online
     * player list with self-first ordering), then 1.4 -> 1.5 in M4.5 ({@code
     * use}: the third action verb - right-click USE on a block, action layer
     * with a three-field verdict {@code result}/{@code effect}/{@code
     * interaction}), staying in lockstep with {@code protocol_version} in the
     * frozen schema/index.json this class serves. 1.6 (M5.0)
     * adds pagination/aggregation to {@code world.query}, {@code
     * inventory.select}, {@code chat.history} + {@code baritone.status}
     * (client-side chat capture), {@code useItem}, {@code entity.use} / {@code
     * entity.attack}, and environment facts on {@code getStats}. 1.7 adds
     * direct Baritone API goto/settings/stop/tunnel and API-backed status.
     * 1.8 adds per-tick actual-health-loss events with damage-source attribution.
     * 1.9 adds direct Baritone FollowProcess control with a configurable follow radius.
     * 1.10 adds single-block state reads and real container transactions for scripts.
     * 1.11 adds raw block/entity/container/connection/Baritone event sources for connection-side scripts.
     * 1.12 adds action-layer player-inventory drops with an optional exact count.
     */
    public static final String PROTOCOL_VERSION = "1.12";

    /** Per-capability version (the Python mock advertises "1.0" for every tool). */
    public static final String TOOL_VERSION = "1.0";

    private static final String INDEX_RESOURCE = "/schema/index.json";
    private static final String RESOURCE_PREFIX = "/schema/";

    private static volatile JsonArray cached;

    private Capabilities() {
    }

    /**
     * The capability list; loaded once from classpath resources on first use
     * and cached. On (partial) failure the successfully loaded entries are
     * returned and the problem is logged - the frame itself stays schema-valid.
     */
    public static JsonArray list() {
        JsonArray local = cached;
        if (local == null) {
            synchronized (Capabilities.class) {
                if (cached == null) {
                    cached = load();
                }
                local = cached;
            }
        }
        return local;
    }

    public static List<String> registryProblems(java.util.Set<String> registeredMethods) {
        java.util.Set<String> advertised = new java.util.HashSet<>();
        for (JsonElement element : list()) {
            advertised.add(element.getAsJsonObject().get("name").getAsString());
        }
        List<String> problems = new ArrayList<>();
        for (String name : advertised) {
            if (!registeredMethods.contains(name)) {
                problems.add("advertised but not registered: " + name);
            }
        }
        for (String name : registeredMethods) {
            if (!"capabilities/list".equals(name) && !advertised.contains(name)) {
                problems.add("registered but not advertised: " + name);
            }
        }
        return problems;
    }

    private static JsonArray load() {
        JsonArray out = new JsonArray();
        JsonObject index = readResource(INDEX_RESOURCE).getAsJsonObject();
        JsonObject tools = index.getAsJsonObject("categories").getAsJsonObject("tools");
        List<String> problems = new ArrayList<>();
        for (var entry : tools.entrySet()) {
            String name = entry.getKey();
            String path = entry.getValue().getAsString();
            try {
                JsonObject schema = readResource(RESOURCE_PREFIX + path).getAsJsonObject();
                out.add(capability(name, schema));
            } catch (Exception e) {
                problems.add(name + "(" + e + ")");
            }
        }
        if (!problems.isEmpty()) {
            SiriusBridge.LOGGER.error("sirius-bridge: failed to load tool schemas: {}", String.join(", ", problems));
        }
        SiriusBridge.LOGGER.info("sirius-bridge: {} capabilities loaded from frozen schema", out.size());
        return out;
    }

    private static JsonObject capability(String name, JsonObject inputSchema) {
        JsonObject cap = new JsonObject();
        cap.addProperty("name", name);
        cap.addProperty("version", TOOL_VERSION);
        cap.add("input_schema", inputSchema);
        return cap;
    }

    private static JsonElement readResource(String resource) {
        try (InputStream in = Capabilities.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("resource not found: " + resource);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
