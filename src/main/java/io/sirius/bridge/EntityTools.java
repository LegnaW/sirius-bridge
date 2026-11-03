package io.sirius.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The M5.0 v1.6 entity primitives: {@code entity.use} (right-click an entity -
 * trade, feed, milk, shear, mount) and {@code entity.attack} (left-click it,
 * {@code count} times, cooldown-aware). Contract half (params, resolution,
 * reach math, monitor state machines, vocabularies) is {@link EntityContracts};
 * this class owns the game access.
 *
 * <p><b>Why it beats {@code lookAt + input.click}</b>: the server checks only
 * the eye-to-collision-box distance, so a crosshair-perfect aim is not needed -
 * but the CLIENT has to hand the correct entity to the interaction packet, which
 * means resolving the entity object ourselves instead of hoping a raycast picks
 * it. The head still turns toward the box centre (it looks natural and mods that
 * read {@code getLookAngle} see the intended direction), it is just not a
 * dependency of the result.
 *
 * <p><b>Threading</b>: resolution, aim and the action run in a main-thread task
 * ({@link PerceptionTools#callOnMainThread}); the evidence watcher advances one
 * tick at a time from {@link SiriusBridge#onClientTick} so a slow screen or a
 * cooldown never blocks the render thread.
 *
 * <p><b>Guard rails</b>: the dig/place/use preamble - {@code input_enabled},
 * permission tier (screen-aware, {@code observe} always denied), one rate token
 * per call, one {@code INPUT} audit line per call.
 */
final class EntityTools {

    private EntityTools() {
    }

    /** Registers both tools; called once from the BridgeServer constructor. */
    static void registerAll(ToolRegistry tools, InputGuard guard) {
        tools.register("entity.use", (ctx, params) -> useEntity(ctx, params, guard));
        tools.register("entity.attack", (ctx, params) -> attackEntity(ctx, params, guard));
    }

    // ------------------------------------------------------------------ entity.use

    /** What the start task decided for {@code entity.use}. */
    private record UseStart(String kind, String reason, ActiveUse use,
                            EntityContracts.EntityFacts facts, double distance) {
        static UseStart of(String kind, String reason) {
            return new UseStart(kind, reason, null, null, 0.0);
        }
    }

    private static JsonObject useEntity(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        final EntityContracts.EntityUseParams p;
        try {
            p = EntityContracts.entityUseParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        String summary = "method=entity.use " + selector(p.uuid(), p.name(), p.radius()) + " hand="
                + p.hand().wireName() + " at=" + (p.atCenter() ? "center" : "auto");
        JsonObject denied = preamble(ctx, guard, summary);
        if (denied != null) {
            return denied;
        }

        final long startedMs = System.currentTimeMillis();
        final UseStart start = PerceptionTools.callOnMainThread(ctx, () -> startUse(p, guard));
        switch (start.kind()) {
            case KIND_DENIED:
                return permissionDenied(ctx, guard, summary, start.reason());
            case EntityContracts.RESULT_NOT_IN_GAME:
                ctx.audit("INPUT", summary + " result=not_in_game");
                return Json.okResponse(ctx.id(), ToolContracts.notInGame());
            case EntityContracts.RESULT_GONE:
            case EntityContracts.RESULT_OUT_OF_RANGE:
            case EntityContracts.RESULT_GUI_OPEN:
                ctx.audit("INPUT", summary + " result=" + start.kind()
                        + (start.reason() != null ? " reason=" + start.reason() : ""));
                return Json.okResponse(ctx.id(), EntityContracts.entityUseResult(start.kind(), start.facts(),
                        null, null, System.currentTimeMillis() - startedMs, start.reason()));
            default:
                break;
        }

        ActiveUse active = start.use();
        boolean completed = active.done.await(p.timeoutMs() + 1500L, TimeUnit.MILLISECONDS);
        String result = completed && active.result != null ? active.result : UseItemContracts.RESULT_NOT_USING;
        long elapsedMs = completed ? active.elapsedMs() : System.currentTimeMillis() - startedMs;
        JsonObject response = EntityContracts.entityUseResult(result,
                active.facts != null ? active.facts : start.facts(),
                active.interaction, active.screenClass, elapsedMs,
                completed ? active.note : "monitor did not finish (window iconified? self-cleans next tick)");
        ctx.audit("INPUT", summary + " timeout_ms=" + p.timeoutMs()
                + (active.interaction != null ? " interaction=" + active.interaction : "")
                + (active.screenClass != null ? " screen=" + active.screenClass : "")
                + " dist=" + String.format(java.util.Locale.ROOT, "%.2f", start.distance())
                + " result=" + result + " elapsed_ms=" + elapsedMs);
        return Json.okResponse(ctx.id(), response);
    }

    /** Pre-checks + target resolution + aim + monitor installation (main thread). */
    private static UseStart startUse(EntityContracts.EntityUseParams p, InputGuard guard) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null) {
            return UseStart.of(EntityContracts.RESULT_NOT_IN_GAME, null);
        }
        boolean screenOpen = mc.screen != null;
        if (!PermissionContracts.allows(guard.tier(), screenOpen, PermissionContracts.Action.INPUT)) {
            return UseStart.of(KIND_DENIED, String.valueOf(screenOpen));
        }
        if (screenOpen) {
            return UseStart.of(EntityContracts.RESULT_GUI_OPEN,
                    "screen=" + mc.screen.getClass().getSimpleName()
                            + " - close it before right-clicking an entity");
        }
        Resolved resolved = resolve(player, level, p.uuid(), p.name(), p.radius());
        if (resolved == null) {
            return new UseStart(EntityContracts.RESULT_GONE, goneReason(p.uuid(), p.name(), p.radius()),
                    null, null, 0.0);
        }
        if (!EntityContracts.inReach(resolved.distance())) {
            return new UseStart(EntityContracts.RESULT_OUT_OF_RANGE,
                    EntityContracts.outOfRangeMessage(label(resolved.entity()), resolved.distance()),
                    null, resolved.facts(), resolved.distance());
        }
        aim(player, resolved.entity());
        ActiveUse use = installUse(p, resolved);
        return new UseStart(KIND_STARTED, null, use, resolved.facts(), resolved.distance());
    }

    private static final String KIND_STARTED = "started";
    private static final String KIND_DENIED = "denied";

    // ------------------------------------------------------------------ entity.attack

    private static JsonObject attackEntity(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        final EntityContracts.EntityAttackParams p;
        try {
            p = EntityContracts.entityAttackParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        String summary = "method=entity.attack " + selector(p.uuid(), p.name(), p.radius())
                + " count=" + p.count() + " interval_ms=" + p.intervalMs()
                + " wait_cooldown=" + p.waitCooldown();
        JsonObject denied = preamble(ctx, guard, summary);
        if (denied != null) {
            return denied;
        }

        final long startedMs = System.currentTimeMillis();
        final AttackStart start = PerceptionTools.callOnMainThread(ctx, () -> startAttack(p, guard));
        switch (start.kind()) {
            case KIND_DENIED:
                return permissionDenied(ctx, guard, summary, start.reason());
            case EntityContracts.RESULT_NOT_IN_GAME:
                ctx.audit("INPUT", summary + " result=not_in_game");
                return Json.okResponse(ctx.id(), ToolContracts.notInGame());
            case EntityContracts.RESULT_GONE:
            case EntityContracts.RESULT_OUT_OF_RANGE:
            case EntityContracts.RESULT_GUI_OPEN:
            case EntityContracts.RESULT_DEAD:
                ctx.audit("INPUT", summary + " result=" + start.kind()
                        + (start.reason() != null ? " reason=" + start.reason() : ""));
                return Json.okResponse(ctx.id(), EntityContracts.entityAttackResult(start.kind(), start.facts(),
                        0, p.count(), 0L, start.reason()));
            default:
                break;
        }

        ActiveAttack active = start.attack();
        // count * (interval + one tick of slack) plus the screen budget; the
        // monitor also self-times-out, this latch bound only protects the
        // WebSocket thread when ticks stop entirely (iconified window).
        long waitMs = (long) p.count() * (p.intervalMs() + 100L) + 2_000L;
        boolean completed = active.done.await(waitMs, TimeUnit.MILLISECONDS);
        String result = completed && active.result != null ? active.result : UseItemContracts.RESULT_NOT_USING;
        long elapsedMs = completed ? active.elapsedMs() : System.currentTimeMillis() - startedMs;
        JsonObject response = EntityContracts.entityAttackResult(result,
                active.facts != null ? active.facts : start.facts(),
                active.monitor.hits(), active.monitor.miss(), active.monitor.cooldownWaitedMs(),
                completed ? active.note : "monitor did not finish (window iconified? self-cleans next tick)");
        ctx.audit("INPUT", summary + " hits=" + active.monitor.hits() + "/" + p.count()
                + " miss=" + active.monitor.miss()
                + " cooldown_waited_ms=" + active.monitor.cooldownWaitedMs()
                + " dist=" + String.format(java.util.Locale.ROOT, "%.2f", start.distance())
                + " result=" + result + " elapsed_ms=" + elapsedMs);
        return Json.okResponse(ctx.id(), response);
    }

    private record AttackStart(String kind, String reason, ActiveAttack attack,
                               EntityContracts.EntityFacts facts, double distance) {
        static AttackStart of(String kind, String reason) {
            return new AttackStart(kind, reason, null, null, 0.0);
        }
    }

    /** Pre-checks + target resolution + aim + monitor installation (main thread). */
    private static AttackStart startAttack(EntityContracts.EntityAttackParams p, InputGuard guard) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null) {
            return AttackStart.of(EntityContracts.RESULT_NOT_IN_GAME, null);
        }
        boolean screenOpen = mc.screen != null;
        if (!PermissionContracts.allows(guard.tier(), screenOpen, PermissionContracts.Action.INPUT)) {
            return AttackStart.of(KIND_DENIED, String.valueOf(screenOpen));
        }
        if (screenOpen) {
            return AttackStart.of(EntityContracts.RESULT_GUI_OPEN,
                    "screen=" + mc.screen.getClass().getSimpleName()
                            + " - close it before attacking an entity");
        }
        Resolved resolved = resolve(player, level, p.uuid(), p.name(), p.radius());
        if (resolved == null) {
            return new AttackStart(EntityContracts.RESULT_GONE, goneReason(p.uuid(), p.name(), p.radius()),
                    null, null, 0.0);
        }
        if (!EntityContracts.inReach(resolved.distance())) {
            return new AttackStart(EntityContracts.RESULT_OUT_OF_RANGE,
                    EntityContracts.outOfRangeMessage(label(resolved.entity()), resolved.distance()),
                    null, resolved.facts(), resolved.distance());
        }
        if (!alive(resolved.entity())) {
            return new AttackStart(EntityContracts.RESULT_DEAD,
                    label(resolved.entity()) + " is already dead", null, resolved.facts(), resolved.distance());
        }
        aim(player, resolved.entity());
        ActiveAttack attack = installAttack(p, resolved);
        return new AttackStart(KIND_STARTED, null, attack, resolved.facts(), resolved.distance());
    }

    // ------------------------------------------------------------------ resolution + geometry

    /** A resolved target: the live entity plus its response facts and distance. */
    private record Resolved(Entity entity, EntityContracts.EntityFacts facts, double distance) {
    }

    /** The entity list + the shared resolution rule (see {@link EntityContracts#resolve}). */
    private static Resolved resolve(LocalPlayer player, ClientLevel level,
                                    String uuid, String name, double radius) {
        Vec3 eye = player.getEyePosition();
        List<Entity> entities = new ArrayList<>();
        List<EntityContracts.Candidate> candidates = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == player || entity.isRemoved()) {
                continue;
            }
            entities.add(entity);
            candidates.add(new EntityContracts.Candidate(entity.getStringUUID(),
                    entity.getName().getString(), distance(eye, entity)));
        }
        int index = EntityContracts.resolve(candidates, uuid, name, radius);
        if (index < 0 || index >= entities.size()) {
            return null;
        }
        Entity entity = entities.get(index);
        EntityContracts.Candidate candidate = candidates.get(index);
        return new Resolved(entity, new EntityContracts.EntityFacts(
                candidate.name(),
                EntityType.getKey(entity.getType()).toString(),
                entity.getStringUUID(),
                candidate.distance()), candidate.distance());
    }

    /** Eye-to-collision-box distance of one entity (the vanilla reach metric). */
    private static double distance(Vec3 eye, Entity entity) {
        AABB box = entity.getBoundingBox();
        return EntityContracts.distanceToBox(eye.x, eye.y, eye.z,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /**
     * Turns the view toward the box centre. Purely cosmetic (the server only
     * checks distance), so it is set directly instead of using the smooth-turn
     * controller - a fast moving chicken must not eat the whole timeout.
     */
    private static void aim(LocalPlayer player, Entity entity) {
        Vec3 eye = player.getEyePosition();
        Vec3 center = entity.getBoundingBox().getCenter();
        double[] rotation = LookContracts.rotationTowards(eye.x, eye.y, eye.z,
                center.x, center.y, center.z);
        player.setYRot((float) rotation[0]);
        player.setXRot((float) rotation[1]);
        player.setOldPosAndRot(); // keep the render interpolation from lurching
    }

    private static boolean alive(Entity entity) {
        if (entity.isRemoved()) {
            return false;
        }
        return !(entity instanceof LivingEntity living) || living.getHealth() > 0.0F;
    }

    private static String label(Entity entity) {
        return "\"" + entity.getName().getString() + "\" (" + EntityType.getKey(entity.getType()) + ")";
    }

    private static String selector(String uuid, String name, double radius) {
        if (uuid != null) {
            return "uuid=" + uuid;
        }
        if (name != null) {
            return "name=\"" + name + "\"";
        }
        return "nearest radius=" + radius;
    }

    private static String goneReason(String uuid, String name, double radius) {
        return "no entity matched " + selector(uuid, name, radius) + " within " + radius
                + " blocks - walk closer or widen radius (world.query type=entities shows what is around)";
    }

    // ------------------------------------------------------------------ monitors + tick pumps

    /** The single active {@code entity.use}; main-thread confined. */
    private static final class ActiveUse {
        final EntityContracts.EntityUseParams params;
        final Entity target;
        final EntityContracts.EntityFacts facts;
        final EntityContracts.UseMonitor monitor;
        final long startedMs = System.currentTimeMillis();
        final CountDownLatch done = new CountDownLatch(1);
        boolean actionSent;
        volatile String interaction;
        volatile String result;
        volatile String screenClass;
        volatile String note;

        ActiveUse(EntityContracts.EntityUseParams params, Entity target,
                  EntityContracts.EntityFacts facts) {
            this.params = params;
            this.target = target;
            this.facts = facts;
            this.monitor = new EntityContracts.UseMonitor(params.timeoutMs());
        }

        long elapsedMs() {
            return System.currentTimeMillis() - startedMs;
        }

        void complete(String result, String screenClass, String note) {
            this.result = result;
            this.screenClass = screenClass;
            this.note = note;
            done.countDown();
        }
    }

    /** The single active {@code entity.attack}; main-thread confined. */
    private static final class ActiveAttack {
        final EntityContracts.EntityAttackParams params;
        final Entity target;
        final EntityContracts.EntityFacts facts;
        final EntityContracts.AttackMonitor monitor;
        final long startedMs = System.currentTimeMillis();
        final CountDownLatch done = new CountDownLatch(1);
        volatile String result;
        volatile String note;

        ActiveAttack(EntityContracts.EntityAttackParams params, Entity target,
                     EntityContracts.EntityFacts facts) {
            this.params = params;
            this.target = target;
            this.facts = facts;
            this.monitor = new EntityContracts.AttackMonitor(params);
        }

        long elapsedMs() {
            return System.currentTimeMillis() - startedMs;
        }

        void complete(String result, String note) {
            this.result = result;
            this.note = note;
            done.countDown();
        }
    }

    private static ActiveUse activeUse;
    private static ActiveAttack activeAttack;

    /**
     * One ACTIVE entity action at a time across BOTH tools (the class-level
     * single slot the dig/place/use tools use): a new {@code entity.use} or
     * {@code entity.attack} releases whatever was running with
     * {@code not_using}, so two monitors can never fight over the same hand.
     */
    private static void supersede(String why) {
        ActiveUse previousUse = activeUse;
        if (previousUse != null) {
            activeUse = null;
            previousUse.complete(UseItemContracts.RESULT_NOT_USING, null, why);
        }
        ActiveAttack previousAttack = activeAttack;
        if (previousAttack != null) {
            activeAttack = null;
            previousAttack.complete(UseItemContracts.RESULT_NOT_USING, why);
        }
    }

    private static ActiveUse installUse(EntityContracts.EntityUseParams p, Resolved resolved) {
        supersede("superseded by another entity.* call");
        ActiveUse use = new ActiveUse(p, resolved.entity(), resolved.facts());
        activeUse = use;
        return use;
    }

    private static ActiveAttack installAttack(EntityContracts.EntityAttackParams p, Resolved resolved) {
        supersede("superseded by another entity.* call");
        ActiveAttack attack = new ActiveAttack(p, resolved.entity(), resolved.facts());
        activeAttack = attack;
        return attack;
    }

    private static void finishUse(ActiveUse use, String result, String screenClass, String note) {
        if (activeUse == use) {
            activeUse = null;
        }
        use.complete(result, screenClass, note);
    }

    private static void finishAttack(ActiveAttack attack, String result, String note) {
        if (activeAttack == attack) {
            activeAttack = null;
        }
        attack.complete(result, note);
    }

    /** Per-tick pump for both entity tools (from {@link SiriusBridge#onClientTick}). */
    static void onClientTick() {
        pumpUse();
        pumpAttack();
    }

    private static void pumpUse() {
        ActiveUse use = activeUse;
        if (use == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            finishUse(use, EntityContracts.RESULT_NOT_IN_GAME, null, "left the world mid-use");
            return;
        }
        EntityContracts.UseView view = new EntityContracts.UseView(true, mc.screen != null,
                use.actionSent, !use.target.isRemoved(), alive(use.target), use.interaction, use.elapsedMs());
        EntityContracts.Verdict verdict = use.monitor.tick(view);
        if (verdict.action() == EntityContracts.Action.USE) {
            sendUse(mc, player, use);
            // Re-read: a singleplayer host may already have opened the trade screen.
            view = new EntityContracts.UseView(true, mc.screen != null, true,
                    !use.target.isRemoved(), alive(use.target), use.interaction, use.elapsedMs());
            verdict = use.monitor.tick(view);
        }
        if (verdict.terminal()) {
            String screenClass = EntityContracts.RESULT_SCREEN_OPENED.equals(verdict.result()) && mc.screen != null
                    ? mc.screen.getClass().getSimpleName()
                    : null;
            finishUse(use, verdict.result(), screenClass, null);
        }
    }

    private static void pumpAttack() {
        ActiveAttack attack = activeAttack;
        if (attack == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            finishAttack(attack, EntityContracts.RESULT_NOT_IN_GAME, "left the world mid-attack");
            return;
        }
        double distance = distance(player.getEyePosition(), attack.target);
        EntityContracts.AttackView view = new EntityContracts.AttackView(true,
                !attack.target.isRemoved(), alive(attack.target),
                EntityContracts.inReach(distance), player.getAttackStrengthScale(0.0F),
                attack.elapsedMs());
        EntityContracts.Verdict verdict = attack.monitor.tick(view);
        if (verdict.action() == EntityContracts.Action.ATTACK) {
            // gameMode.attack sends the packet and applies the local hit; the swing
            // is what a human's left click does (vanilla startAttack).
            mc.gameMode.attack(player, attack.target);
            player.swing(InteractionHand.MAIN_HAND);
            attack.monitor.noteAttack(attack.elapsedMs());
            verdict = attack.monitor.tick(new EntityContracts.AttackView(true,
                    !attack.target.isRemoved(), alive(attack.target),
                    EntityContracts.inReach(distance), player.getAttackStrengthScale(0.0F),
                    attack.elapsedMs()));
        }
        if (verdict.terminal()) {
            finishAttack(attack, verdict.result(), null);
        }
    }

    /**
     * One {@code entity.use} press: {@code interactAt(box centre)} when
     * {@code at=center} (armour stands, item frames, modded precision blocks),
     * else the plain {@code interact} vanilla uses for the FACE of a villager,
     * horse or cow.
     */
    private static void sendUse(Minecraft mc, LocalPlayer player, ActiveUse use) {
        InteractionHand hand = use.params.hand() == UseContracts.Hand.OFF
                ? InteractionHand.OFF_HAND
                : InteractionHand.MAIN_HAND;
        Vec3 center = use.target.getBoundingBox().getCenter();
        InteractionResult result = use.params.atCenter()
                ? mc.gameMode.interactAt(player, use.target, new EntityHitResult(use.target, center), hand)
                : mc.gameMode.interact(player, use.target, hand);
        if (result != null && result.shouldSwing()) {
            player.swing(hand);
        }
        use.actionSent = true;
        use.interaction = UseTools.interactionName(result);
    }

    // ------------------------------------------------------------------ guard helpers

    /**
     * The shared acting preamble: {@code input_enabled} -> screen-independent
     * permission veto -> one rate token. Returns the error response to send, or
     * {@code null} when the call may proceed.
     */
    private static JsonObject preamble(ToolContext ctx, InputGuard guard, String summary) {
        if (!guard.enabled()) {
            ctx.audit("INPUT", summary + " result=input_disabled");
            return Json.errorResponse(ctx.id(), Json.INPUT_DISABLED, InputContracts.inputDisabledMessage(), null);
        }
        if (PermissionContracts.deniedRegardlessOfScreen(guard.tier(), PermissionContracts.Action.INPUT)) {
            ctx.audit("INPUT_DENIED", "reason=permission tier=" + guard.tier().configName() + " " + summary);
            return Json.errorResponse(ctx.id(), Json.PERMISSION_DENIED,
                    PermissionContracts.deniedMessage(guard.tier(), false, PermissionContracts.Action.INPUT), null);
        }
        if (!guard.tryAcquire()) {
            ctx.audit("INPUT", summary + " result=rate_limited");
            return Json.errorResponse(ctx.id(), Json.RATE_LIMITED,
                    InputContracts.rateLimitedMessage(guard.limitPerSecond()), null);
        }
        return null;
    }

    /** The {@code -32012} denial + INPUT_DENIED audit line (the dig/place/use wording). */
    private static JsonObject permissionDenied(ToolContext ctx, InputGuard guard, String summary,
                                               String screenOpen) {
        boolean screen = Boolean.parseBoolean(screenOpen);
        ctx.audit("INPUT_DENIED", "reason=permission tier=" + guard.tier().configName() + " " + summary
                + " screen=" + screen);
        return Json.errorResponse(ctx.id(), Json.PERMISSION_DENIED,
                PermissionContracts.deniedMessage(guard.tier(), screen, PermissionContracts.Action.INPUT), null);
    }
}
