package io.sirius.bridge;

import com.google.gson.JsonObject;

import java.util.Map;

/** Pure params and response shapes for the protocol-1.10 block.get primitive. */
final class BlockContracts {

    static final int COORDINATE_LIMIT = 30_000_000;

    record Params(int x, int y, int z) {
    }

    private BlockContracts() {
    }

    static Params params(JsonObject params) throws ToolContracts.InvalidParams {
        return new Params(
                coordinate(params, "x"),
                coordinate(params, "y"),
                coordinate(params, "z"));
    }

    private static int coordinate(JsonObject params, String name) throws ToolContracts.InvalidParams {
        if (!params.has(name)) {
            throw new ToolContracts.InvalidParams("block.get requires integer " + name
                    + " within -" + COORDINATE_LIMIT + ".." + COORDINATE_LIMIT);
        }
        return ToolContracts.integerMember(params, name, "block.get",
                -COORDINATE_LIMIT, COORDINATE_LIMIT, 0);
    }

    static JsonObject result(String status, int x, int y, int z, int minY, int maxY,
                             String block, Map<String, String> state) {
        JsonObject result = new JsonObject();
        result.addProperty("in_game", true);
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("status", status);
        result.addProperty("loaded", "ok".equals(status));
        result.addProperty("min_y", minY);
        result.addProperty("max_y", maxY);
        if (block != null) {
            result.addProperty("block", block);
        } else {
            result.add("block", com.google.gson.JsonNull.INSTANCE);
        }
        JsonObject properties = new JsonObject();
        state.forEach(properties::addProperty);
        result.add("state", properties);
        return result;
    }
}
