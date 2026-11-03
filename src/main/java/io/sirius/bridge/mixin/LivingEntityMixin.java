package io.sirius.bridge.mixin;

import io.sirius.bridge.ClientDamageSources;
import io.sirius.bridge.CoreEventSources;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "handleDamageEvent", at = @At("HEAD"))
    private void siriusBridge$captureDamageSource(DamageSource source, CallbackInfo ci) {
        ClientDamageSources.record((LivingEntity) (Object) this, source);
    }

    @Inject(method = "handleDamageEvent", at = @At("TAIL"))
    private void siriusBridge$captureHurt(DamageSource source, CallbackInfo ci) {
        CoreEventSources.captureHurt((LivingEntity) (Object) this, source);
    }
}
