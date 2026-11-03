package io.sirius.bridge;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

public final class ClientDamageSources {

    private static DamageSource latest;
    private static long sequence;
    private static long recordedAt;

    private ClientDamageSources() {
    }

    public static void record(Entity entity, DamageSource source) {
        if (entity instanceof LocalPlayer) {
            latest = source;
            sequence++;
            recordedAt = System.currentTimeMillis();
        }
    }

    public static DamageSource latest() {
        return latest;
    }

    public static long sequence() {
        return sequence;
    }

    public static long recordedAt() {
        return recordedAt;
    }

    public static void clear() {
        latest = null;
        sequence = 0;
        recordedAt = 0;
    }
}
