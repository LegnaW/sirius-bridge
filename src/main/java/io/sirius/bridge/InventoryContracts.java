package io.sirius.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Frozen-contract logic for the M4.4 v1.4 {@code inventory} tool: scope
 * parsing, filter normalization, slot-to-role classification, the emptiness
 * lists, count aggregation and response assembly. Pure Gson + JDK only - no
 * Minecraft classes - so the smoke test covers it without a game.
 *
 * <p><b>Slot basis</b> (the M2-C real-machine lesson, deliberately repeated
 * here): every {@code slot} is an <b>{@code Inventory} container index</b> -
 * 0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand - the SAME basis as
 * {@code getGuiState}'s {@code container_slot}, and never {@code Slot.index}
 * (that one is the menu list position once a container screen is open).
 *
 * <p><b>Why a {@code filter} probe instead of resolving tags here:</b> {@code
 * #tag} membership lives in the item registry (a game object). The contract
 * half therefore takes an {@link ItemFilter} callback implemented by the shell
 * (registry tag membership, memoized per item id) and keeps every decision -
 * which slots are reported, what {@code counts} sums, which indices land in
 * {@code matching} - pure and testable.
 *
 * <p><b>Response semantics</b> (a point the spec left implicit, resolved here
 * and pinned by the smoke test):
 * <ul>
 *   <li>no {@code filter}: {@code slots} lists every slot of the scope, empty
 *       ones included ({@code item:null}, {@code count:0}) - the response is a
 *       structural map of the backpack, not a shopping list;</li>
 *   <li>with {@code filter}: {@code slots} lists only the MATCHING non-empty
 *       slots (that is what the caller asked for), and {@code matching} carries
 *       their slot indices;</li>
 *   <li>{@code counts} aggregates over exactly the reported slots, so a filtered
 *       call answers "how much of these do I have";</li>
 *   <li>{@code empty_hotbar}/{@code empty_main} are always the full emptiness
 *       lists of the player inventory (0-8 / 9-35), independent of scope and
 *       filter: callers use them to pick a DESTINATION slot, so narrowing them
 *       by filter would defeat the purpose.</li>
 * </ul>
 */
public final class InventoryContracts {

    private InventoryContracts() {
    }

    // ------------------------------------------------------------------ slot layout

    /** Inventory container indices (same basis as getGuiState's container_slot). */
    public static final int HOTBAR_START = 0;
    public static final int HOTBAR_END = 8;
    public static final int MAIN_START = 9;
    public static final int MAIN_END = 35;
    public static final int ARMOR_START = 36;
    public static final int ARMOR_END = 39;
    public static final int OFFHAND_SLOT = 40;

    /** Total slots reported by {@code Inventory.getContainerSize()} in 1.21.1 (36 + 4 + 1). */
    public static final int SLOT_COUNT = 41;

    /**
     * 玩家背包菜单（{@code player.inventoryMenu}，containerId 0）的布局：
     * 菜单 {@code 0} 是合成输出、{@code 1-4} 合成格、{@code 5-8} 盔甲、
     * {@code 9-35} 主背包、{@code 36-44} 快捷栏、{@code 45} 副手。
     * {@code handleInventoryMouseClick} 认的是<b>菜单槽</b>，把背包下标当菜单槽用
     * 会点到合成格/盔甲格上（2026-11-01 真机 bug：
     * {@code place {item}} 想交换主背包与快捷栏，结果点了合成格，方块永远换不到手上）。
     */
    public static final int MENU_ARMOR_START = 5;
    public static final int MENU_HOTBAR_START = 36;
    public static final int MENU_OFFHAND_SLOT = 45;

    /** 背包槽（{@code Inventory} 索引）→ 玩家背包菜单槽位。 */
    public static int menuSlotForPlayerSlot(int playerSlot) {
        if (playerSlot >= HOTBAR_START && playerSlot <= HOTBAR_END) {
            return MENU_HOTBAR_START + playerSlot;
        }
        if (playerSlot >= ARMOR_START && playerSlot <= ARMOR_END) {
            return MENU_ARMOR_START + playerSlot - ARMOR_START;
        }
        return playerSlot == OFFHAND_SLOT ? MENU_OFFHAND_SLOT : playerSlot;
    }

    /** Max entries in an {@code inventory} filter array (aligned with world.query). */
    public static final int MAX_FILTER_ENTRIES = 16;

    /** Max length of one filter entry, in chars. */
    public static final int MAX_FILTER_ENTRY_CHARS = 128;

    /** Scope ids (frozen schema enum), in the schema's order. */
    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_HOTBAR = "hotbar";
    public static final String SCOPE_MAIN = "main";
    public static final String SCOPE_ARMOR = "armor";
    public static final String SCOPE_OFFHAND = "offhand";

    /** The scope names, for {@code -32602} messages. */
    public static String scopes() {
        return SCOPE_ALL + "/" + SCOPE_HOTBAR + "/" + SCOPE_MAIN + "/" + SCOPE_ARMOR + "/" + SCOPE_OFFHAND;
    }

    /**
     * Entry charset: an optional {@code #} tag marker, then a registry location
     * (optional {@code [a-z0-9_.-]+:} namespace + a non-empty
     * {@code [a-z0-9_./-]+} path) - the same shape {@code world.query.filter}
     * accepts, so a validated entry always resolves without a runtime exception.
     */
    private static final java.util.regex.Pattern FILTER_ENTRY =
            java.util.regex.Pattern.compile("[a-z0-9_.-]+(?::[a-z0-9_./-]+)?");

    // ------------------------------------------------------------------ params

    /**
     * A validated filter: concrete item ids and {@code #tag} ids, both already
     * namespace-completed ({@code oak_log} -> {@code minecraft:oak_log},
     * {@code #logs} -> {@code #minecraft:logs} without the {@code #}).
     */
    public record Filter(List<String> ids, List<String> tagIds) {

        public boolean hasTags() {
            return !tagIds.isEmpty();
        }
    }

    /** Validated {@code inventory} params. {@code filter} is null when absent. */
    public record InventoryParams(String scope, Filter filter) {
    }

    /**
     * Validates {@code inventory} params: optional {@code scope} (one of
     * {@code all/hotbar/main/armor/offhand}, default {@code all}) and optional
     * {@code filter} (1..16 strings, registry ids or {@code #tag}s; bare paths
     * get the {@code minecraft:} prefix).
     */
    public static InventoryParams inventoryParams(JsonObject params) throws ToolContracts.InvalidParams {
        String scope = SCOPE_ALL;
        JsonElement scopeElement = params.get("scope");
        if (scopeElement != null && !scopeElement.isJsonNull()) {
            if (!scopeElement.isJsonPrimitive() || !scopeElement.getAsJsonPrimitive().isString()) {
                throw new ToolContracts.InvalidParams(
                        "inventory scope must be a string, one of " + scopes());
            }
            scope = scopeElement.getAsString();
            if (scopeIndex(scope) < 0) {
                throw new ToolContracts.InvalidParams("inventory scope must be one of " + scopes()
                        + ", got: \"" + scope + "\"");
            }
        }
        return new InventoryParams(scope, filter(params));
    }

    /** {@code -1} for an unknown scope name (order mirrors the schema enum). */
    private static int scopeIndex(String scope) {
        return switch (scope) {
            case SCOPE_ALL -> 0;
            case SCOPE_HOTBAR -> 1;
            case SCOPE_MAIN -> 2;
            case SCOPE_ARMOR -> 3;
            case SCOPE_OFFHAND -> 4;
            default -> -1;
        };
    }

    private static Filter filter(JsonObject params) throws ToolContracts.InvalidParams {
        JsonElement filterElement = params.get("filter");
        if (filterElement == null || filterElement.isJsonNull()) {
            return null;
        }
        if (!filterElement.isJsonArray()) {
            throw new ToolContracts.InvalidParams("inventory filter must be an array of strings"
                    + " (item ids like \"oak_log\" or tags like \"#minecraft:logs\"), 1.."
                    + MAX_FILTER_ENTRIES + " entries");
        }
        JsonArray entries = filterElement.getAsJsonArray();
        if (entries.size() < 1) {
            throw new ToolContracts.InvalidParams("inventory filter must contain at least 1 entry"
                    + " (omit filter entirely for the unfiltered scope listing)");
        }
        if (entries.size() > MAX_FILTER_ENTRIES) {
            throw new ToolContracts.InvalidParams("inventory filter must contain at most "
                    + MAX_FILTER_ENTRIES + " entries, got: " + entries.size());
        }
        List<String> ids = new ArrayList<>(entries.size());
        List<String> tagIds = new ArrayList<>();
        for (JsonElement entry : entries) {
            if (entry == null || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                throw new ToolContracts.InvalidParams("inventory filter entries must be strings"
                        + " (item ids like \"oak_log\" or tags like \"#minecraft:logs\")");
            }
            String raw = entry.getAsString();
            if (raw.length() < 1) {
                throw new ToolContracts.InvalidParams("inventory filter entries must not be empty");
            }
            if (raw.length() > MAX_FILTER_ENTRY_CHARS) {
                throw new ToolContracts.InvalidParams("inventory filter entries must be at most "
                        + MAX_FILTER_ENTRY_CHARS + " chars, got: " + raw.length());
            }
            boolean tag = raw.startsWith("#");
            String id = tag ? raw.substring(1) : raw;
            if (id.isEmpty() || !FILTER_ENTRY.matcher(id).matches()) {
                throw new ToolContracts.InvalidParams("inventory filter entry \"" + raw
                        + "\" is not a valid item id or #tag (expected lowercase [a-z0-9_.-] with"
                        + " optional namespace, e.g. \"oak_log\", \"minecraft:oak_log\","
                        + " \"#minecraft:logs\")");
            }
            // Namespace completion: a bare path lives in the minecraft namespace,
            // exactly like commands do (#logs -> minecraft:logs).
            String normalized = id.indexOf(':') >= 0 ? id : "minecraft:" + id;
            if (tag) {
                tagIds.add(normalized);
            } else {
                ids.add(normalized);
            }
        }
        return new Filter(ids, tagIds);
    }

    // ------------------------------------------------------------------ scope + roles

    /** Whether a container slot belongs to the requested scope. */
    public static boolean inScope(int slot, String scope) {
        return switch (scope) {
            case SCOPE_HOTBAR -> slot >= HOTBAR_START && slot <= HOTBAR_END;
            case SCOPE_MAIN -> slot >= MAIN_START && slot <= MAIN_END;
            case SCOPE_ARMOR -> slot >= ARMOR_START && slot <= ARMOR_END;
            case SCOPE_OFFHAND -> slot == OFFHAND_SLOT;
            default -> slot >= HOTBAR_START && slot <= OFFHAND_SLOT; // "all"
        };
    }

    /**
     * Role of one container slot of the PLAYER inventory. Delegates to
     * {@link GuiContracts#roleOf} so both tools agree byte-for-byte on the
     * hotbar/player/armor/offhand boundaries (a single source of truth - the
     * boundaries are protocol semantics, not an implementation detail).
     */
    public static String role(int slot) {
        return GuiContracts.roleOf(false, false, true, slot);
    }

    // ------------------------------------------------------------------ facts + assembly

    /** One slot's contents, read on the client main thread. {@code item} null = empty. */
    public record ItemFact(int slot, String item, String display, int count) {
    }

    /**
     * Filter membership probe, implemented by the shell over the item registry
     * ({@code ItemStack.is(TagKey<Item>)} for tag entries, id equality for id
     * entries). The smoke test supplies its own lambda.
     */
    @FunctionalInterface
    public interface ItemFilter {
        boolean matches(int slot, String itemId);
    }

    /**
     * Assembles the {@code inventory} response. {@code facts} must cover the
     * WHOLE player inventory (slot 0..40, one entry per slot) - the scope,
     * filter, counts and the emptiness lists are all decided here.
     *
     * @param selected the hotbar index currently held ({@code Inventory.selected})
     */
    public static JsonObject inventoryResult(int selected, String scope, List<ItemFact> facts,
                                             Filter filter, ItemFilter matcher) {
        JsonArray slots = new JsonArray();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Integer> matching = new ArrayList<>();
        List<Integer> emptyHotbar = new ArrayList<>();
        List<Integer> emptyMain = new ArrayList<>();

        for (ItemFact fact : facts) {
            // Emptiness lists are scope- and filter-independent (destination slots).
            if (fact.item() == null) {
                if (fact.slot() >= HOTBAR_START && fact.slot() <= HOTBAR_END) {
                    emptyHotbar.add(fact.slot());
                } else if (fact.slot() >= MAIN_START && fact.slot() <= MAIN_END) {
                    emptyMain.add(fact.slot());
                }
            }
            if (!inScope(fact.slot(), scope)) {
                continue;
            }
            if (filter != null) {
                // A filtered view reports ITEMS (what the caller is looking for),
                // so empty slots are not entries of it.
                if (fact.item() == null || !matcher.matches(fact.slot(), fact.item())) {
                    continue;
                }
                matching.add(fact.slot());
            }
            slots.add(slotJson(fact));
            if (fact.item() != null && fact.count() > 0) {
                counts.merge(fact.item(), fact.count(), Integer::sum);
            }
        }

        JsonObject countsJson = new JsonObject();
        counts.forEach(countsJson::addProperty);

        JsonObject result = new JsonObject();
        result.addProperty("in_game", true);
        result.addProperty("selected", selected);
        result.add("slots", slots);
        result.add("empty_hotbar", intArray(emptyHotbar));
        result.add("empty_main", intArray(emptyMain));
        result.add("counts", countsJson);
        result.add("matching", intArray(matching));
        return result;
    }

    /**
     * Not-in-a-world answer (title screen / disconnecting): an empty inventory
     * shape - NOT an error, the getStats convention. The emptiness lists are
     * omitted on purpose: with no world there is no inventory to be empty.
     */
    public static JsonObject notInGameResult() {
        JsonObject result = new JsonObject();
        result.addProperty("in_game", false);
        result.add("slots", new JsonArray());
        result.addProperty("selected", -1);
        result.add("counts", new JsonObject());
        return result;
    }

    private static JsonObject slotJson(ItemFact fact) {
        JsonObject slot = new JsonObject();
        slot.addProperty("slot", fact.slot());
        slot.add("item", fact.item() != null ? new JsonPrimitive(fact.item()) : JsonNull.INSTANCE);
        // v1.6.1: 同时给"游戏内显示名"。模组物品的 registry id（create:cogwheel）
        // 对模型几乎没有意义，而显示名（齿轮）它一眼就懂；两个都给，模型自己挑。
        if (fact.display() != null) {
            slot.addProperty("display", fact.display());
        }
        slot.addProperty("count", fact.item() != null ? fact.count() : 0);
        slot.addProperty("role", role(fact.slot()));
        return slot;
    }

    private static JsonArray intArray(List<Integer> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    // ------------------------------------------------------------------ inventory.select (v1.6)

    /** Hotbar index bounds of {@code inventory.select} (frozen schema). */
    public static final int SELECT_MIN = HOTBAR_START;
    public static final int SELECT_MAX = HOTBAR_END;

    /**
     * Validated {@code inventory.select} params. This primitive only moves the
     * SELECTION (the held hotbar index, {@code Inventory.selected}); it never
     * moves items - rearranging the backpack stays a GUI job, which is why the
     * response only echoes the new/previous slot and the item now in hand.
     */
    public record SelectParams(int slot) {
    }

    /**
     * Validates {@code inventory.select} params: a REQUIRED integer hotbar
     * index 0..8. Anything else is a {@code -32602} (the schema's own bounds),
     * with the teaching text spelling out what the number means.
     */
    public static SelectParams selectParams(JsonObject params) throws ToolContracts.InvalidParams {
        JsonElement e = params.get("slot");
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new ToolContracts.InvalidParams("inventory.select requires integer slot "
                    + SELECT_MIN + ".." + SELECT_MAX + " (hotbar index: 0 = leftmost)");
        }
        double v = e.getAsDouble();
        if (!Double.isFinite(v) || v != Math.floor(v)) {
            throw new ToolContracts.InvalidParams("inventory.select slot must be an integer "
                    + SELECT_MIN + ".." + SELECT_MAX + ", got: " + v);
        }
        if (v < SELECT_MIN || v > SELECT_MAX) {
            throw new ToolContracts.InvalidParams("inventory.select slot must be within "
                    + SELECT_MIN + ".." + SELECT_MAX + " (hotbar index: 0 = leftmost), got: " + (long) v);
        }
        return new SelectParams((int) v);
    }

    /**
     * Assembles the {@code inventory.select} response:
     * {@code {"in_game":true,"selected":n,"previous":n,"item":{"name","count"}|null}}.
     * {@code item} describes whatever now sits in the selected slot (null when
     * empty), so the caller immediately knows whether the switch was useful.
     */
    public static JsonObject selectResult(int selected, int previous, String item, int count) {
        return selectResult(selected, previous, item, null, count);
    }

    /** v1.6.1：多带一个显示名（模组物品的 id 模型认不出，显示名才认得出）。 */
    public static JsonObject selectResult(int selected, int previous, String item, String display, int count) {
        JsonObject result = new JsonObject();
        result.addProperty("in_game", true);
        result.addProperty("selected", selected);
        result.addProperty("previous", previous);
        if (item == null) {
            result.add("item", JsonNull.INSTANCE);
        } else {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", item);
            if (display != null) {
                entry.addProperty("display", display);
            }
            entry.addProperty("count", count);
            result.add("item", entry);
        }
        return result;
    }

    // ------------------------------------------------------------------ inventory.drop (v1.12)

    public record DropParams(int slot, Integer count) {
    }

    public record DropFacts(int slot, int menuSlot, String item, int requested,
                            int before, int dropped, int remaining) {
    }

    public static DropParams dropParams(JsonObject params) throws ToolContracts.InvalidParams {
        JsonElement slotElement = params.get("slot");
        if (slotElement == null || slotElement.isJsonNull()
                || !slotElement.isJsonPrimitive() || !slotElement.getAsJsonPrimitive().isNumber()) {
            throw new ToolContracts.InvalidParams("inventory.drop requires integer slot 0..40"
                    + " (inventory container index: 0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand)");
        }
        double slot = slotElement.getAsDouble();
        if (!Double.isFinite(slot) || slot != Math.floor(slot) || slot < HOTBAR_START || slot > OFFHAND_SLOT) {
            throw new ToolContracts.InvalidParams("inventory.drop slot must be an integer 0..40, got: " + slot);
        }
        Integer count = null;
        JsonElement countElement = params.get("count");
        if (countElement != null && !countElement.isJsonNull()) {
            if (!countElement.isJsonPrimitive() || !countElement.getAsJsonPrimitive().isNumber()) {
                throw new ToolContracts.InvalidParams("inventory.drop count must be an integer 1..64");
            }
            double value = countElement.getAsDouble();
            if (!Double.isFinite(value) || value != Math.floor(value) || value < 1 || value > 64) {
                throw new ToolContracts.InvalidParams("inventory.drop count must be an integer 1..64, got: " + value);
            }
            count = (int) value;
        }
        return new DropParams((int) slot, count);
    }

    public static String dropVerdict(int requested, int before, int remaining, boolean sameItem) {
        if (remaining < 0 || remaining > before || !sameItem) {
            return "unknown";
        }
        int dropped = before - remaining;
        if (dropped == requested) {
            return "dropped";
        }
        return dropped > 0 && dropped < requested ? "partial" : "unknown";
    }

    public static JsonObject dropResult(String result, DropFacts facts, boolean inGame) {
        JsonObject object = new JsonObject();
        object.addProperty("in_game", inGame);
        object.addProperty("result", result);
        object.addProperty("slot", facts.slot());
        object.addProperty("menu_slot", facts.menuSlot());
        object.add("item", facts.item() != null ? new JsonPrimitive(facts.item()) : JsonNull.INSTANCE);
        object.addProperty("requested", facts.requested());
        object.addProperty("dropped", facts.dropped());
        object.addProperty("before", facts.before());
        object.addProperty("remaining", facts.remaining());
        if (inGame) {
            object.addProperty("evidence", "client_snapshot");
        }
        return object;
    }
}
