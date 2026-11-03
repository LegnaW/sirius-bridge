package io.sirius.bridge;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IBuilderProcess;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

final class BaritoneTools {

    private static IBaritone api;
    private static PathEvent lastPathEvent;
    private static long lastPathEventAt;
    private static TunnelTask tunnel;
    private static long tunnelSequence;
    private static Runnable eventListener;

    static void setEventListener(Runnable listener) {
        eventListener = listener;
    }

    static JsonObject statusForEvents() {
        try {
            return statusResult(api());
        } catch (Throwable failure) {
            return unavailable("status", failure);
        }
    }

    private BaritoneTools() {
    }

    static void registerAll(ToolRegistry tools, InputGuard guard) {
        tools.register("baritone.status", BaritoneTools::status);
        tools.register("baritone.goto", (ctx, params) -> goTo(ctx, params, guard));
        tools.register("baritone.follow", (ctx, params) -> follow(ctx, params, guard));
        tools.register("baritone.settings", (ctx, params) -> settings(ctx, params, guard));
        tools.register("baritone.stop", (ctx, params) -> stop(ctx, params, guard));
        tools.register("baritone.tunnel", (ctx, params) -> tunnel(ctx, params, guard));
    }

    private static JsonObject status(ToolContext ctx, JsonObject params) throws Exception {
        try {
            BaritoneContracts.statusParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        JsonObject result = PerceptionTools.callOnMainThread(ctx, () -> {
            try {
                IBaritone baritone = api();
                return statusResult(baritone);
            } catch (Throwable t) {
                return unavailable("status", t);
            }
        });
        ctx.audit("PERCEPTION", "method=baritone.status source=api state=" + result.get("state").getAsString()
                + " available=" + result.get("available").getAsBoolean());
        return Json.okResponse(ctx.id(), result);
    }

    private static JsonObject goTo(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        final BaritoneApiContracts.GoToParams p;
        try {
            BaritoneApiContracts.requireKeys(params, java.util.Set.of("x", "y", "z", "allow_break", "allow_place"),
                    "baritone.goto");
            p = BaritoneApiContracts.goToParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        String summary = p.x() + "," + p.y() + "," + p.z();
        return action(ctx, guard, "baritone.goto", summary, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                return notInGame("goto");
            }
            IBaritone baritone = api();
            interruptTunnel("superseded_by_goto");
            baritone.getPathingBehavior().cancelEverything();
            BaritoneAPI.getSettings().allowBreak.value = p.allowBreak();
            BaritoneAPI.getSettings().allowPlace.value = p.allowPlace();
            Goal goal = p.y() != null ? new GoalBlock(p.x(), p.y(), p.z()) : new GoalXZ(p.x(), p.z());
            baritone.getCustomGoalProcess().setGoalAndPath(goal);
            lastPathEvent = null;
            lastPathEventAt = System.currentTimeMillis();
            JsonObject result = started("goto");
            result.addProperty("goal", goal.toString());
            result.add("target", target(p.x(), p.y(), p.z()));
            result.addProperty("allow_break", p.allowBreak());
            result.addProperty("allow_place", p.allowPlace());
            return result;
        });
    }

