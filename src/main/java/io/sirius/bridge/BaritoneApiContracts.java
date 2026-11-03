package io.sirius.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Set;

final class BaritoneApiContracts {

    private BaritoneApiContracts() {
    }

    record GoToParams(int x, Integer y, int z, boolean allowBreak, boolean allowPlace) {
    }

    record SettingsParams(Boolean allowBreak, Boolean allowPlace, Boolean chatControl) {
    }

    record FollowParams(String uuid, String name, int followRadius, boolean allowBreak, boolean allowPlace) {
    }

    record TunnelParams(int height, int width, int depth, int timeoutMs) {
    }

    record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                  int corner1X, int corner1Y, int corner1Z,
                  int corner2X, int corner2Y, int corner2Z, String direction) {
        int volume() {
            return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    static GoToParams goToParams(JsonObject params) throws ToolContracts.InvalidParams {
        int x = requiredInt(params, "x", "baritone.goto");
        int z = requiredInt(params, "z", "baritone.goto");
        Integer y = params.has("y") && !params.get("y").isJsonNull()
                ? requiredInt(params, "y", "baritone.goto") : null;
        return new GoToParams(x, y, z,
                ToolContracts.booleanMember(params, "allow_break", false),
                ToolContracts.booleanMember(params, "allow_place", false));
    }

    static SettingsParams settingsParams(JsonObject params) throws ToolContracts.InvalidParams {
        return new SettingsParams(optionalBoolean(params, "allow_break"),
                optionalBoolean(params, "allow_place"), optionalBoolean(params, "chat_control"));
    }

    static FollowParams followParams(JsonObject params) throws ToolContracts.InvalidParams {
        String uuid = optionalText(params, "uuid");
        String name = optionalText(params, "name");
        if ((uuid == null) == (name == null)) {
            throw new ToolContracts.InvalidParams("baritone.follow requires exactly one of uuid or name");
        }
        int followRadius = ToolContracts.integerMember(params, "follow_radius", "baritone.follow", 1, 64, 10);
        return new FollowParams(uuid, name, followRadius,
                ToolContracts.booleanMember(params, "allow_break", false),
                ToolContracts.booleanMember(params, "allow_place", false));
    }

    private static String optionalText(JsonObject params, String member) throws ToolContracts.InvalidParams {
        if (params == null || !params.has(member) || params.get(member).isJsonNull()) {
            return null;
        }
        JsonElement value = params.get(member);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ToolContracts.InvalidParams("baritone.follow " + member + " must be a string");
        }
        String text = value.getAsString().trim();
        return text.isEmpty() ? null : text;
    }

    static TunnelParams tunnelParams(JsonObject params) throws ToolContracts.InvalidParams {
        int height = ToolContracts.integerMember(params, "height", "baritone.tunnel", 2, 16, -1);
        int width = ToolContracts.integerMember(params, "width", "baritone.tunnel", 1, 16, -1);
        int depth = ToolContracts.integerMember(params, "depth", "baritone.tunnel", 1, 256, -1);
        if (height < 0 || width < 0 || depth < 0) {
            throw new ToolContracts.InvalidParams("baritone.tunnel requires height, width and depth");
        }
        int timeout = ToolContracts.integerMember(params, "timeout_ms", "baritone.tunnel",
                5000, 300000, 120000);
        return new TunnelParams(height, width, depth, timeout);
    }

    static void emptyParams(JsonObject params, String tool) throws ToolContracts.InvalidParams {
        if (params != null && !params.isEmpty()) {
            throw new ToolContracts.InvalidParams(tool + " takes no parameters, got: " + params.keySet());
        }
    }

    static Region region(int x, int y, int z, int height, int width, int depth, String direction)
            throws ToolContracts.InvalidParams {
        int h = height - 1;
        int w = width - 1;
        int addition = w % 2 == 0 ? 0 : 1;
        int x1;
        int y1 = y;
        int z1;
        int x2;
        int y2 = y + h;
        int z2;
        switch (direction.toUpperCase(Locale.ROOT)) {
            case "EAST" -> {
                x1 = x;
                z1 = z - w / 2;
                x2 = x + depth;
                z2 = z + w / 2 + addition;
            }
            case "WEST" -> {
                x1 = x;
                z1 = z + w / 2 + addition;
                x2 = x - depth;
                z2 = z - w / 2;
            }
            case "NORTH" -> {
                x1 = x - w / 2;
                z1 = z;
                x2 = x + w / 2 + addition;
                z2 = z - depth;
            }
            case "SOUTH" -> {
                x1 = x + w / 2 + addition;
                z1 = z;
                x2 = x - w / 2;
                z2 = z + depth;
            }
            default -> throw new ToolContracts.InvalidParams("Unsupported tunnel direction: " + direction);
        }
        return new Region(Math.min(x1, x2), y1, Math.min(z1, z2),
                Math.max(x1, x2), y2, Math.max(z1, z2),
                x1, y1, z1, x2, y2, z2, direction.toUpperCase(Locale.ROOT));
    }

    static void requireKeys(JsonObject params, Set<String> allowed, String tool) throws ToolContracts.InvalidParams {
        if (params == null) {
            return;
        }
        for (String key : params.keySet()) {
            if (!allowed.contains(key)) {
                throw new ToolContracts.InvalidParams(tool + " unknown parameter: " + key);
            }
        }
    }

    private static int requiredInt(JsonObject params, String member, String tool) throws ToolContracts.InvalidParams {
        if (params == null || !params.has(member) || params.get(member).isJsonNull()) {
            throw new ToolContracts.InvalidParams(tool + " requires integer " + member);
        }
        JsonElement value = params.get(member);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ToolContracts.InvalidParams(tool + " " + member + " must be an integer");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.floor(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new ToolContracts.InvalidParams(tool + " " + member + " must be a 32-bit integer, got: " + number);
        }
        return (int) number;
    }

    private static Boolean optionalBoolean(JsonObject params, String member) throws ToolContracts.InvalidParams {
        if (params == null || !params.has(member) || params.get(member).isJsonNull()) {
            return null;
        }
        return ToolContracts.booleanMember(params, member, false);
    }
}
