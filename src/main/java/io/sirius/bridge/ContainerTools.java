package io.sirius.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Action-layer container primitives. Every menu read and click runs on the client main thread. */
final class ContainerTools {

    private ContainerTools() {
    }

    static void registerAll(ToolRegistry tools, InputGuard guard) {
        tools.register("container.clickSlot", (ctx, params) -> clickSlot(ctx, params, guard));
        tools.register("container.transfer", (ctx, params) -> transfer(ctx, params, guard));
        tools.register("container.close", (ctx, params) -> close(ctx, params, guard));
    }

    private static JsonObject clickSlot(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        final ContainerContracts.ClickParams p;
        try {
            p = ContainerContracts.clickParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        return guarded(ctx, guard, "method=container.clickSlot slot=" + p.slot()
                + " button=" + p.button() + " mode=" + p.mode(), () -> {
            Minecraft mc = Minecraft.getInstance();
            AbstractContainerScreen<?> screen = currentScreen(mc);
            if (screen == null) {
                return ContainerContracts.stateResult("no_container", mc.level != null);
            }
            AbstractContainerMenu menu = screen.getMenu();
            if (p.slot() >= menu.slots.size()) {
                return ContainerContracts.stateResult("slot_out_of_range", true);
            }
            List<GuiContracts.SlotFact> before = GuiTools.collectSlots(mc, screen);
            ContainerContracts.StackFact carriedBefore = stackFact(menu.getCarried());
            mc.gameMode.handleInventoryMouseClick(menu.containerId, p.slot(), p.button(),
                    clickType(p.mode()), mc.player);
            List<GuiContracts.SlotFact> after = GuiTools.collectSlots(mc, screen);
            return ContainerContracts.clickResult("clicked", menu.containerId,
                    screen.getClass().getSimpleName(), p, before, after,
                    carriedBefore, stackFact(menu.getCarried()));
        });
    }

    private static JsonObject transfer(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        final ContainerContracts.TransferParams p;
        try {
            p = ContainerContracts.transferParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        return guarded(ctx, guard, "method=container.transfer from=" + p.fromSlot()
                + " to=" + p.toSlot() + " count=" + p.count(), () -> transferOnMain(p));
    }

    private static JsonObject transferOnMain(ContainerContracts.TransferParams p) {
        Minecraft mc = Minecraft.getInstance();
        AbstractContainerScreen<?> screen = currentScreen(mc);
        if (screen == null) {
            return ContainerContracts.stateResult("no_container", mc.level != null);
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (p.fromSlot() >= menu.slots.size() || p.toSlot() >= menu.slots.size()) {
            return ContainerContracts.stateResult("slot_out_of_range", true);
        }
        List<GuiContracts.SlotFact> before = GuiTools.collectSlots(mc, screen);
        ContainerContracts.StackFact carriedBefore = stackFact(menu.getCarried());
        Slot from = menu.slots.get(p.fromSlot());
        Slot to = menu.slots.get(p.toSlot());
        ItemStack source = from.getItem();
        ItemStack target = to.getItem();
        int requested = p.count() == 0 && !source.isEmpty() ? source.getCount() : p.count();
        int capacity = to.getMaxStackSize(target.isEmpty() ? source : target) - target.getCount();
        String refusal = ContainerContracts.transferRefusal(new ContainerContracts.TransferFacts(
                p.fromSlot() == p.toSlot(), !menu.getCarried().isEmpty(), source.isEmpty(),
                source.getCount(), from.mayPickup(mc.player), to.mayPlace(source),
                target.isEmpty() || ItemStack.isSameItemSameComponents(source, target), capacity), requested);
        if (refusal != null) {
            return transferResult(refusal, screen, p, requested, 0, before, carriedBefore);
        }

        int sourceCount = source.getCount();
        String expectedItem = stackFact(source).item();
        click(mc, menu, p.fromSlot(), 0, ClickType.PICKUP);
        if (requested == sourceCount) {
            click(mc, menu, p.toSlot(), 0, ClickType.PICKUP);
        } else {
            for (int i = 0; i < requested; i++) {
                click(mc, menu, p.toSlot(), 1, ClickType.PICKUP);
            }
            if (!menu.getCarried().isEmpty()) {
                click(mc, menu, p.fromSlot(), 0, ClickType.PICKUP);
            }
        }

        List<GuiContracts.SlotFact> after = GuiTools.collectSlots(mc, screen);
        int afterTargetCount = targetCount(after, p.toSlot(), expectedItem);
        int beforeTargetCount = targetCount(before, p.toSlot(), expectedItem);
        int moved = Math.max(0, afterTargetCount - beforeTargetCount);
        boolean complete = moved == requested && menu.getCarried().isEmpty();
        return ContainerContracts.transferResult(complete ? "transferred" : "unknown",
                menu.containerId, screen.getClass().getSimpleName(), p, requested, moved,
                before, after, carriedBefore, stackFact(menu.getCarried()));
    }

    private static JsonObject transferResult(String result, AbstractContainerScreen<?> screen,
                                             ContainerContracts.TransferParams p, int requested, int moved,
                                             List<GuiContracts.SlotFact> before,
                                             ContainerContracts.StackFact carriedBefore) {
        return ContainerContracts.transferResult(result, screen.getMenu().containerId,
                screen.getClass().getSimpleName(), p, requested, moved, before,
                GuiTools.collectSlots(Minecraft.getInstance(), screen), carriedBefore,
                stackFact(screen.getMenu().getCarried()));
    }

    private static int targetCount(List<GuiContracts.SlotFact> slots, int index, String expectedItem) {
        for (GuiContracts.SlotFact slot : slots) {
            if (slot.index() == index) {
                return java.util.Objects.equals(expectedItem, slot.item()) ? slot.count() : 0;
            }
        }
        return 0;
    }

    private static JsonObject close(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        try {
            ContainerContracts.closeParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        return guarded(ctx, guard, "method=container.close", () -> {
            Minecraft mc = Minecraft.getInstance();
            AbstractContainerScreen<?> screen = currentScreen(mc);
            if (screen == null) {
                return ContainerContracts.stateResult("no_container", mc.level != null);
            }
            String screenClass = screen.getClass().getSimpleName();
            screen.onClose();
            return ContainerContracts.closeResult("closed", screenClass);
        });
    }

    private static AbstractContainerScreen<?> currentScreen(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null
                || !(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            return null;
        }
        return mc.player.containerMenu == screen.getMenu() ? screen : null;
    }

    private static void click(Minecraft mc, AbstractContainerMenu menu, int slot, int button, ClickType type) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, mc.player);
    }

    private static ClickType clickType(String mode) {
        return switch (mode) {
            case "quick_move" -> ClickType.QUICK_MOVE;
            case "swap" -> ClickType.SWAP;
            case "clone" -> ClickType.CLONE;
            case "throw" -> ClickType.THROW;
            case "quick_craft" -> ClickType.QUICK_CRAFT;
            case "pickup_all" -> ClickType.PICKUP_ALL;
            default -> ClickType.PICKUP;
        };
    }

    private static ContainerContracts.StackFact stackFact(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ContainerContracts.StackFact.EMPTY;
        }
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new ContainerContracts.StackFact(id != null ? id.toString() : "unknown", stack.getCount());
    }

    private static JsonObject guarded(ToolContext ctx, InputGuard guard, String summary,
                                      java.util.function.Supplier<JsonObject> action) throws Exception {
        if (!guard.enabled()) {
            ctx.audit("INPUT", summary + " result=input_disabled");
            return Json.errorResponse(ctx.id(), Json.INPUT_DISABLED, InputContracts.inputDisabledMessage(), null);
        }
        if (PermissionContracts.deniedRegardlessOfScreen(guard.tier(), PermissionContracts.Action.INPUT)) {
            ctx.audit("INPUT_DENIED", "reason=permission tier=" + guard.tier().configName() + " " + summary);
            return Json.errorResponse(ctx.id(), Json.PERMISSION_DENIED,
                    PermissionContracts.deniedMessage(guard.tier(), true, PermissionContracts.Action.INPUT), null);
        }
        if (!guard.tryAcquire()) {
            ctx.audit("INPUT", summary + " result=rate_limited");
            return Json.errorResponse(ctx.id(), Json.RATE_LIMITED,
                    InputContracts.rateLimitedMessage(guard.limitPerSecond()), null);
        }
        JsonObject result = PerceptionTools.callOnMainThread(ctx, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (!PermissionContracts.allows(guard.tier(), mc.screen != null,
                    PermissionContracts.Action.INPUT)) {
                return null;
            }
            return action.get();
        });
        if (result == null) {
            ctx.audit("INPUT_DENIED", "reason=permission tier=" + guard.tier().configName() + " " + summary);
            return Json.errorResponse(ctx.id(), Json.PERMISSION_DENIED,
                    PermissionContracts.deniedMessage(guard.tier(), true, PermissionContracts.Action.INPUT), null);
        }
        ctx.audit("INPUT", summary + " result=" + result.get("result").getAsString());
        return Json.okResponse(ctx.id(), result);
    }
}
