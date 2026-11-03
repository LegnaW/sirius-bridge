package io.sirius.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class InventoryDropTools {

    private static final long SYNC_TIMEOUT_MS = 2000;
    private static final long SYNC_POLL_MS = 50;

    private InventoryDropTools() {
    }

    static void registerAll(ToolRegistry tools, InputGuard guard) {
        tools.register("inventory.drop", (ctx, params) -> drop(ctx, params, guard));
    }

    private static JsonObject drop(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        final InventoryContracts.DropParams p;
        try {
            p = InventoryContracts.dropParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        String summary = "method=inventory.drop slot=" + p.slot() + " count=" + p.count();
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
        DropStart start = PerceptionTools.callOnMainThread(ctx, () -> {
            Minecraft mc = Minecraft.getInstance();
            screenOpen[0] = mc.screen != null;
            if (!PermissionContracts.allows(guard.tier(), screenOpen[0], PermissionContracts.Action.INPUT)) {
                return null;
            }
            return start(mc, p);
        });
        if (start == null) {
            ctx.audit("INPUT_DENIED", "reason=permission tier=" + guard.tier().configName()
                    + " " + summary + " screen=" + screenOpen[0]);
            return Json.errorResponse(ctx.id(), Json.PERMISSION_DENIED,
                    PermissionContracts.deniedMessage(guard.tier(), screenOpen[0],
                            PermissionContracts.Action.INPUT), null);
        }
        if (start.refusal != null) {
            ctx.audit("INPUT", summary + " result=" + start.refusal.get("result").getAsString());
            return Json.okResponse(ctx.id(), start.refusal);
        }

        long deadline = System.nanoTime() + SYNC_TIMEOUT_MS * 1_000_000L;
        JsonObject result;
        do {
            Thread.sleep(SYNC_POLL_MS);
            result = PerceptionTools.callOnMainThread(ctx, () -> observe(p, start));
            if ("dropped".equals(result.get("result").getAsString())) {
                break;
            }
        } while (System.nanoTime() < deadline);
        ctx.audit("INPUT", summary + " result=" + result.get("result").getAsString()
                + " dropped=" + result.get("dropped").getAsInt()
                + " remaining=" + result.get("remaining").getAsInt());
        return Json.okResponse(ctx.id(), result);
    }

    private static DropStart start(Minecraft mc, InventoryContracts.DropParams p) {
        LocalPlayer player = mc.player;
        int menuSlot = InventoryContracts.menuSlotForPlayerSlot(p.slot());
        if (player == null || mc.level == null || mc.gameMode == null) {
            return new DropStart(InventoryContracts.dropResult("not_in_game",
                    new InventoryContracts.DropFacts(p.slot(), menuSlot, null, 0, 0, 0, 0), false), null, null, 0, 0);
        }
        if (player.containerMenu != player.inventoryMenu) {
            return refusal("container_open", p, menuSlot, null, 0, 0);
        }
        Slot slot = player.inventoryMenu.getSlot(menuSlot);
        ItemStack before = slot.getItem().copy();
        String item = itemId(before);
        int beforeCount = before.getCount();
        int requested = p.count() != null ? p.count() : beforeCount;
        if (before.isEmpty()) {
            return refusal("empty_slot", p, menuSlot, null, 0, requested);
        }
        if (requested > beforeCount) {
            return refusal("count_exceeds_stack", p, menuSlot, item, beforeCount, requested);
        }
        if (!player.inventoryMenu.getCarried().isEmpty()) {
            return refusal("carried_item", p, menuSlot, item, beforeCount, requested);
        }
        if (!slot.mayPickup(player)) {
            return refusal("slot_forbidden", p, menuSlot, item, beforeCount, requested);
        }
        if (p.count() == null) {
            mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, menuSlot, 1,
                    ClickType.THROW, player);
        } else {
            for (int i = 0; i < requested; i++) {
                mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, menuSlot, 0,
                        ClickType.THROW, player);
            }
        }
        return new DropStart(null, before, item, requested, menuSlot);
    }

    private static DropStart refusal(String result, InventoryContracts.DropParams p, int menuSlot,
                                     String item, int before, int requested) {
        return new DropStart(InventoryContracts.dropResult(result,
                new InventoryContracts.DropFacts(p.slot(), menuSlot, item, requested, before, 0, before), true),
                null, null, 0, 0);
    }

    private static JsonObject observe(InventoryContracts.DropParams p, DropStart start) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return InventoryContracts.dropResult("not_in_game",
                    new InventoryContracts.DropFacts(p.slot(), start.menuSlot, null, 0, 0, 0, 0), false);
        }
        ItemStack after = player.getInventory().getItem(p.slot());
        int remaining = after.getCount();
        boolean sameItem = after.isEmpty() || ItemStack.isSameItemSameComponents(start.before, after);
        int dropped = sameItem ? Math.max(0, start.before.getCount() - remaining) : 0;
        String result = InventoryContracts.dropVerdict(start.requested, start.before.getCount(),
                remaining, sameItem);
        return InventoryContracts.dropResult(result, new InventoryContracts.DropFacts(
                p.slot(), start.menuSlot, start.item, start.requested, start.before.getCount(), dropped, remaining), true);
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null ? key.toString() : "unknown";
    }

    private static final class DropStart {
        final JsonObject refusal;
        final ItemStack before;
        final String item;
        final int requested;
        final int menuSlot;

        DropStart(JsonObject refusal, ItemStack before, String item, int requested, int menuSlot) {
            this.refusal = refusal;
            this.before = before;
            this.item = item;
            this.requested = requested;
            this.menuSlot = menuSlot;
        }
    }
}
