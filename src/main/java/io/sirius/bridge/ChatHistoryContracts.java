package io.sirius.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Frozen-contract logic for the M5.0 v1.6 {@code chat.history} tool: parameter
 * validation, the client-side chat ring buffer, server/client source
 * attribution and response assembly. Pure Gson + JDK only - no Minecraft
 * classes - so the smoke test covers the whole thing without a game.
 *
 * <p>({@code chat.send} - the writing side - keeps its own contract class,
 * {@link ChatContracts}; this one is the READING side added in protocol 1.6.)
 *
 * <p><b>Why a ring buffer at all</b>: a lot of what the agent must know only
 * ever appears in the CLIENT chat box - login-plugin confirmations, mod
 * notices, and every Baritone receipt. Those lines fade after ten seconds, so
 * "screenshot immediately after typing the command" is a race the harness
 * loses constantly. The bridge therefore keeps the last
 * {@link #RING_SIZE} lines plus their epoch timestamps.
 *
 * <p><b>Capture</b> (shell side, {@link ChatHistoryTools}) is reflective: the
 * chat list lives in {@code ChatComponent}, which has no public accessor for
 * it, and the project forbids mixins. The tick pump reads the last
 * {@link #CAPTURE_WINDOW} texts and appends only the NEW suffix - that diff is
 * {@link #newSuffix}, which is why it is pure and smoke-tested: an off-by-one
 * here either loses lines or duplicates them every tick.
 *
 * <p><b>Source attribution</b>: the NeoForge {@code ClientChatReceivedEvent}
 * (already wired for {@code events.subscribe}) sees SERVER chat only. Those
 * texts are remembered in a small {@link ServerTextLog}; a captured line whose
 * text is still remembered is labelled {@code server}, everything else
 * {@code client} - i.e. Baritone/mod notices, exactly the interesting half.
 * Deliberately conservative: an unknown line is labelled {@code client} rather
 * than guessed.
 */
public final class ChatHistoryContracts {

    private ChatHistoryContracts() {
    }

    // ------------------------------------------------------------------ constants

    /** Ring capacity (spec suggestion: 256 lines ≈ a few minutes of busy chat). */
    public static final int RING_SIZE = 256;

    /** Default {@code limit} (the schema allows 1..200). */
    public static final int DEFAULT_LIMIT = 40;

    /** Schema maximum for {@code limit}. */
    public static final int MAX_LIMIT = 200;

    /** How many trailing chat texts one tick reads before diffing. */
    public static final int CAPTURE_WINDOW = 64;

    /** How many recent server-chat texts the attribution memory keeps. */
    public static final int SERVER_TEXT_MEMORY = 24;

    /** Source ids (frozen schema enum). */
    public static final String SOURCE_ALL = "all";
    public static final String SOURCE_SERVER = "server";
    public static final String SOURCE_CLIENT = "client";

    /** The source names, for {@code -32602} messages. */
    public static String sources() {
        return SOURCE_ALL + "/" + SOURCE_SERVER + "/" + SOURCE_CLIENT;
    }

    // ------------------------------------------------------------------ params

    /**
     * Validated {@code chat.history} params. {@code regex} is already compiled
     * (an invalid pattern is a {@code -32602} at validation time, not a crash
     * mid-read); {@code sinceMs} is null when the caller did not filter.
     */
    public record HistoryParams(int limit, Pattern regex, String source, Long sinceMs) {
    }

    /**
     * Validates {@code chat.history} params: optional {@code limit} 1..200
     * (default 40), optional Java {@code regex} (compiled here; a broken
     * pattern answers {@code -32602} with a teaching line), optional
     * {@code source} ({@code all}/{@code server}/{@code client}) and optional
     * {@code since_ms} (epoch milliseconds, lines with {@code t >= since_ms}).
     */
    public static HistoryParams historyParams(JsonObject params) throws ToolContracts.InvalidParams {
        int limit = ToolContracts.integerMember(params, "limit", "chat.history", 1, MAX_LIMIT, DEFAULT_LIMIT);

        Pattern regex = null;
        JsonElement regexElement = params.get("regex");
        if (regexElement != null && !regexElement.isJsonNull()) {
            if (!regexElement.isJsonPrimitive() || !regexElement.getAsJsonPrimitive().isString()) {
                throw new ToolContracts.InvalidParams("chat.history regex must be a string"
                        + " (a Java regular expression tested against every whole chat line)");
            }
            String raw = regexElement.getAsString();
            if (raw.isEmpty()) {
                throw new ToolContracts.InvalidParams("chat.history regex must not be empty"
                        + " (omit it to return every line)");
            }
            try {
                regex = Pattern.compile(raw);
            } catch (PatternSyntaxException e) {
                throw new ToolContracts.InvalidParams("chat.history regex is not a valid Java regular"
                        + " expression: " + e.getDescription() + " (at index " + e.getIndex() + ")."
                        + " Omit regex to return every line, or check the pattern (e.g. \"Going to:.*\")");
            }
        }

        String source = SOURCE_ALL;
        JsonElement sourceElement = params.get("source");
        if (sourceElement != null && !sourceElement.isJsonNull()) {
            if (!sourceElement.isJsonPrimitive() || !sourceElement.getAsJsonPrimitive().isString()) {
                throw new ToolContracts.InvalidParams("chat.history source must be a string, one of "
                        + sources());
            }
            String raw = sourceElement.getAsString();
            if (sourceIndex(raw) < 0) {
                throw new ToolContracts.InvalidParams("chat.history source must be one of " + sources()
                        + ", got: \"" + raw + "\" (server = real server chat,"
                        + " client = local messages such as Baritone receipts)");
            }
            source = raw;
        }

        Long sinceMs = null;
        JsonElement sinceElement = params.get("since_ms");
        if (sinceElement != null && !sinceElement.isJsonNull()) {
            if (!sinceElement.isJsonPrimitive() || !sinceElement.getAsJsonPrimitive().isNumber()) {
                throw new ToolContracts.InvalidParams("chat.history since_ms must be epoch milliseconds"
                        + " (an integer >= 0)");
            }
            double v = sinceElement.getAsDouble();
            if (!Double.isFinite(v) || v != Math.floor(v)) {
                throw new ToolContracts.InvalidParams("chat.history since_ms must be an integer >= 0, got: " + v);
            }
            if (v < 0) {
                throw new ToolContracts.InvalidParams("chat.history since_ms must be >= 0, got: " + (long) v);
            }
            sinceMs = (long) v;
        }
        return new HistoryParams(limit, regex, source, sinceMs);
    }

    /** {@code -1} for an unknown source name (order mirrors the schema enum). */
    private static int sourceIndex(String source) {
        return switch (source) {
            case SOURCE_ALL -> 0;
            case SOURCE_SERVER -> 1;
            case SOURCE_CLIENT -> 2;
            default -> -1;
        };
    }

    // ------------------------------------------------------------------ the ring

    /** One captured chat line: epoch ms, "server"/"client", plain text. */
    public record ChatLine(long t, String source, String text) {
    }

    /**
     * Fixed-capacity chat history (oldest first, oldest evicted first). Not
     * thread-safe on purpose: every access happens on the client main thread
     * (the tick pump writes, the tool reads through the same-thread latch).
     */
    public static final class ChatRing {

        private final int capacity;
        private final ArrayDeque<ChatLine> lines = new ArrayDeque<>();

        public ChatRing(int capacity) {
            this.capacity = capacity;
        }

        /** Appends one line, evicting the oldest once full. */
        public void add(ChatLine line) {
            if (line == null || line.text() == null) {
                return;
            }
            while (lines.size() >= capacity) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }

        public void add(long t, String source, String text) {
            add(new ChatLine(t, source, text));
        }

        /** Snapshot of the buffer, oldest first (a copy - the caller may hold it). */
        public List<ChatLine> lines() {
            return new ArrayList<>(lines);
        }

        public int size() {
            return lines.size();
        }

        public void clear() {
            lines.clear();
        }
    }

    /**
     * Remembers recently seen SERVER chat texts so captured lines can be
     * attributed. {@link #consume} removes the match so a repeated identical
     * line consumes its own entry (two "hi" from a player stay two server
     * lines, while a mod's identical client notice is not upgraded by luck).
     */
    public static final class ServerTextLog {

        private final int capacity;
        private final ArrayDeque<String> texts = new ArrayDeque<>();

        public ServerTextLog(int capacity) {
            this.capacity = capacity;
        }

        public void note(String text) {
            if (text == null) {
                return;
            }
            while (texts.size() >= capacity) {
                texts.removeFirst();
            }
            texts.addLast(text);
        }

        /** True when {@code text} was seen as server chat; consumes that entry. */
        public boolean consume(String text) {
            if (text == null || texts.isEmpty()) {
                return false;
            }
            for (Iterator<String> it = texts.iterator(); it.hasNext(); ) {
                if (it.next().equals(text)) {
                    it.remove();
                    return true;
                }
            }
            return false;
        }

        public int size() {
            return texts.size();
        }

        public void clear() {
            texts.clear();
        }
    }

    // ------------------------------------------------------------------ capture diff + filtering

    /**
     * The NEW suffix of {@code current} given the previous window snapshot:
     * the largest {@code k} such that the last {@code k} entries of
     * {@code previous} equal the first {@code k} entries of {@code current},
     * and everything after that prefix is new.
     *
     * <p>The chat list is append-only for long stretches, so the full overlap
     * is found every tick; when the history was cleared (F3+D) nothing
     * overlaps and everything currently on screen is reported as new - a few
     * duplicate lines are a better failure than dropping real ones.
     */
    public static List<String> newSuffix(List<String> previous, List<String> current) {
        if (current == null || current.isEmpty()) {
            return new ArrayList<>();
        }
        if (previous == null || previous.isEmpty()) {
            return new ArrayList<>(current);
        }
        int max = Math.min(previous.size(), current.size());
        for (int k = max; k > 0; k--) {
            if (previous.subList(previous.size() - k, previous.size()).equals(current.subList(0, k))) {
                return new ArrayList<>(current.subList(k, current.size()));
            }
        }
        return new ArrayList<>(current);
    }

    /** Whether one line passes the source filter. */
    public static boolean sourceMatches(ChatLine line, String source) {
        return SOURCE_ALL.equals(source) || (line.source() != null && line.source().equals(source));
    }

    /**
     * Assembles the {@code chat.history} response:
     * {@code {"lines":[{t,source,text}],"count":n,"captured":bool}}.
     *
     * <p>Filters run in {@code since_ms} -> {@code source} -> {@code regex}
     * order over the whole buffer, then the LAST {@code limit} survivors are
     * returned (callers want the most recent lines, capped). {@code count} is
     * the number of returned lines, so "did anything match" is a single field.
     */
    public static JsonObject historyResult(List<ChatLine> ring, HistoryParams p, boolean captured) {
        List<ChatLine> kept = new ArrayList<>();
        for (ChatLine line : ring) {
            if (p.sinceMs() != null && line.t() < p.sinceMs()) {
                continue;
            }
            if (!sourceMatches(line, p.source())) {
                continue;
            }
            if (p.regex() != null && (line.text() == null || !p.regex().matcher(line.text()).find())) {
                continue;
            }
            kept.add(line);
        }
        int from = Math.max(0, kept.size() - p.limit());
        JsonArray lines = new JsonArray();
        for (ChatLine line : kept.subList(from, kept.size())) {
            lines.add(lineJson(line));
        }
        JsonObject result = new JsonObject();
        result.add("lines", lines);
        result.addProperty("count", lines.size());
        result.addProperty("captured", captured);
        return result;
    }

    /** {@code {t,source,text}} - the wire shape of one chat line. */
    public static JsonObject lineJson(ChatLine line) {
        JsonObject out = new JsonObject();
        out.addProperty("t", line.t());
        out.addProperty("source", line.source() != null ? line.source() : SOURCE_CLIENT);
        out.add("text", line.text() != null ? new JsonPrimitive(line.text()) : JsonNull.INSTANCE);
        return out;
    }

    /** Source name for a captured line, from the "was this server chat?" answer. */
    public static String sourceOf(boolean serverChat) {
        return serverChat ? SOURCE_SERVER : SOURCE_CLIENT;
    }

    /** Lower-cased helper kept next to the constants (audit lines / messages). */
    public static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
