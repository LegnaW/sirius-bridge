package io.sirius.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Raw client event producers. Filtering and flow policy belong to the connection side. */
public final class CoreEventSources {

    @FunctionalInterface
    interface Sink {
        void push(String type, EventsContracts.EventLevel level, JsonObject data);
    }

    private static CoreEventSources active;

    private final Sink sink;
    private int heldSlot = -1;
    private ItemStack heldStack = ItemStack.EMPTY;
    private JsonObject baritoneBaseline;
    private final Map<LivingEntity, DamageSource> hurtSources = new java.util.WeakHashMap<>();

    CoreEventSources(Sink sink) {
        this.sink = sink;
    }

    void register() {
        active = this;
        BaritoneTools.setEventListener(this::onBaritonePathEvent);
    }

    void shutdown() {
        BaritoneTools.setEventListener(null);
        if (active == this) {
            active = null;
        }
    }

    void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            heldSlot = -1;
            heldStack = ItemStack.EMPTY;
            baritoneBaseline = null;
            return;
        }
        int slot = mc.player.getInventory().selected;
        ItemStack current = mc.player.getInventory().getSelected();
        if (heldSlot >= 0) {
            boolean sameComponents = ItemStack.isSameItemSameComponents(heldStack, current);
            if (CoreEventContracts.heldChanged(heldSlot, slot, stackFact(heldStack), stackFact(current), sameComponents)) {
                emit("held_item_changed", CoreEventContracts.heldData(
                        heldSlot, slot, stackFact(heldStack), stackFact(current)));
            }
        }
        heldSlot = slot;
        heldStack = current.copy();
        emitBaritoneIfChanged("state_changed");
    }

    void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        JsonObject fact = entityFact(event.getEntity());
        emit("entity_spawn", fact);
        if (event.getEntity() instanceof Player) {
            emit("player_join", fact);
        }
    }

    void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        JsonObject fact = entityFact(event.getEntity());
        boolean dead = event.getEntity() instanceof LivingEntity living && living.isDeadOrDying();
        fact.addProperty("dead_or_dying", dead);
        if (dead) {
            LivingEntity living = (LivingEntity) event.getEntity();
            JsonObject death = entityFact(living);
            death.addProperty("health", living.getHealth());
            DamageSource source = hurtSources.get(living);
            if (source != null) {
                addDamageSource(death, source);
            } else {
                death.addProperty("damage_type", "unknown");
            }
            death.addProperty("evidence", "client_entity_leave");
            emit("entity_death", death);
        }
        emit("entity_gone", fact);
        if (event.getEntity() instanceof Player) {
            emit("player_leave", fact);
        }
    }

    void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        JsonObject out = new JsonObject();
        out.addProperty("state", "connected");
        out.add("player", event.getPlayer() != null ? entityFact(event.getPlayer()) : null);
        out.addProperty("evidence", "client_connection_event");
        emit("connection_state", out);
    }

    void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        JsonObject out = new JsonObject();
        out.addProperty("state", "disconnected");
        out.add("player", event.getPlayer() != null ? entityFact(event.getPlayer()) : null);
        out.addProperty("evidence", "client_connection_event");
        emit("connection_state", out);
        if (event.getPlayer() != null) {
            JsonObject player = entityFact(event.getPlayer());
            player.addProperty("evidence", "client_connection_event");
            emit("player_leave", player);
        }
    }

    private void onHurt(LivingEntity entity, DamageSource source) {
        if (!entity.level().isClientSide()) {
            return;
        }
        hurtSources.put(entity, source);
        JsonObject out = entityFact(entity);
        out.addProperty("health", entity.getHealth());
        addDamageSource(out, source);
        out.addProperty("evidence", "client_damage_event");
        emit("entity_hurt", out);
    }

    private void onBlockChange(Level level, BlockPos pos, BlockState before, BlockState after) {
        if (!level.isClientSide() || before == after) {
            return;
        }
        JsonObject out = new JsonObject();
        out.addProperty("x", pos.getX());
        out.addProperty("y", pos.getY());
        out.addProperty("z", pos.getZ());
        out.addProperty("dimension", level.dimension().location().toString());
        out.add("before", blockState(before));
        out.add("after", blockState(after));
        out.addProperty("evidence", "client_block_change");
        emit("block_update", out);
    }

    private void onSlot(int containerId, int stateId, String packet, int slot, ItemStack stack) {
        emit("slot_update", CoreEventContracts.slotData(
                containerId, stateId, packet, slot, stackFact(stack)));
    }

    private void onTakeItem(ClientboundTakeItemEntityPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        JsonObject out = new JsonObject();
        out.addProperty("item_entity_id", packet.getItemId());
        out.addProperty("collector_entity_id", packet.getPlayerId());
        out.addProperty("amount", packet.getAmount());
        Entity item = mc.level.getEntity(packet.getItemId());
        if (item instanceof ItemEntity itemEntity) {
            out.add("item", CoreEventContracts.stack(stackFact(itemEntity.getItem())));
            out.add("entity", entityFact(itemEntity));
        } else {
            out.add("item", CoreEventContracts.stack(CoreEventContracts.StackFact.EMPTY));
        }
        Entity collector = mc.level.getEntity(packet.getPlayerId());
        out.add("collector", collector != null ? entityFact(collector) : null);
        out.addProperty("evidence", "server_packet");
        emit("item_pickup", out);
    }

    private void onBaritonePathEvent() {
        emitBaritoneIfChanged("path_event");
    }

    private void emitBaritoneIfChanged(String reason) {
        JsonObject current = BaritoneTools.statusForEvents();
        if (baritoneBaseline == null) {
            baritoneBaseline = current;
            return;
        }
        if (baritoneBaseline.equals(current)) {
            return;
        }
        baritoneBaseline = current;
        JsonObject out = current.deepCopy();
        out.addProperty("reason", reason);
        out.addProperty("evidence", "baritone_api");
        emit("baritone_state", out);
    }

    private void emit(String type, JsonObject data) {
        sink.push(type, CoreEventContracts.levelOf(type), data);
    }

    static JsonObject entityFact(Entity entity) {
        JsonObject out = new JsonObject();
        out.addProperty("id", entity.getId());
        out.addProperty("uuid", entity.getStringUUID());
        out.addProperty("type", EntityType.getKey(entity.getType()).toString());
        out.addProperty("name", entity.getName().getString());
        JsonObject position = new JsonObject();
        position.addProperty("x", entity.getX());
        position.addProperty("y", entity.getY());
        position.addProperty("z", entity.getZ());
        out.add("position", position);
        out.addProperty("dimension", entity.level().dimension().location().toString());
        return out;
    }

    private static JsonObject blockState(BlockState state) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Map<String, String> properties = new LinkedHashMap<>();
        state.getValues().forEach((property, value) -> properties.put(property.getName(), String.valueOf(value)));
        return CoreEventContracts.blockState(id != null ? id.toString() : "unknown", properties);
    }

    private static CoreEventContracts.StackFact stackFact(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return CoreEventContracts.StackFact.EMPTY;
        }
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new CoreEventContracts.StackFact(id != null ? id.toString() : "unknown", stack.getCount());
    }

    private static void addDamageSource(JsonObject out, DamageSource source) {
        out.addProperty("damage_type", source.typeHolder().unwrapKey()
                .map(key -> key.location().toString()).orElse("unknown"));
        out.add("attacker", source.getEntity() != null ? entityFact(source.getEntity()) : null);
        out.add("direct_entity", source.getDirectEntity() != null ? entityFact(source.getDirectEntity()) : null);
    }

    public static void captureHurt(LivingEntity entity, DamageSource source) {
        CoreEventSources current = active;
        if (current != null) {
            current.onHurt(entity, source);
        }
    }

    public static void captureBlockChange(Level level, BlockPos pos, BlockState before, BlockState after) {
        CoreEventSources current = active;
        if (current != null && before != null && after != null) {
            current.onBlockChange(level, pos, before, after);
        }
    }

    public static void captureSlot(int containerId, int stateId, String packet, int slot, ItemStack stack) {
        CoreEventSources current = active;
        if (current != null) {
            current.onSlot(containerId, stateId, packet, slot, stack);
        }
    }

    public static void captureContainerContent(int containerId, int stateId, List<ItemStack> stacks,
                                               ItemStack carried) {
        for (int i = 0; i < stacks.size(); i++) {
            captureSlot(containerId, stateId, "set_content", i, stacks.get(i));
        }
        captureSlot(containerId, stateId, "set_content_carried", -1, carried);
    }

    public static void captureTakeItem(ClientboundTakeItemEntityPacket packet) {
        CoreEventSources current = active;
        if (current != null) {
            current.onTakeItem(packet);
        }
    }
}
