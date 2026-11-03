package io.sirius.bridge;

import com.google.gson.JsonObject;

public final class BaritoneContracts {

    private BaritoneContracts() {
    }

    public static void statusParams(JsonObject params) throws ToolContracts.InvalidParams {
        BaritoneApiContracts.emptyParams(params, "baritone.status");
    }
}
