package io.sirius.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Map;

/** Pure JSON shapes and change decisions for the protocol-1.11 raw event sources. */
final class CoreEventContracts {

    static final List<String> EVENT_TYPES = List.of(
            "block_update",
            "entity_spawn", "entity_gone", "entity_hurt", "entity_death",
            "item_pickup", "player_join", "player_leave",
            "window_open", "window_close", "slot_update",
            "held_item_changed", "connection_state", "baritone_state");

    record StackFact(String item, int count) {
        static final StackFact EMPTY = new StackFact(null, 0);
    }

    private CoreEventContracts() {
    }

    static EventsContracts.EventLevel levelOf(String type) {
        return switch (type) {
            case "entity_hurt", "entity_death", "connection_state" -> EventsContracts.EventLevel.WARNING;
            default -> EventsContracts.EventLevel.INFO;
        };
    }

    static JsonObject blockState(String item, Map<String, String> properties) {
        JsonObject out = new JsonObject();
        out.addProperty("block", item);
        JsonObject state = new JsonObject();
        properties.forEach(state::addProperty);
        out.add("state", state);
        return out;
    }

    static JsonObject stack(StackFact stack) {
        JsonObject out = new JsonObject();
        out.add("item", stack.item() != null ? new JsonPrimitive(stack.item()) : JsonNull.INSTANCE);
        out.addProperty("count", stack.count());
        return out;
    }

    static boolean heldChanged(int previousSlot, int slot, StackFact previous, StackFact current,
                               boolean sameItemComponents) {
        return previousSlot != slot || !sameItemComponents || previous.count() != current.count();
    }

    static JsonObject heldData(int previousSlot, int slot, StackFact previous, StackFact current) {
        JsonObject out = new JsonObject();
        out.addProperty("previous_slot", previousSlot);
        out.addProperty("slot", slot);
        out.add("before", stack(previous));
        out.add("after", stack(current));
        out.addProperty("evidence", "client_tick");
        return out;
    }

    static JsonObject slotData(int containerId, int stateId, String packet, int slot, StackFact stack) {
        JsonObject out = new JsonObject();
        out.addProperty("container_id", containerId);
        out.addProperty("state_id", stateId);
        out.addProperty("packet", packet);
        out.addProperty("slot", slot);
        out.add("stack", stack(stack));
        out.addProperty("evidence", "server_packet");
        return out;
    }

    static JsonObject subscriptionCatalog() {
        JsonObject out = new JsonObject();
        JsonArray events = new JsonArray();
        EVENT_TYPES.forEach(events::add);
        out.add("events", events);
        out.addProperty("filtering", "connection_side");
        return out;
    }
}
