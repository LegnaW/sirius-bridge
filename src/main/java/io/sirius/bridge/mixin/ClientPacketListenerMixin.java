package io.sirius.bridge.mixin;

import io.sirius.bridge.CoreEventSources;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void siriusBridge$captureSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        CoreEventSources.captureSlot(packet.getContainerId(), packet.getStateId(),
                "set_slot", packet.getSlot(), packet.getItem());
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void siriusBridge$captureContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        CoreEventSources.captureContainerContent(packet.getContainerId(), packet.getStateId(),
                packet.getItems(), packet.getCarriedItem());
    }

    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void siriusBridge$captureTakeItem(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        CoreEventSources.captureTakeItem(packet);
    }
}
