package io.sirius.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The M4.4 v1.4 {@code inventory} tool: a structured read of the player's own
 * inventory, without opening a screen.
 *
 * <p><b>Why a dedicated primitive</b>: before v1.4 the only way to learn what
 * the player carries was "press E, then getGuiState" - an intrusive detour
 * (it blocks input while the screen is open) whose slot coordinates then have
 * to be converted from GUI-scaled space to window pixels just to click them.
 * A pure perception call answers the same question in one request.
 *
 * <p><b>Slot basis</b>: the shell reads {@code Inventory.getItem(slot)} for
 * the container indices 0..40 (0-8 hotbar, 9-35 main, 36-39 armor, 40
 * offhand) - the same basis as {@code getGuiState}'s {@code container_slot},
 * never {@code Slot.index}. All decisions (scope, filter, counts, roles,
 * emptiness lists) live in {@link InventoryContracts}.
 *
 * <p><b>Always allowed</b>: perception, so it never touches the
 * {@link InputGuard} - no rate-limit token, no permission tier, no
 * {@code input_enabled} gate (the observe tier may read the backpack).
 *
 * <p><b>Threading</b>: one main-thread task reads the stacks (41 cheap reads)
 * and assembles the JSON; the WebSocket thread blocks on the shared latch
 * (10 s, iconified-window protection).
 */
final class InventoryTools {

    private InventoryTools() {
    }

    /** Registers the tools; called once from the BridgeServer constructor. */
    static void registerAll(ToolRegistry tools, InputGuard guard) {
        tools.register("inventory", InventoryTools::inventory);
        // v1.6: switching the held hotbar slot is an ACT, not a read - it rides
        // the same InputGuard preamble (enabled -> permission -> rate token ->
        // INPUT audit) as every other action-layer primitive.
        tools.register("inventory.select", (ctx, params) -> select(ctx, params, guard));
    }

    // ------------------------------------------------------------------ inventory.select (v1.6)

    /**
     * {@code inventory.select({slot})}: point the hotbar selection at
     * {@code slot} (0..8). Only the INDEX changes - items are never moved, so
     * no GUI is opened and no click is synthesised.
     *
     * <p>Screen state is consulted for the PERMISSION matrix only (it decides
     * whether {@code input_gui}/{@code input_world} may act right now); unlike
     * {@code use}/{@code place} an open screen is not a refusal here, because
     * nothing is being injected into the screen - it is a plain field write on
     * the local player, exactly what the number keys do from the HUD.
     */
    private static JsonObject select(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        final InventoryContracts.SelectParams p;
        try {
            p = InventoryContracts.selectParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        String summary = "method=inventory.select slot=" + p.slot();
        if (!guard.enabled()) {
            ctx.audit("INPUT", summary + " result=input_disabled");
            return Json.errorResponse(ctx.id(), Json.INPUT_DISABLED, InputContracts.inputDisabledMessage(), null);
        }
        if (PermissionContracts.deniedRegardlessOfScreen(guard.tier(), PermissionContracts.Action.INPUT)) {
            ctx.audit("INPUT_DENIED", "reason=permission tier=" + guard.tier().configName() + " " + summary);
            return Json.errorResponse(ctx.id(), Json.PERMISSION_DENIED,
                    PermissionContracts.deniedMessage(guard.tier(), false, PermissionContracts.Action.INPUT), null);
        }
        if (!guard.tryAcquire()) {
            ctx.audit("INPUT", summary + " result=rate_limited");
            return Json.errorResponse(ctx.id(), Json.RATE_LIMITED,
                    InputContracts.rateLimitedMessage(guard.limitPerSecond()), null);
        }

        boolean[] screenOpen = {false};
        JsonObject result = PerceptionTools.callOnMainThread(ctx, () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            screenOpen[0] = mc.screen != null;
            if (!PermissionContracts.allows(guard.tier(), screenOpen[0], PermissionContracts.Action.INPUT)) {
                return null; // screen-dependent refusal, decided on the live state
            }
            if (player == null || mc.level == null) {
                return ToolContracts.notInGame(); // title screen: not an error
            }
            Inventory inventory = player.getInventory();
            int previous = inventory.selected;
            inventory.selected = p.slot();
            ItemStack stack = inventory.getItem(p.slot());
            String item = null;
            String display = null;
            int count = 0;
            if (!stack.isEmpty()) {
                var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                item = key != null ? key.toString() : "unknown";
                count = stack.getCount();
                try {
                    String hover = stack.getHoverName().getString();
                    display = hover != null && !hover.isBlank() ? hover : null;
                } catch (RuntimeException ignored) {
                    // 名字取不到不影响切换本身
                }
            }
            return InventoryContracts.selectResult(p.slot(), previous, item, display, count);
        });
        if (result == null) {
            ctx.audit("INPUT_DENIED", "reason=permission tier=" + guard.tier().configName()
                    + " " + summary + " screen=" + screenOpen[0]);
            return Json.errorResponse(ctx.id(), Json.PERMISSION_DENIED,
                    PermissionContracts.deniedMessage(guard.tier(), screenOpen[0],
                            PermissionContracts.Action.INPUT), null);
        }
        boolean inGame = result.has("in_game") && result.get("in_game").getAsBoolean();
        ctx.audit("INPUT", summary + (inGame
                ? " selected=" + result.get("selected").getAsInt()
                + " previous=" + result.get("previous").getAsInt()
                + " result=selected"
                : " result=not_in_game"));
        return Json.okResponse(ctx.id(), result);
    }

