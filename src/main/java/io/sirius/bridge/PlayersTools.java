package io.sirius.bridge;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The M4.4 v1.4 {@code players} tool: who is on this server, straight from the
 * client's tab-list snapshot.
 *
 * <p><b>Why</b>: {@code world.query(type:"entities")} only reports entities
 * inside the render distance, and the {@code chat} event's {@code sender} is a
 * bare uuid - so "who is here" and "who said that" had no answer. The tab list
 * ({@code ClientPacketListener.getOnlinePlayers()}) is exactly that data and
 * costs nothing to read.
 *
 * <p><b>Source</b>: {@code PlayerInfo} entries - {@code getProfile().getName()}
 * / {@code getId()}, {@code getLatency()}, {@code getGameMode().getName()}
 * ({@code survival}/{@code creative}/{@code adventure}/{@code spectator}).
 * {@code self} is decided by comparing the profile uuid with the local player's
 * uuid - the same identity the server uses, robust against name changes.
 *
 * <p><b>No parameters</b>: any member in {@code params} is a {@code -32602}
 * (the getGuiState discipline). <b>Always allowed</b>: perception, so it never
 * touches the {@link InputGuard}.
 *
 * <p><b>Threading</b>: one main-thread snapshot task via the shared latch;
 * ordering and JSON assembly run in {@link PlayersContracts} (pure).
 */
final class PlayersTools {

    private PlayersTools() {
    }

    /** Registers the tool; called once from the BridgeServer constructor. */
    static void registerAll(ToolRegistry tools) {
        tools.register("players", PlayersTools::players);
    }

    private static JsonObject players(ToolContext ctx, JsonObject params) throws Exception {
        try {
            PlayersContracts.playersParams(params); // frozen schema: empty params
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }

        JsonObject result = PerceptionTools.callOnMainThread(ctx, () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            ClientPacketListener connection = mc.getConnection();
            if (player == null || mc.level == null || connection == null) {
                return PlayersContracts.notInGamePlayers(); // title screen: not an error
            }
            UUID selfId = player.getUUID();
            List<PlayersContracts.PlayerFact> facts = new ArrayList<>();
            for (PlayerInfo info : connection.getOnlinePlayers()) {
                GameProfile profile = info.getProfile();
                UUID id = profile.getId();
                GameType gameMode = info.getGameMode();
                facts.add(new PlayersContracts.PlayerFact(
                        profile.getName(),
                        id != null ? id.toString() : null,
                        selfId.equals(id),
                        info.getLatency(),
                        gameMode != null ? gameMode.getName() : null));
            }
            return PlayersContracts.playersResult(facts);
        });
        return Json.okResponse(ctx.id(), result);
    }
}
