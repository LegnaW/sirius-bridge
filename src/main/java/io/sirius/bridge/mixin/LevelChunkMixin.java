package io.sirius.bridge.mixin;

import io.sirius.bridge.CoreEventSources;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void siriusBridge$captureBlockChange(BlockPos pos, BlockState state, boolean moved,
                                                 CallbackInfoReturnable<BlockState> cir) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        BlockPos worldPos = new BlockPos(chunk.getPos().getMinBlockX() + (pos.getX() & 15), pos.getY(),
                chunk.getPos().getMinBlockZ() + (pos.getZ() & 15));
        CoreEventSources.captureBlockChange(chunk.getLevel(), worldPos, cir.getReturnValue(), state);
    }
}