    private static JsonObject inventory(ToolContext ctx, JsonObject params) throws Exception {
        final InventoryContracts.InventoryParams p;
        try {
            p = InventoryContracts.inventoryParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }

        JsonObject result = PerceptionTools.callOnMainThread(ctx, () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null || mc.level == null) {
                return InventoryContracts.notInGameResult(); // title screen: not an error
            }
            Inventory inventory = player.getInventory();
            List<InventoryContracts.ItemFact> facts = new ArrayList<>(InventoryContracts.SLOT_COUNT);
            ItemStack[] stacks = new ItemStack[InventoryContracts.SLOT_COUNT];
            for (int slot = 0; slot < InventoryContracts.SLOT_COUNT; slot++) {
                ItemStack stack = inventory.getItem(slot);
                stacks[slot] = stack;
                if (stack.isEmpty()) {
                    facts.add(new InventoryContracts.ItemFact(slot, null, null, 0));
                    continue;
                }
                var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                // v1.6.1: 显示名（客户端语言）与 registry id 一起给——模组物品的 id
                // （create:cogwheel）对模型没有意义，显示名（齿轮）才认得出。
                String display = null;
                try {
                    String hover = stack.getHoverName().getString();
                    display = hover != null && !hover.isBlank() ? hover : null;
                } catch (RuntimeException ignored) {
                    // 取不到就只给 id，不因为一个名字让整次读背包失败
                }
                facts.add(new InventoryContracts.ItemFact(
                        slot, key != null ? key.toString() : null, display, stack.getCount()));
            }
            return InventoryContracts.inventoryResult(inventory.selected, p.scope(), facts, p.filter(),
                    matcher(p.filter(), stacks));
        });
        return Json.okResponse(ctx.id(), result);
    }

    /**
     * The tag/id membership probe. TagKeys resolve once per call; membership is
     * memoized per item id (a handful of distinct ids even in a full backpack)
     * so a 16-tag filter never re-tests the same item 41 times.
     */
    private static InventoryContracts.ItemFilter matcher(InventoryContracts.Filter filter, ItemStack[] stacks) {
        if (filter == null) {
            return (slot, itemId) -> false; // never consulted: no filter, no matching list
        }
        List<TagKey<Item>> tagKeys = new ArrayList<>(filter.tagIds().size());
        for (String tagId : filter.tagIds()) {
            // ItemTags.create == TagKey.create(Registries.ITEM, id); an unknown tag
            // simply matches nothing (holder.is checks set membership).
            tagKeys.add(ItemTags.create(ResourceLocation.parse(tagId)));
        }
        Set<String> ids = new HashSet<>(filter.ids());
        Map<String, Boolean> tagMemo = new HashMap<>();
        return (slot, itemId) -> {
            if (ids.contains(itemId)) {
                return true;
            }
            if (tagKeys.isEmpty()) {
                return false;
            }
            boolean tagged = tagMemo.computeIfAbsent(itemId, key -> {
                ItemStack stack = stackFor(stacks, slot, key);
                if (stack == null) {
                    return false;
                }
                for (TagKey<Item> tag : tagKeys) {
                    if (stack.is(tag)) {
                        return true;
                    }
                }
                return false;
            });
            return tagged;
        };
    }

    /**
     * The stack for a memo lookup: the memo is keyed by item id, so the first
     * slot holding that id answers for all of them (every stack of the same
     * registry id carries the same tags).
     */
    private static ItemStack stackFor(ItemStack[] stacks, int slot, String itemId) {
        ItemStack direct = stacks[slot];
        if (direct != null && !direct.isEmpty()) {
            var key = BuiltInRegistries.ITEM.getKey(direct.getItem());
            if (key != null && key.toString().equals(itemId)) {
                return direct;
            }
        }
        for (ItemStack candidate : stacks) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            var key = BuiltInRegistries.ITEM.getKey(candidate.getItem());
            if (key != null && key.toString().equals(itemId)) {
                return candidate;
            }
        }
        return null;
    }
}
