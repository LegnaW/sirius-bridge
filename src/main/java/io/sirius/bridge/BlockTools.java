package io.sirius.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minecraft-coupled shell for block.get; all game access runs on the client main thread. */
final class BlockTools {

    private BlockTools() {
    }

    static JsonObject get(ToolContext ctx, JsonObject params) throws Exception {
        final BlockContracts.Params p;
        try {
            p = BlockContracts.params(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        JsonObject result = PerceptionTools.callOnMainThread(ctx, () -> read(p));
        return Json.okResponse(ctx.id(), result);
    }

    private static JsonObject read(BlockContracts.Params p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return ToolContracts.notInGame();
        }
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        if (p.y() < minY || p.y() > maxY) {
            return BlockContracts.result("out_of_world", p.x(), p.y(), p.z(), minY, maxY,
                    null, Map.of());
        }
        BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
        if (level.getChunkAt(pos) instanceof EmptyLevelChunk) {
            return BlockContracts.result("unloaded", p.x(), p.y(), p.z(), minY, maxY,
                    null, Map.of());
        }
        BlockState blockState = level.getBlockState(pos);
        var id = BuiltInRegistries.BLOCK.getKey(blockState.getBlock());
        Map<String, String> properties = new LinkedHashMap<>();
        blockState.getValues().forEach((property, value) ->
                properties.put(property.getName(), String.valueOf(value)));
        return BlockContracts.result("ok", p.x(), p.y(), p.z(), minY, maxY,
                id != null ? id.toString() : "unknown", properties);
    }
}
