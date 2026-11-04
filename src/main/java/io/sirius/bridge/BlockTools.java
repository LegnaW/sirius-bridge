package io.sirius.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
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
        JsonObject result = BlockContracts.result("ok", p.x(), p.y(), p.z(), minY, maxY,
                id != null ? id.toString() : "unknown", properties);
        boolean dry = blockState.getFluidState().isEmpty();
        result.addProperty("air", blockState.isAir() && dry);
        result.addProperty("breakable", blockState.getDestroySpeed(level, pos) >= 0 && dry);
        result.addProperty("passable", passable(level, pos, blockState));
        double height = supportHeight(level, pos, blockState);
        result.addProperty("safe_support", height > 0);
        result.addProperty("support_height", height);
        return result;
    }

    static boolean passable(ClientLevel level, BlockPos pos, BlockState state) {
        return state.getFluidState().isEmpty() && !hazardous(state) && state.getCollisionShape(level, pos).isEmpty();
    }

    static double supportHeight(ClientLevel level, BlockPos pos, BlockState state) {
        return supportHeight(level, pos, state, 0.5, 0.5);
    }

    static double supportHeight(ClientLevel level, BlockPos pos, BlockState state, double x, double z) {
        if (!state.getFluidState().isEmpty() || hazardous(state) || state.getBlock() instanceof FallingBlock) {
            return 0;
        }
        double height = state.getCollisionShape(level, pos).toAabbs().stream()
                .filter(box -> box.minX <= x && box.maxX >= x && box.minZ <= z && box.maxZ >= z)
                .mapToDouble(box -> box.maxY).max().orElse(0);
        return height <= 1 ? height : 0;
    }

    private static boolean hazardous(BlockState state) {
        return state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POINTED_DRIPSTONE);
    }
}
