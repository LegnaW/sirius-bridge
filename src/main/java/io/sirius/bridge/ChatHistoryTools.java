package io.sirius.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The M5.0 v1.6 {@code chat.history} tool and its chat CAPTURE pump.
 * Baritone status moved to the direct API integration in v1.7.
 *
 * <p><b>Why reflection (and why it may degrade)</b>: the received-message list
 * lives in {@code ChatComponent} with no public accessor, and this project
 * forbids mixins (see the v1.6 spec), so the tick pump reads the private field.
 * 1.21.1 (Mojmap) names the two candidates {@code allMessages} (records with a
 * {@code content()} {@link Component}) and {@code trimmedMessages} (already
 * word-wrapped lines). Both are tried; if neither yields text the capture is
 * disabled ONCE with a log line and {@code chat.history} keeps answering
 * {@code captured:false} instead of throwing every tick.
 *
 * <p><b>Threading</b>: the pump runs from {@link SiriusBridge#onClientTick}
 * (client main thread) and the handlers read the ring through the shared
 * {@link PerceptionTools#callOnMainThread} latch, so the ring itself needs no
 * locking - every access is on that one thread.
 *
 * <p><b>Always allowed</b>: both tools are pure perception, so they never touch
 * the {@link InputGuard} (no {@code input_enabled} gate, no rate token, no
 * permission tier - {@code observe} may read chat), but every call still
 * writes one {@code PERCEPTION} audit line.
 *
 * <p><b>Source attribution</b>: {@link EventPusher#onChatReceived} already sees
 * every SERVER chat line; it forwards the text to
 * {@link #noteServerChat(String)} so the capture can label a line
 * {@code server}. Anything not seen there is labelled {@code client} (Baritone
 * receipts, mod notices, command feedback) - the deliberate "do not guess"
 * rule.
 */
final class ChatHistoryTools {

    private ChatHistoryTools() {
    }

    /** The captured chat ring (main-thread confined). */
    private static final ChatHistoryContracts.ChatRing RING = new ChatHistoryContracts.ChatRing(ChatHistoryContracts.RING_SIZE);

    /** Recently seen server-chat texts, for source attribution. */
    private static final ChatHistoryContracts.ServerTextLog SERVER_TEXT =
            new ChatHistoryContracts.ServerTextLog(ChatHistoryContracts.SERVER_TEXT_MEMORY);

    /** The previous capture window (oldest first), for the new-suffix diff. */
    private static final List<String> WINDOW = new ArrayList<>();

    // ------------------------------------------------------------------ reflection state

    /** {@code ChatComponent.allMessages} (preferred) or {@code trimmedMessages}. */
    private static Field messagesField;
    /** {@code GuiMessage.content()} - a record accessor, resolved from the first element. */
    private static Method contentMethod;
    /** True once a probe ran (success or not). */
    private static boolean probed;
    /** False until the first successful capture (reported as {@code captured}). */
    private static boolean captureAvailable;
    /** True when the reflective path is known to be unusable - never retried. */
    private static boolean captureDisabled;
    /** Cheap change signature: the raw list size + the newest line's text. */
    private static int lastRawSize = -1;
    private static String lastRawTail;

    /** Registers chat history; called once from the BridgeServer constructor. */
    static void registerAll(ToolRegistry tools) {
        tools.register("chat.history", ChatHistoryTools::history);
    }

    // ------------------------------------------------------------------ the tools

    /** {@code chat.history({limit?, regex?, source?, since_ms?})}. */
    private static JsonObject history(ToolContext ctx, JsonObject params) throws Exception {
        final ChatHistoryContracts.HistoryParams p;
        try {
            p = ChatHistoryContracts.historyParams(params);
        } catch (ToolContracts.InvalidParams e) {
            return Json.errorResponse(ctx.id(), Json.INVALID_PARAMS, e.getMessage(), null);
        }
        JsonObject[] holder = new JsonObject[1];
        final boolean[] captured = new boolean[1];
        final int[] buffered = new int[1];
        PerceptionTools.callOnMainThread(ctx, () -> {
            List<ChatHistoryContracts.ChatLine> snapshot = RING.lines();
            captured[0] = captureAvailable;
            buffered[0] = snapshot.size();
            holder[0] = ChatHistoryContracts.historyResult(snapshot, p, captured[0]);
            return null;
        });
        JsonObject result = holder[0];
        ctx.audit("PERCEPTION", "method=chat.history limit=" + p.limit()
                + " source=" + p.source()
                + (p.regex() != null ? " regex=" + p.regex().pattern() : "")
                + (p.sinceMs() != null ? " since_ms=" + p.sinceMs() : "")
                + " buffered=" + buffered[0]
                + " count=" + result.get("count").getAsInt()
                + " captured=" + captured[0]);
        return Json.okResponse(ctx.id(), result);
    }

    // ------------------------------------------------------------------ server-chat attribution

    /**
     * Remembers one SERVER chat line (called from
     * {@link EventPusher#onChatReceived}, client main thread). The capture pump
     * uses this to label a chat line {@code server} instead of {@code client}.
     */
    static void noteServerChat(String text) {
        SERVER_TEXT.note(text);
    }

    // ------------------------------------------------------------------ capture pump (main thread)

    /**
     * Per-tick capture (from {@link SiriusBridge#onClientTick}): reads the
     * client's chat list, appends the new suffix to the ring and remembers
     * the window for the next diff. Never throws into the game - a reflective
     * failure disables the capture once and logs why.
     */
    static void onClientTick() {
        if (captureDisabled) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            ChatComponent chat = mc.gui != null ? mc.gui.getChat() : null;
            if (chat == null) {
                return; // GUI not built yet (very early frames)
            }
            if (!probed) {
                probe(chat);
            }
            if (captureDisabled) {
                return;
            }
            List<String> window = readWindow(chat);
            if (window == null) {
                return; // unchanged since the last tick - nothing to capture
            }
            captureAvailable = true;
            long now = System.currentTimeMillis();
            for (String text : ChatHistoryContracts.newSuffix(WINDOW, window)) {
                RING.add(now, ChatHistoryContracts.sourceOf(SERVER_TEXT.consume(text)), text);
            }
            WINDOW.clear();
            WINDOW.addAll(window);
        } catch (Throwable t) {
            // A reflection change must never kill the tick loop: degrade once.
            disable("chat capture failed, disabling (chat.history will answer captured:false)", t);
        }
    }

    /**
     * Resolves the message list: {@code allMessages} first (one record per
     * chat line, so wrapped lines are not split), {@code trimmedMessages}
     * second (wrapped - the best available on a build that renamed the other).
     */
    private static void probe(ChatComponent chat) {
        probed = true;
        for (String name : new String[]{"allMessages", "trimmedMessages"}) {
            Field field = findField(name);
            if (field == null) {
                continue;
            }
            try {
                Object value = field.get(chat);
                if (!(value instanceof List<?> list)) {
                    continue;
                }
                if (!list.isEmpty()) {
                    Method content = list.get(0).getClass().getMethod("content");
                    content.setAccessible(true);
                    contentMethod = content;
                }
                messagesField = field;
                SiriusBridge.LOGGER.info("sirius-bridge: chat capture armed on ChatComponent.{}", name);
                return;
            } catch (Throwable t) {
                SiriusBridge.LOGGER.debug("sirius-bridge: chat capture probe on {} failed: {}",
                        name, t.toString());
            }
        }
        disable("no usable ChatComponent message list (tried allMessages, trimmedMessages)", null);
    }

    /**
     * The last {@link ChatHistoryContracts#CAPTURE_WINDOW} texts, oldest first, or
     * {@code null} when the chat list has not changed since the previous call
     * (the cheap signature keeps the per-tick cost to one field read).
     */
    private static List<String> readWindow(ChatComponent chat) throws Exception {
        Object value = messagesField.get(chat);
        if (!(value instanceof List<?> list)) {
            disable("ChatComponent message list is not a java.util.List", null);
            return null;
        }
        if (list.isEmpty()) {
            lastRawSize = 0;
            lastRawTail = null;
            return WINDOW.isEmpty() ? null : new ArrayList<>();
        }
        // The list is newest-first (vanilla inserts at index 0).
        String tail = text(list.get(0));
        if (list.size() == lastRawSize && tail != null && tail.equals(lastRawTail)) {
            return null; // nothing new arrived
        }
        lastRawSize = list.size();
        lastRawTail = tail;
        int take = Math.min(ChatHistoryContracts.CAPTURE_WINDOW, list.size());
        List<String> out = new ArrayList<>(take);
        for (int i = take - 1; i >= 0; i--) { // reverse -> oldest first
            String line = text(list.get(i));
            if (line != null) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * Plain text of one chat entry: a record's {@code content()} is either a
     * {@link Component} (allMessages) or a {@link FormattedCharSequence}
     * (trimmedMessages - the sink hands out code points, so they are appended
     * as such); both are flattened here.
     */
    private static String text(Object entry) throws Exception {
        if (entry == null) {
            return null;
        }
        if (contentMethod == null) {
            contentMethod = entry.getClass().getMethod("content");
            contentMethod.setAccessible(true);
        }
        Object content = contentMethod.invoke(entry);
        if (content instanceof Component component) {
            return component.getString();
        }
        if (content instanceof FormattedCharSequence sequence) {
            StringBuilder text = new StringBuilder();
            sequence.accept((index, style, codepoint) -> {
                text.appendCodePoint(codepoint);
                return true;
            });
            return text.toString();
        }
        return content != null ? content.toString() : null;
    }

    /** Finds a private field by name anywhere in the ChatComponent hierarchy. */
    private static Field findField(String name) {
        for (Class<?> type = ChatComponent.class; type != null && type != Object.class;
             type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // try the superclass (mods subclass ChatComponent in rare cases)
            } catch (RuntimeException e) {
                // setAccessible refused (strong module boundaries) - degrade
                SiriusBridge.LOGGER.debug("sirius-bridge: chat field {} not accessible: {}",
                        name, e.toString());
                return null;
            }
        }
        return null;
    }

    /** Disables the capture for the rest of the session (logged exactly once). */
    private static void disable(String reason, Throwable failure) {
        if (captureDisabled) {
            return;
        }
        captureDisabled = true;
        captureAvailable = false;
        if (failure != null) {
            SiriusBridge.LOGGER.warn("sirius-bridge: {}", reason, failure);
        } else {
            SiriusBridge.LOGGER.warn("sirius-bridge: {}", reason);
        }
    }

    /** Test/diagnostic helper: the ring size (main thread only). */
    static int buffered() {
        return RING.size();
    }

    /** Test/diagnostic helper: clears the ring + attribution memory (main thread only). */
    static void reset() {
        RING.clear();
        SERVER_TEXT.clear();
        WINDOW.clear();
        lastRawSize = -1;
        lastRawTail = null;
    }
}
