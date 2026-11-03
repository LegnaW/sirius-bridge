package io.sirius.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Frozen-contract logic for the M4.4 v1.4 {@code players} tool: the
 * no-parameter discipline, entry assembly, the deterministic ordering and the
 * response shape. Pure Gson + JDK only - no Minecraft classes - so the smoke
 * test covers it without a game.
 *
 * <p><b>Ordering</b>: {@code self} FIRST, then everyone by name in dictionary
 * order. The client's own entry may be anywhere in
 * {@code ClientPacketListener.getOnlinePlayers()} (it is a hash-backed
 * collection), and the brain reads {@code players[0]} as "me" in practice -
 * plus an unstable order would make every replay and assertion flaky.
 *
 * <p><b>Minimal exposure</b>: name, uuid, self, latency and gamemode only. No
 * skin, no address, no team - the tool answers "who is in this server", which
 * is exactly what the chat event's {@code sender} uuid needs to be resolved
 * against ({@code world.query} only sees entities inside the render distance).
 */
public final class PlayersContracts {

    private PlayersContracts() {
    }

    /**
     * Validates {@code players} params: the frozen schema declares an empty
     * object, so any member is a {@code -32602} violation (the getGuiState
     * discipline - a frozen parameter contract is what keeps the call
     * deterministic).
     */
    public static void playersParams(JsonObject params) throws ToolContracts.InvalidParams {
        if (!params.entrySet().isEmpty()) {
            throw new ToolContracts.InvalidParams("players takes no parameters (got: "
                    + String.join(",", params.keySet()) + ")");
        }
    }

    /** One online player, extracted from {@code PlayerInfo} on the main thread. */
    public record PlayerFact(String name, String uuid, boolean self, int latencyMs, String gamemode) {
    }

    /**
     * Assembles the {@code players} response:
     * {@code {"in_game":true,"count":2,"players":[{"name","uuid","self",
     * "latency_ms","gamemode"}, ...]}} with self first and the rest name-sorted.
     * The input list is not modified (a defensive copy is sorted).
     */
    public static JsonObject playersResult(List<PlayerFact> facts) {
        List<PlayerFact> sorted = new ArrayList<>(facts);
        sorted.sort(Comparator
                .comparing((PlayerFact fact) -> !fact.self())
                .thenComparing(fact -> fact.name() == null ? "" : fact.name()));

        JsonArray players = new JsonArray();
        for (PlayerFact fact : sorted) {
            JsonObject entry = new JsonObject();
            entry.add("name", fact.name() != null ? new JsonPrimitive(fact.name()) : JsonNull.INSTANCE);
            entry.add("uuid", fact.uuid() != null ? new JsonPrimitive(fact.uuid()) : JsonNull.INSTANCE);
            entry.addProperty("self", fact.self());
            entry.addProperty("latency_ms", fact.latencyMs());
            entry.add("gamemode", fact.gamemode() != null
                    ? new JsonPrimitive(fact.gamemode()) : JsonNull.INSTANCE);
            players.add(entry);
        }

        JsonObject result = new JsonObject();
        result.addProperty("in_game", true);
        result.addProperty("count", players.size());
        result.add("players", players);
        return result;
    }

    /** Not-in-a-world answer (title screen / disconnecting) - not an error. */
    public static JsonObject notInGamePlayers() {
        JsonObject result = new JsonObject();
        result.addProperty("in_game", false);
        result.addProperty("count", 0);
        result.add("players", new JsonArray());
        return result;
    }
}
