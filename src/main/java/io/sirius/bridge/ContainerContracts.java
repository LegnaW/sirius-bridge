package io.sirius.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pure params, slot diffs and response shapes for protocol-1.10 container primitives. */
final class ContainerContracts {

    static final int MAX_SLOT = 511;
    static final int MAX_BUTTON = 40;
    static final int MAX_TRANSFER_COUNT = 128;
    static final List<String> MODES = List.of(
            "pickup", "quick_move", "swap", "clone", "throw", "quick_craft", "pickup_all");

    record ClickParams(int slot, int button, String mode) {
    }

    record TransferParams(int fromSlot, int toSlot, int count) {
    }

    record StackFact(String item, int count) {
        static final StackFact EMPTY = new StackFact(null, 0);
    }

    record TransferFacts(boolean sameSlot, boolean carriedNonempty, boolean sourceEmpty,
                         int sourceCount, boolean sourceAllowed, boolean destinationAllowed,
                         boolean compatible, int capacity) {
    }

    private ContainerContracts() {
    }

    static String transferRefusal(TransferFacts facts, int requested) {
        if (facts.sameSlot()) return "no_op";
        if (facts.carriedNonempty()) return "carried_not_empty";
        if (facts.sourceEmpty()) return "empty_source";
        if (requested > facts.sourceCount()) return "invalid_count";
        if (!facts.sourceAllowed() || !facts.destinationAllowed()) return "slot_forbidden";
        if (!facts.compatible()) return "incompatible";
        if (facts.capacity() < requested) return "not_enough_space";
        return null;
    }

    static ClickParams clickParams(JsonObject params) throws ToolContracts.InvalidParams {
        int slot = ToolContracts.integerMember(params, "slot", "container.clickSlot",
                0, MAX_SLOT, -1);
        if (slot < 0) {
            throw new ToolContracts.InvalidParams("container.clickSlot requires integer slot 0.." + MAX_SLOT
                    + " (getGuiState.slots[].index)");
        }
        int button = ToolContracts.integerMember(params, "button", "container.clickSlot",
                0, MAX_BUTTON, 0);
        String mode = "pickup";
        if (params.has("mode") && !params.get("mode").isJsonNull()) {
            if (!params.get("mode").isJsonPrimitive() || !params.get("mode").getAsJsonPrimitive().isString()) {
                throw new ToolContracts.InvalidParams("container.clickSlot mode must be one of "
                        + String.join("/", MODES));
            }
            mode = params.get("mode").getAsString();
        }
        if (!MODES.contains(mode)) {
            throw new ToolContracts.InvalidParams("container.clickSlot mode must be one of "
                    + String.join("/", MODES) + ", got: \"" + mode + "\"");
        }
        return new ClickParams(slot, button, mode);
    }

    static TransferParams transferParams(JsonObject params) throws ToolContracts.InvalidParams {
        int from = requiredSlot(params, "from_slot");
        int to = requiredSlot(params, "to_slot");
        int count = ToolContracts.integerMember(params, "count", "container.transfer",
                1, MAX_TRANSFER_COUNT, 0);
        return new TransferParams(from, to, count);
    }

    private static int requiredSlot(JsonObject params, String name) throws ToolContracts.InvalidParams {
        int slot = ToolContracts.integerMember(params, name, "container.transfer", 0, MAX_SLOT, -1);
        if (slot < 0) {
            throw new ToolContracts.InvalidParams("container.transfer requires integer " + name
                    + " 0.." + MAX_SLOT + " (getGuiState.slots[].index)");
        }
        return slot;
    }

    static void closeParams(JsonObject params) throws ToolContracts.InvalidParams {
        if (!params.entrySet().isEmpty()) {
            throw new ToolContracts.InvalidParams("container.close takes no parameters (got: "
                    + String.join(",", params.keySet()) + ")");
        }
    }

    static JsonObject stateResult(String result, boolean inGame) {
        JsonObject out = new JsonObject();
        out.addProperty("result", result);
        out.addProperty("in_game", inGame);
        out.addProperty("evidence", "none");
        return out;
    }

    static JsonObject clickResult(String result, int containerId, String screenClass,
                                  ClickParams params, List<GuiContracts.SlotFact> before,
                                  List<GuiContracts.SlotFact> after, StackFact carriedBefore,
                                  StackFact carriedAfter) {
        JsonObject out = actionResult(result, containerId, screenClass, before, after,
                carriedBefore, carriedAfter);
        out.addProperty("slot", params.slot());
        out.addProperty("button", params.button());
        out.addProperty("mode", params.mode());
        return out;
    }

    static JsonObject transferResult(String result, int containerId, String screenClass,
                                     TransferParams params, int requested, int moved,
                                     List<GuiContracts.SlotFact> before,
                                     List<GuiContracts.SlotFact> after, StackFact carriedBefore,
                                     StackFact carriedAfter) {
        JsonObject out = actionResult(result, containerId, screenClass, before, after,
                carriedBefore, carriedAfter);
        out.addProperty("from_slot", params.fromSlot());
        out.addProperty("to_slot", params.toSlot());
        out.addProperty("requested", requested);
        out.addProperty("moved", moved);
        return out;
    }

    private static JsonObject actionResult(String result, int containerId, String screenClass,
                                           List<GuiContracts.SlotFact> before,
                                           List<GuiContracts.SlotFact> after, StackFact carriedBefore,
                                           StackFact carriedAfter) {
        JsonObject out = new JsonObject();
        out.addProperty("result", result);
        out.addProperty("in_game", true);
        out.addProperty("container_id", containerId);
        out.addProperty("screen_class", screenClass);
        out.addProperty("evidence", "client_snapshot");
        out.add("changed_slots", changedSlots(before, after));
        out.add("carried_before", stackJson(carriedBefore));
        out.add("carried_after", stackJson(carriedAfter));
        return out;
    }

    static JsonObject closeResult(String result, String screenClass) {
        JsonObject out = new JsonObject();
        out.addProperty("result", result);
        out.addProperty("in_game", true);
        out.addProperty("screen_class", screenClass);
        out.addProperty("evidence", "screen_closed");
        return out;
    }

    static JsonArray changedSlots(List<GuiContracts.SlotFact> before,
                                  List<GuiContracts.SlotFact> after) {
        Map<Integer, GuiContracts.SlotFact> oldByIndex = before.stream()
                .collect(Collectors.toMap(GuiContracts.SlotFact::index, Function.identity()));
        JsonArray changed = new JsonArray();
        for (GuiContracts.SlotFact next : after) {
            GuiContracts.SlotFact old = oldByIndex.get(next.index());
            if (old != null && java.util.Objects.equals(old.item(), next.item())
                    && old.count() == next.count()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", next.index());
            entry.add("before", stackJson(old != null
                    ? new StackFact(old.item(), old.count()) : StackFact.EMPTY));
            entry.add("after", stackJson(new StackFact(next.item(), next.count())));
            changed.add(entry);
        }
        return changed;
    }

    private static JsonObject stackJson(StackFact stack) {
        JsonObject out = new JsonObject();
        out.add("item", stack.item() != null ? new JsonPrimitive(stack.item()) : JsonNull.INSTANCE);
        out.addProperty("count", stack.count());
        return out;
    }
}
