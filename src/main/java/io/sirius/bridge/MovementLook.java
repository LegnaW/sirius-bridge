package io.sirius.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * M4.1 movement look ("moving should turn the head"): while Baritone is
 * navigating and the local player is moving, turn the view toward the MOVEMENT
 * DIRECTION at a fixed angular speed - the head leads where the body actually
 * goes, replacing the fixed-camera strafing the user observed.
 *
 * <p><b>Why this exists (root-cause evidence):</b> during the M4 acceptance
 * 130-block Baritone walk the bridge audit log shows ZERO look/lookAt/turn
 * writes - nothing of ours touched the rotation, yet the camera never
 * followed the movement. Baritone (this build) drives WASD combinations
 * without rotating the view, so the body strafes sideways under a frozen
 * camera. The bridge is the right layer for the fix: rotation writes here
 * ({@link LookTools#applyRotation}) are focus-independent and already
 * server-synced.
 *
 * <p><b>Yield rules (no fight with anything that steers the view on
 * purpose):</b>
 * <ul>
 *   <li>Without active Baritone navigation, including raw keyboard movement -
 *       no write.</li>
 *   <li>An active {@link TurnController} turn (explicit smooth
 *       {@code lookAt}, the dig aim phase) always wins - movement look does
 *       not write a single tick while one is in flight.</li>
 *   <li>Below the speed threshold (standing, tiny nudges, pure vertical
 *       motion) - no write.</li>
 *   <li>Within the yaw deadzone of the movement heading - no write (so when
 *       Baritone DOES rotate, its target and ours coincide and the controller
 *       stays silent instead of fighting).</li>
 * </ul>
 *
 * <p><b>M4.2 pitch leveling:</b> during Baritone navigation, pitch is also
 * gently pushed toward 0 (level gaze) at {@link #PITCH_SPEED_DEG_PER_SEC} -
 * slower than the yaw rate. This closes the M4.1 design gap: dig aims at a
 * tree canopy (pitch ~-40) or the block underfoot (+40), the dig ends and
 * NOBODY re-levels the view - MovementLook used to leave that leftover pitch
 * in place forever, so the bot walked around "head turned right but staring
 * at the sky". The aim itself is still never disturbed: during any active
 * turn (dig aim phase, explicit lookAt) this whole controller stays silent,
 * and leveling only runs WHILE NAVIGATING AND MOVING with a 1 deg deadzone.
 *
 * <p><b>Threading:</b> main thread only ({@code SiriusBridge}'s
 * {@code ClientTickEvent.Post} listener, right after
 * {@link TurnController#onClientTick()}); the decision math is pure
 * ({@link #nextYaw}) and smoke-tested without a game.
 */
final class MovementLook {

    private MovementLook() {
    }

    /** Fixed turn speed while moving (deg/s) - matches the dig aim default. */
    static final double SPEED_DEG_PER_SEC = 300.0;

    /**
     * Fixed pitch-leveling speed while moving (deg/s). Deliberately SLOWER
     * than the yaw rate: snapping the head sideways to the travel direction
     * reads as natural, but a gaze that sweeps down from a canopy aim to level
     * at head-snap speed looks mechanical - humans raise/lower their eyes back
     * to level in an unhurried settle ("比 yaw 慢更像人抬头回平"). 150 deg/s is
     * the middle of the sanctioned 120-180 band and still clears a worst-case
     * +-40 deg dig leftover in ~0.27 s (5-6 ticks), i.e. long before a normal
     * walk leg ends.
     */
    static final double PITCH_SPEED_DEG_PER_SEC = 150.0;

    /**
     * Minimum horizontal speed to count as "moving" (blocks per tick).
     * Walking is ~0.21 b/t at 20 tps; 0.05 (=1 m/s) filters idle drift,
     * knockback wobble and pure vertical motion (falling, swimming up).
     */
    static final double MIN_SPEED = 0.05;

    /** Within this many degrees of the movement heading: no write (deadzone). */
    static final double DEADZONE_DEG = 2.0;

    /**
     * Within this many degrees of LEVEL pitch: no write (deadzone) - stops the
     * controller from emitting endless sub-degree writes every walking tick.
     * Tighter than the yaw deadzone: pitch has no moving target, 0 IS the
     * destination, and the acceptance bound is |pitch| &lt;= 2 deg.
     */
    static final double PITCH_DEADZONE_DEG = 1.0;

    /** ms per client tick - the step math assumes the vanilla 20 tps clock. */
    private static final double TICK_SECONDS = 0.050;

    static boolean shouldApply(boolean enabled, boolean baritoneNavigating) {
        return enabled && baritoneNavigating;
    }

    /**
     * Per-tick advance hook; called from {@code SiriusBridge.onClientTick}.
     * No-op when disabled (config {@code movement_look = false}), when Baritone
     * is not navigating, when no player exists, or when the yield rules say
     * stay silent.
     */
    static void onClientTick(boolean enabled, boolean baritoneNavigating) {
        if (!shouldApply(enabled, baritoneNavigating)) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (TurnController.activeTurn() != null) {
            return; // an explicit turn owns the view this tick
        }
        var velocity = player.getDeltaMovement();
        Double yaw = nextYaw(player.getYRot(), velocity.x, velocity.z, SPEED_DEG_PER_SEC);
        if (yaw != null) {
            LookTools.applyYaw(player, yaw);
        }
        Double pitch = nextPitch(player.getXRot(), velocity.x, velocity.z, PITCH_SPEED_DEG_PER_SEC);
        if (pitch != null) {
            LookTools.applyPitch(player, pitch);
        }
    }

    /**
     * Pure decision: the yaw to write this tick, or {@code null} when the
     * controller must stay silent. Target heading is derived from the
     * horizontal velocity vector exactly like {@link LookContracts#rotationTowards}
     * (yaw 0 = +Z / south, atan2(dz, dx) - 90), the step is a fixed
     * {@code speedDegPerSec * 50ms} along the shortest signed difference.
     */
    static Double nextYaw(double currentYaw, double vx, double vz, double speedDegPerSec) {
        double horizontal = Math.sqrt(vx * vx + vz * vz);
        if (horizontal < MIN_SPEED) {
            return null;
        }
        double targetYaw = LookContracts.wrapDegrees(Math.toDegrees(Math.atan2(vz, vx)) - 90.0);
        double delta = LookContracts.yawDelta(currentYaw, targetYaw);
        if (Math.abs(delta) <= DEADZONE_DEG) {
            return null;
        }
        double step = speedDegPerSec * TICK_SECONDS;
        return LookContracts.approach(currentYaw, delta, step);
    }

    /**
     * Pure decision: the pitch to write this tick, or {@code null} when the
     * controller must stay silent. Same movement gate as {@link #nextYaw}
     * (identical {@link #MIN_SPEED} on the SAME horizontal velocity vector -
     * leveling is a walking behaviour, not a standing one); the target is
     * always 0 (level gaze) and the step is a fixed
     * {@code speedDegPerSec * 50ms} straight down the remaining difference.
     * Pitch never wraps, so the delta is plain {@code 0 - currentPitch} and NO
     * degree normalization is applied here (vanilla clamps pitch at use; a
     * wrapDegrees pass could flip a legit -90..90 value).
     */
    static Double nextPitch(double currentPitch, double vx, double vz, double speedDegPerSec) {
        double horizontal = Math.sqrt(vx * vx + vz * vz);
        if (horizontal < MIN_SPEED) {
            return null;
        }
        if (Math.abs(currentPitch) <= PITCH_DEADZONE_DEG) {
            return null;
        }
        double step = speedDegPerSec * TICK_SECONDS;
        return LookContracts.approach(currentPitch, -currentPitch, step);
    }
}