    private static JsonObject follow(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        final BaritoneApiContracts.FollowParams p;
        try {
            BaritoneApiContracts.requireKeys(params,
                    java.util.Set.of("uuid", "name", "follow_radius", "allow_break", "allow_place"), "baritone.follow");
            p = BaritoneApiContracts.followParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        String summary = (p.uuid() != null ? p.uuid() : p.name()) + " radius=" + p.followRadius();
        return action(ctx, guard, "baritone.follow", summary, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                return notInGame("follow");
            }
            Entity selected = null;
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof net.minecraft.world.entity.player.Player) || entity == mc.player) {
                    continue;
                }
                boolean matches = p.uuid() != null
                        ? entity.getStringUUID().equalsIgnoreCase(p.uuid())
                        : entity.getName().getString().equalsIgnoreCase(p.name());
                if (matches) {
                    selected = entity;
                    break;
                }
            }
            JsonObject result = started("follow");
            result.addProperty("operation", "follow");
            result.addProperty("follow_radius", p.followRadius());
            result.addProperty("allow_break", p.allowBreak());
            result.addProperty("allow_place", p.allowPlace());
            if (selected == null) {
                result.addProperty("result", "target_not_found");
                result.addProperty("started", false);
                result.addProperty("reason", "the named player is not in the client entity tracking range");
                return result;
            }
            IBaritone baritone = api();
            interruptTunnel("superseded_by_follow");
            baritone.getPathingBehavior().cancelEverything();
            BaritoneAPI.getSettings().allowBreak.value = p.allowBreak();
            BaritoneAPI.getSettings().allowPlace.value = p.allowPlace();
            BaritoneAPI.getSettings().followRadius.value = p.followRadius();
            Entity targetEntity = selected;
            baritone.getFollowProcess().follow(entity -> entity == targetEntity);
            lastPathEvent = null;
            lastPathEventAt = System.currentTimeMillis();
            result.addProperty("result", "following");
            result.add("target", entityFact(selected));
            return result;
        });
    }

    private static JsonObject settings(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        final BaritoneApiContracts.SettingsParams p;
        try {
            BaritoneApiContracts.requireKeys(params, java.util.Set.of("allow_break", "allow_place", "chat_control"),
                    "baritone.settings");
            p = BaritoneApiContracts.settingsParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        return action(ctx, guard, "baritone.settings", "", () -> {
            IBaritone baritone = api();
            if (p.allowBreak() != null) {
                BaritoneAPI.getSettings().allowBreak.value = p.allowBreak();
            }
            if (p.allowPlace() != null) {
                BaritoneAPI.getSettings().allowPlace.value = p.allowPlace();
            }
            if (p.chatControl() != null) {
                BaritoneAPI.getSettings().chatControl.value = p.chatControl();
            }
            JsonObject result = settingsResult();
            result.addProperty("result", "updated");
            result.addProperty("process", baritone.getPathingControlManager().mostRecentInControl()
                    .map(Object::toString).orElse(null));
            return result;
        });
    }

    private static JsonObject stop(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        try {
            BaritoneApiContracts.emptyParams(params, "baritone.stop");
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        return action(ctx, guard, "baritone.stop", "", () -> {
            IBaritone baritone = api();
            interruptTunnel("stopped");
            baritone.getFollowProcess().cancel();
            baritone.getPathingBehavior().cancelEverything();
            lastPathEvent = PathEvent.CANCELED;
            lastPathEventAt = System.currentTimeMillis();
            JsonObject result = new JsonObject();
            result.addProperty("result", "stopped");
            result.addProperty("stopped", true);
            result.addProperty("available", true);
            return result;
        });
    }

    private static JsonObject tunnel(ToolContext ctx, JsonObject params, InputGuard guard) throws Exception {
        final BaritoneApiContracts.TunnelParams p;
        try {
            BaritoneApiContracts.requireKeys(params, java.util.Set.of("height", "width", "depth", "timeout_ms"),
                    "baritone.tunnel");
            p = BaritoneApiContracts.tunnelParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        String summary = p.height() + "x" + p.width() + "x" + p.depth();
        return action(ctx, guard, "baritone.tunnel", summary, () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            ClientLevel level = mc.level;
            if (player == null || level == null) {
                return notInGame("tunnel");
            }
            IBaritone baritone = api();
            interruptTunnel("superseded_by_tunnel");
            baritone.getPathingBehavior().cancelEverything();
            BaritoneAPI.getSettings().allowBreak.value = true;
            BaritoneAPI.getSettings().allowPlace.value = false;
            BaritoneAPI.getSettings().chatControl.value = false;
            BlockPos feet = player.blockPosition();
            BaritoneApiContracts.Region region = BaritoneApiContracts.region(feet.getX(), feet.getY(), feet.getZ(),
                    p.height(), p.width(), p.depth(), player.getDirection().name());
            TunnelTask task = new TunnelTask(++tunnelSequence, p, region, player.getX(), player.getY(), player.getZ());
            tunnel = task;
            lastPathEvent = null;
            lastPathEventAt = task.startedAt;
            baritone.getBuilderProcess().clearArea(
                    new BlockPos(region.corner1X(), region.corner1Y(), region.corner1Z()),
                    new BlockPos(region.corner2X(), region.corner2Y(), region.corner2Z()));
            JsonObject result = started("tunnel");
            result.addProperty("operation_id", task.id);
            result.add("region", regionJson(region));
            result.addProperty("direction", region.direction());
            result.addProperty("timeout_ms", p.timeoutMs());
            return result;
        });
    }

    static void onClientTick() {
        TunnelTask task = tunnel;
        if (task == null || !task.active) {
            return;
        }
        try {
            IBaritone baritone = api();
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            ClientLevel level = mc.level;
            long now = System.currentTimeMillis();
            if (player == null || level == null) {
                finishTunnel(task, "not_in_game", "left game", -1, false);
                return;
            }
            double projection = (player.getX() - task.startX) * task.directionX
                    + (player.getZ() - task.startZ) * task.directionZ;
            task.bestProjection = Math.max(task.bestProjection, projection);
            double moved = Math.hypot(player.getX() - task.anchorX, player.getZ() - task.anchorZ);
            if (moved >= 0.75) {
                task.anchorX = player.getX();
                task.anchorZ = player.getZ();
                task.lastProgressAt = now;
            }
            task.lastX = player.getX();
            task.lastY = player.getY();
            task.lastZ = player.getZ();
            IBuilderProcess builder = baritone.getBuilderProcess();
            if (!builder.isActive()) {
                int remaining = remaining(level, task.region);
                finishTunnel(task, remaining == 0 ? "done" : "blocked",
                        remaining == 0 ? "region is air" : "builder stopped with " + remaining + " blocks remaining",
                        remaining, true);
                return;
            }
            if (builder.isPaused()) {
                finishTunnel(task, "blocked", "builder paused", -1, true);
                return;
            }
            if (now - task.startedAt >= task.params.timeoutMs()) {
                finishTunnel(task, "timeout", "timeout_ms exceeded", -1, true);
                return;
            }
            if (now - task.lastProgressAt >= 4500) {
                boolean noPath = lastPathEvent == PathEvent.CALC_FAILED && lastPathEventAt >= task.startedAt;
                String result = noPath ? "no_path" : task.bestProjection < 1 ? "no_progress" : "blocked";
                finishTunnel(task, result, "no movement for 4.5s", -1, true);
            }
        } catch (Throwable t) {
            finishTunnel(task, "blocked", "API failure: " + t.getClass().getSimpleName(), -1, true);
        }
    }

    static boolean isNavigating() {
        try {
            IBaritone baritone = api();
            return baritone.getPathingBehavior().isPathing()
                    || baritone.getPathingBehavior().hasPath()
                    || baritone.getPathingBehavior().getInProgress().isPresent()
                    || baritone.getFollowProcess().isActive()
                    || baritone.getBuilderProcess().isActive();
        } catch (Throwable failure) {
            return false;
        }
    }

    private static IBaritone api() {
        if (api == null) {
            api = BaritoneAPI.getProvider().getPrimaryBaritone();
            api.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    lastPathEvent = event;
                    lastPathEventAt = System.currentTimeMillis();
                    if (eventListener != null) {
                        eventListener.run();
                    }
                }
            });
        }
        return api;
    }

    private static JsonObject statusResult(IBaritone baritone) {
        boolean calculating = baritone.getPathingBehavior().getInProgress().isPresent();
        boolean pathing = baritone.getPathingBehavior().isPathing();
        boolean hasPath = baritone.getPathingBehavior().hasPath();
        boolean builderActive = baritone.getBuilderProcess().isActive();
        boolean followActive = baritone.getFollowProcess().isActive();
        Goal goal = baritone.getPathingBehavior().getGoal();
        String state;
        if (calculating) {
            state = "calculating";
        } else if (followActive) {
            state = "following";
        } else if (pathing || hasPath || builderActive || tunnel != null && tunnel.active) {
            state = "pathing";
        } else if (lastPathEvent == PathEvent.AT_GOAL) {
            state = "arrived";
        } else if (lastPathEvent == PathEvent.CALC_FAILED || lastPathEvent == PathEvent.NEXT_CALC_FAILED) {
            state = "no_path";
        } else {
            state = "idle";
        }
        JsonObject result = new JsonObject();
        result.addProperty("state", state);
        result.addProperty("goal", goal != null ? goal.toString() : null);
        result.addProperty("last_line", lastPathEvent != null ? lastPathEvent.name() : null);
        result.add("lines", new JsonArray());
        result.add("at_ms", lastPathEventAt != 0
                ? new com.google.gson.JsonPrimitive(lastPathEventAt) : JsonNull.INSTANCE);
        result.addProperty("captured", true);
        result.addProperty("available", true);
        result.addProperty("source", "api");
        result.addProperty("pathing", pathing);
        result.addProperty("has_path", hasPath);
        result.addProperty("calculating", calculating);
        result.addProperty("builder_active", builderActive);
        result.addProperty("builder_paused", baritone.getBuilderProcess().isPaused());
        result.addProperty("last_event", lastPathEvent != null ? lastPathEvent.name() : null);
        result.addProperty("estimated_ticks", baritone.getPathingBehavior().estimatedTicksToGoal().orElse(null));
        result.addProperty("process", baritone.getPathingControlManager().mostRecentInControl()
                .map(process -> process.displayName()).orElse(null));
        result.add("settings", settingsResult());
        result.add("follow", followResult(baritone, followActive));
        if (tunnel != null) {
            result.add("tunnel", tunnelJson(tunnel));
        }
        return result;
    }

    private static JsonObject followResult(IBaritone baritone, boolean active) {
        JsonObject result = new JsonObject();
        result.addProperty("active", active);
        result.addProperty("radius", BaritoneAPI.getSettings().followRadius.value);
        JsonArray targets = new JsonArray();
        java.util.List<Entity> following = baritone.getFollowProcess().following();
        if (following != null) {
            LocalPlayer me = Minecraft.getInstance().player;
            for (Entity entity : following) {
                JsonObject fact = entityFact(entity);
                if (me != null) {
                    fact.addProperty("distance", entity.distanceTo(me));
                }
                targets.add(fact);
            }
        }
        result.add("targets", targets);
        return result;
    }

    private static JsonObject entityFact(Entity entity) {
        JsonObject result = new JsonObject();
        result.addProperty("name", entity.getName().getString());
        result.addProperty("type", EntityType.getKey(entity.getType()).toString());
        result.addProperty("uuid", entity.getStringUUID());
        result.add("position", position(entity.getX(), entity.getY(), entity.getZ()));
        return result;
    }

    private static JsonObject action(ToolContext ctx, InputGuard guard, String method, String summary,
                                     ApiCall call) throws Exception {
        if (!guard.enabled()) {
            return Json.errorResponse(ctx.id(), Json.INPUT_DISABLED, InputContracts.inputDisabledMessage(), null);
        }
        if (PermissionContracts.deniedRegardlessOfScreen(guard.tier(), PermissionContracts.Action.BARITONE)) {
            return Json.errorResponse(ctx.id(), Json.PERMISSION_DENIED,
                    PermissionContracts.deniedMessage(guard.tier(), false, PermissionContracts.Action.BARITONE), null);
        }
        if (!guard.tryAcquire()) {
            return Json.errorResponse(ctx.id(), Json.RATE_LIMITED,
                    InputContracts.rateLimitedMessage(guard.limitPerSecond()), null);
        }
        JsonObject result = PerceptionTools.callOnMainThread(ctx, () -> {
            try {
                return call.run();
            } catch (Throwable t) {
                return unavailable(method, t);
            }
        });
        ctx.audit("INPUT", "method=" + method + " " + summary + " source=api result="
                + (result.has("result") ? result.get("result").getAsString() : "ok"));
        return Json.okResponse(ctx.id(), result);
    }

    private static void interruptTunnel(String reason) {
        TunnelTask task = tunnel;
        if (task != null && task.active) {
            finishTunnel(task, "interrupted", reason, -1, false);
        }
    }

    private static void finishTunnel(TunnelTask task, String result, String reason, int remaining, boolean cancel) {
        if (!task.active) {
            return;
        }
        task.active = false;
        task.finishedAt = System.currentTimeMillis();
        task.result = result;
        task.reason = reason;
        task.remaining = remaining;
        if (cancel) {
            api().getPathingBehavior().cancelEverything();
        }
        BaritoneAPI.getSettings().allowBreak.value = false;
        BaritoneAPI.getSettings().allowPlace.value = false;
    }

    private static int remaining(ClientLevel level, BaritoneApiContracts.Region region) {
        int remaining = 0;
        for (BlockPos pos : BlockPos.betweenClosed(region.minX(), region.minY(), region.minZ(),
                region.maxX(), region.maxY(), region.maxZ())) {
            if (!level.getBlockState(pos).isAir()) {
                remaining++;
            }
        }
        return remaining;
    }

    private static JsonObject settingsResult() {
        JsonObject result = new JsonObject();
        result.addProperty("allow_break", BaritoneAPI.getSettings().allowBreak.value);
        result.addProperty("allow_place", BaritoneAPI.getSettings().allowPlace.value);
        result.addProperty("chat_control", BaritoneAPI.getSettings().chatControl.value);
        result.addProperty("available", true);
        return result;
    }

    private static JsonObject tunnelJson(TunnelTask task) {
        JsonObject result = new JsonObject();
        result.addProperty("operation_id", task.id);
        result.addProperty("active", task.active);
        result.addProperty("result", task.active ? "running" : task.result);
        result.addProperty("reason", task.reason);
        result.addProperty("height", task.params.height());
        result.addProperty("width", task.params.width());
        result.addProperty("depth", task.params.depth());
        result.addProperty("direction", task.region.direction());
        result.addProperty("progress", task.bestProjection);
        result.addProperty("elapsed_ms", (task.active ? System.currentTimeMillis() : task.finishedAt) - task.startedAt);
        result.addProperty("remaining", task.remaining);
        result.add("region", regionJson(task.region));
        result.add("from", position(task.startX, task.startY, task.startZ));
        result.add("to", position(task.lastX, task.lastY, task.lastZ));
        result.addProperty("allow_break", true);
        result.addProperty("allow_place", false);
        return result;
    }

    private static JsonObject regionJson(BaritoneApiContracts.Region region) {
        JsonObject result = new JsonObject();
        result.add("min", position(region.minX(), region.minY(), region.minZ()));
        result.add("max", position(region.maxX(), region.maxY(), region.maxZ()));
        result.addProperty("volume", region.volume());
        return result;
    }

    private static JsonObject target(int x, Integer y, int z) {
        JsonObject result = new JsonObject();
        result.addProperty("x", x);
        if (y != null) {
            result.addProperty("y", y);
        }
        result.addProperty("z", z);
        return result;
    }

    private static JsonObject position(double x, double y, double z) {
        JsonObject result = new JsonObject();
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        return result;
    }

    private static JsonObject started(String operation) {
        JsonObject result = new JsonObject();
        result.addProperty("result", "started");
        result.addProperty("started", true);
        result.addProperty("operation", operation);
        result.addProperty("available", true);
        return result;
    }

    private static JsonObject notInGame(String operation) {
        JsonObject result = new JsonObject();
        result.addProperty("result", "not_in_game");
        result.addProperty("started", false);
        result.addProperty("operation", operation);
        result.addProperty("in_game", false);
        return result;
    }

    private static JsonObject unavailable(String operation, Throwable failure) {
        JsonObject result = new JsonObject();
        result.addProperty("result", "api_unavailable");
        result.addProperty("operation", operation);
        result.addProperty("available", false);
        result.addProperty("captured", false);
        result.addProperty("state", "unknown");
        result.addProperty("source", "api");
        result.addProperty("reason", "Baritone API build is required: " + failure.getClass().getSimpleName());
        return result;
    }

    @FunctionalInterface
    private interface ApiCall {
        JsonObject run() throws Exception;
    }

    private static final class TunnelTask {
        private final long id;
        private final BaritoneApiContracts.TunnelParams params;
        private final BaritoneApiContracts.Region region;
        private final double startX;
        private final double startY;
        private final double startZ;
        private final long startedAt = System.currentTimeMillis();
        private long lastProgressAt = startedAt;
        private double anchorX;
        private double anchorZ;
        private double lastX;
        private double lastY;
        private double lastZ;
        private double bestProjection;
        private final int directionX;
        private final int directionZ;
        private boolean active = true;
        private long finishedAt;
        private String result;
        private String reason;
        private int remaining = -1;

        private TunnelTask(long id, BaritoneApiContracts.TunnelParams params,
                           BaritoneApiContracts.Region region, double x, double y, double z) {
            this.id = id;
            this.params = params;
            this.region = region;
            this.startX = x;
            this.startY = y;
            this.startZ = z;
            this.anchorX = x;
            this.anchorZ = z;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.directionX = "EAST".equals(region.direction()) ? 1 : "WEST".equals(region.direction()) ? -1 : 0;
            this.directionZ = "SOUTH".equals(region.direction()) ? 1 : "NORTH".equals(region.direction()) ? -1 : 0;
        }
    }
}
