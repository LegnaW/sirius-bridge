package io.sirius.bridge;

import baritone.api.BaritoneAPI;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;

/** Main-thread hotbar selection using actual block and item data. */
final class AutoTool {
    private AutoTool() {}

    static boolean select(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState state) {
        var settings = BaritoneAPI.getSettings();
        var inventory = player.getInventory();
        int selected = inventory.selected;
        DigContracts.ToolCandidate best = null;
        // Visit the held slot first to keep equivalent choices stable.
        try {
            for (int offset = 0; offset < 9; offset++) {
                int slot = (selected + offset) % 9;
                ItemStack stack = inventory.getItem(slot);
                if (!settings.useSwordToMine.value && stack.is(ItemTags.SWORDS)) continue;
                if (settings.itemSaver.value && stack.isDamageableItem()
                        && stack.getMaxDamage() - stack.getDamageValue() <= settings.itemSaverThreshold.value) continue;
                // NeoForge's position-sensitive harvest hook reads the selected stack.
                inventory.selected = slot;
                boolean correct = player.hasCorrectToolForDrops(state, level, pos);
                double speed = stack.getDestroySpeed(state);
                if (speed > 1) speed += efficiency(stack);
                if (!Double.isFinite(speed) || speed < 0) continue;
                boolean silk = stack.getEnchantments().keySet().stream()
                        .anyMatch(enchantment -> enchantment.is(Enchantments.SILK_TOUCH));
                int cost = stack.getItem() instanceof TieredItem tool
                        ? (int) tool.getTier().getAttackDamageBonus() : stack.isDamageableItem() ? 0 : -1;
                var candidate = new DigContracts.ToolCandidate(slot, correct, speed, silk, cost);
                if (DigContracts.betterTool(candidate, best, settings.preferSilkTouch.value)) best = candidate;
            }
        } finally {
            inventory.selected = selected;
        }
        if (best == null || !best.correct()) return false;
        inventory.selected = best.slot();
        return true;
    }

    private static double efficiency(ItemStack stack) {
        var attribute = new AttributeInstance(Attributes.MINING_EFFICIENCY, ignored -> {});
        attribute.setBaseValue(0);
        // ItemStack iteration already includes enchantment and NeoForge attribute modifiers.
        stack.forEachModifier(EquipmentSlot.MAINHAND, (type, modifier) -> {
            if (type.equals(Attributes.MINING_EFFICIENCY)) attribute.addOrUpdateTransientModifier(modifier);
        });
        return attribute.getValue();
    }
}
