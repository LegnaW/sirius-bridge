package io.sirius.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Frozen-contract logic for the M5.0 v1.6 {@code useItem} tool: parameter
 * validation, the tick-driven press/report state machine, the result
 * vocabulary and response assembly. Pure Gson + JDK only - no Minecraft
 * classes - so the smoke test covers it without a game.
 *
 * <p><b>Why this primitive exists</b>: protocol 1.5 could only right-click a
 * BLOCK ({@code use}) or inject a raw click ({@code input.click}). Everything
 * that uses the ITEM itself - eating, drinking a potion, casting a fishing rod,
 * throwing an ender pearl, shearing, filling a bucket, placing a boat - needs
 * {@code gameMode.useItem} with no target at all, which neither primitive
 * expresses. This is the fourth action verb: "use whatever is in my hand".
 *
 * <p><b>Evidence, not faith</b>: the {@code InteractionResult} alone is not
 * enough - CONSUME means "started using", not "used up" (1.6.1 真机 bug: 吃面包
 * 回报 {@code used} 而数量 3→3、饱食不变). So a press is reported {@code used}
 * only when one of four observed things CHANGED: the held stack's count, the
 * hunger level, the saturation, or the armour slot's item id; a screen that
 * opened is {@code screen_opened}. A press the game accepted with none of those
 * moving is the new {@code incomplete} (honest "没吃成"); only a silent PASS is
 * {@code no_effect}.
 *
 * <p><b>Why a tick monitor</b>: {@code times}/{@code interval_ms} must not block
 * the client main thread (the render loop would freeze for seconds), so the
 * press schedule is advanced once per client tick from
 * {@link SiriusBridge#onClientTick}, exactly like {@code dig}/{@code place}/
 * {@code use}.
 */
public final class UseItemContracts {

    private UseItemContracts() {
    }

    // ------------------------------------------------------------------ constants

    /** Default total budget; eating/vial animations answer within a second or two. */
    public static final int DEFAULT_TIMEOUT_MS = 2_000;
    public static final int MIN_TIMEOUT_MS = 200;
    public static final int MAX_TIMEOUT_MS = 10_000;

    /** Default gap between two presses. */
    public static final int DEFAULT_INTERVAL_MS = 300;
    public static final int MAX_INTERVAL_MS = 5_000;

    /** Schema maximum for {@code times}. */
    public static final int MAX_TIMES = 10;

    /**
     * 按住进度的余量（tick）：服务端要数满 {@code getUseDuration()} 才真扣物品，
     * 客户端在最后一 tick 才收尾，留 10 tick（0.5 s）吸收网络/同步抖动。
     */
    public static final int HOLD_SLACK_TICKS = 10;

    /** "这项证据未知"的哨兵值（旧调用点不带饱食/饱和度快照时用）：未知绝不算证据。 */
    public static final int UNKNOWN = -1;

    /** 按住结束后额外留给证据落地的余量（ms）：服务端确认/客户端同步常常晚一两个 tick。 */
    public static final int HOLD_DEADLINE_SLACK_MS = 500;

    /**
     * 持有期间（以及刚结束那几 tick）该用的截止时间。
     *
     * <p>为什么不能直接用 {@code timeout_ms}：默认 {@code timeout_ms} 是 2000 ms，
     * 而"按住到进度条走完"光食物就要 {@code getUseDuration()} = 32 tick ≈ 1.6 s，
     * 加 10 tick 余量 ≈ 2.1 s —— <b>默认参数下吃个面包刚好超时</b>，于是 P0-B 会从
     * "假成功"变成"假超时"，还是骗人。所以按住期间的有效截止时间取
     * {@code max(timeout_ms, hold 预算 + 500ms)}：一次完整进食永远来得及走完，
     * 而按住预算本身又受调用预算夹制，不会无限延长。
     *
     * @param timeoutMs       调用方给的 {@code timeout_ms}
     * @param holdBudgetTicks 本次按住预算（tick，来自 {@code getUseDuration() + HOLD_SLACK_TICKS}）
     */
    public static int holdAwareTimeoutMs(int timeoutMs, int holdBudgetTicks) {
        long holdMs = (long) Math.max(0, holdBudgetTicks) * 50L + HOLD_DEADLINE_SLACK_MS;
        return (int) Math.max(timeoutMs, holdMs);
    }

    // ------------------------------------------------------------------ result vocabulary

    public static final String RESULT_USED = "used";
    /**
     * v1.6.2（2026-11-01 真机）新增：press 被游戏接受（interaction 是 success/consume），
     * 但数量、饱食、饱和度、装备这四项证据<b>一项都没动</b>。
     *
     * <p>它和 {@code used} 的区别是"骗人"：真机上"吃面包"回报 {@code used}、数量 3→3、
     * 饱食不变——因为进食是"按住到进度条走完"，按一下只是"开始使用"。
     * 单独给一个词，模型才知道是"没吃成，得按住/或者本来就饱"。
     */
    public static final String RESULT_INCOMPLETE = "incomplete";

    /** {@code incomplete} 的人话解释（进结果体的 {@code reason}：模型据此决定"再按住试试"还是"别试了"）。 */
    public static final String INCOMPLETE_REASON = "按下被接受了，但物品数量、饱食、饱和度、装备都没变化"
            + " —— 进食/喝药要把进度条按住走完；也可能是饱食已满或被服务端拒绝";

    public static final String RESULT_NO_EFFECT = "no_effect";
    public static final String RESULT_SCREEN_OPENED = "screen_opened";
    public static final String RESULT_GUI_OPEN = "gui_open";
    public static final String RESULT_NOT_IN_GAME = "not_in_game";
    public static final String RESULT_NOT_USING = "not_using";
    public static final String RESULT_TIMEOUT = "timeout";

    // ------------------------------------------------------------------ params

    /** Validated {@code useItem} params. */
    public record UseItemParams(UseContracts.Hand hand, int times, int intervalMs, int timeoutMs) {
    }

    /**
     * Validates {@code useItem} params per the frozen schema: optional
     * {@code hand} ({@code main}/{@code off}, default main), {@code times}
     * 1..10 (default 1), {@code interval_ms} 0..5000 (default 300) and
     * {@code timeout_ms} 200..10000 (default 2000 - an explicit null means the
     * default, the {@code use} convention).
     */
    public static UseItemParams useItemParams(JsonObject params) throws ToolContracts.InvalidParams {
        UseContracts.Hand hand = UseContracts.Hand.MAIN;
        JsonElement handElement = params.get("hand");
        if (handElement != null && !handElement.isJsonNull()) {
            if (!handElement.isJsonPrimitive() || !handElement.getAsJsonPrimitive().isString()) {
                throw new ToolContracts.InvalidParams("useItem hand must be a string, one of "
                        + UseContracts.Hand.allowed());
            }
            String raw = handElement.getAsString();
            UseContracts.Hand parsed = UseContracts.Hand.parse(raw);
            if (parsed == null) {
                throw new ToolContracts.InvalidParams("useItem hand must be one of "
                        + UseContracts.Hand.allowed() + ", got: \"" + raw + "\"");
            }
            hand = parsed;
        }
        int times = ToolContracts.integerMember(params, "times", "useItem", 1, MAX_TIMES, 1);
        int intervalMs = ToolContracts.integerMember(params, "interval_ms", "useItem",
                0, MAX_INTERVAL_MS, DEFAULT_INTERVAL_MS);
        int timeoutMs = ToolContracts.integerMember(params, "timeout_ms", "useItem",
                MIN_TIMEOUT_MS, MAX_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
        return new UseItemParams(hand, times, intervalMs, timeoutMs);
    }

    // ------------------------------------------------------------------ monitor

    /** What the shell should do this tick. */
    public enum Action {
        /** Time to send one {@code gameMode.useItem}. */
        PRESS,
        /** Nothing to do yet (waiting for the interval / the evidence). */
        WAIT,
        /** Terminal: release the waiter with {@link Verdict#result()}. */
        FINISH
    }

    /** One tick's decision. */
    public record Verdict(Action action, String result) {
        static Verdict of(Action action) {
            return new Verdict(action, null);
        }

        public boolean terminal() {
            return action == Action.FINISH;
        }
    }

    /** The per-tick observation the shell extracts on the main thread. */
    public record TickView(boolean inGame, boolean screenOpen, long elapsedMs, int itemCount,
                           int food, int saturation, String armor) {

        /**
         * 只带数量的观察（旧调用点的兼容入口）：饱食/饱和度按 {@link #UNKNOWN}、
         * 盔甲按 {@code null} 处理，判定里一律当"这项证据没观测到"，绝不当成"没变"的证据。
         */
        public TickView(boolean inGame, boolean screenOpen, long elapsedMs, int itemCount) {
            this(inGame, screenOpen, elapsedMs, itemCount, UNKNOWN, UNKNOWN, null);
        }
    }

    /**
     * 开始使用之前的证据快照（主线程从 {@code LocalPlayer} 读）。
     *
     * <p>为什么需要四项而不是只有数量：进食/喝药的扣除是服务端把进度条数满才发生的，
     * 而"穿上头盔"这类动作数量根本不变（还是 1 个），只有盔甲槽的物品 id 会变。
     * 只看数量就会把"戴上了"误判成 {@code no_effect}、把"没吃成"误判成 {@code used}。
     *
     * <p>约定：{@code food}/{@code saturation} 的 {@link #UNKNOWN} 与 {@code armor} 的
     * {@code null} 表示"未知"；{@code armor} 的<b>空串表示空槽</b>（空 → 戴上头盔也是证据）。
     */
    public record UseSnapshot(int itemCount, int food, int saturation, String armor) {

        /** 未知快照（兼容只带数量的旧路径）。 */
        public static UseSnapshot ofCount(int itemCount) {
            return new UseSnapshot(itemCount, UNKNOWN, UNKNOWN, null);
        }
    }

    /**
     * The pure press/pacing state machine for one {@code useItem} call.
     *
     * <p>Precedence: leaving the world / a screen / "all presses done" /
     * "budget exhausted" all outrank the next press - the schedule never fires
     * after the call is over.
     *
     * <p><b>结果只认证据</b>（v1.6.2，2026-11-01 真机 bug）：{@code InteractionResult}
     * 的 CONSUME 只代表"开始使用"，所以"被接受"绝不能直接等于 {@code used}。
     * 判定顺序：四项证据有变化 → {@code used}；只有"被接受" → {@code incomplete}；
     * 什么都没有 → 走这个分支本来的"没事发生"词（序列走完 {@code no_effect} /
     * 预算被砍 {@code timeout}）。
     */
    public static final class UseItemMonitor {

        private final int times;
        private final int intervalMs;
        private int timeoutMs;
        private final UseSnapshot before;
        private int presses;
        private long nextPressAtMs;
        private String lastInteraction;
        private boolean consumedAny;

        public UseItemMonitor(UseItemParams p, int itemBefore) {
            this(p, UseSnapshot.ofCount(itemBefore));
        }

        public UseItemMonitor(UseItemParams p, UseSnapshot before) {
            this.times = p.times();
            this.intervalMs = p.intervalMs();
            this.timeoutMs = p.timeoutMs();
            this.before = before;
        }

        /** Records the outcome of one press and schedules the next one. */
        public void notePress(String interaction, long elapsedMs) {
            presses++;
            lastInteraction = interaction;
            if (UseContracts.consumed(interaction)) {
                consumedAny = true;
            }
            nextPressAtMs = elapsedMs + intervalMs;
        }

        /**
         * 抬高本次调用的截止时间（只升不降）。按住开始时调用
         * {@link UseItemContracts#holdAwareTimeoutMs}：一次完整进食的 1.6 s 进度条
         * 不能因为默认 {@code timeout_ms}=2000 而被判成超时。
         */
        public void raiseTimeoutMs(int timeoutMs) {
            this.timeoutMs = Math.max(this.timeoutMs, timeoutMs);
        }

        /** Terminal verdict for the current tick, or {@code null} to keep going. */
        public Verdict terminal(TickView view) {
            if (!view.inGame()) {
                return new Verdict(Action.FINISH, RESULT_NOT_IN_GAME);
            }
            if (view.screenOpen()) {
                // A screen that opened before any press is somebody else's GUI;
                // one that opened after a press is the answer (chest, book, ...).
                return new Verdict(Action.FINISH, presses > 0 ? RESULT_SCREEN_OPENED : RESULT_GUI_OPEN);
            }
            if (presses >= times) {
                return new Verdict(Action.FINISH, evidenceVerdict(view));
            }
            if (view.elapsedMs() >= timeoutMs) {
                // 真超时就是 {@code timeout}，不和 {@code incomplete} 混（incomplete 只留给
                // "序列走完/本来不需要按住" 的假成功）；只有实打实的证据能把它升成 used。
                return new Verdict(Action.FINISH, evidenceMoved(view) ? RESULT_USED : RESULT_TIMEOUT);
            }
            return null;
        }

        /** One tick: terminal verdict first, then the press schedule. */
        public Verdict tick(TickView view) {
            Verdict terminal = terminal(view);
            if (terminal != null) {
                return terminal;
            }
            if (view.elapsedMs() >= nextPressAtMs) {
                return Verdict.of(Action.PRESS);
            }
            return Verdict.of(Action.WAIT);
        }

        /**
         * 四项证据里任何一项动了就算真的用出去了：手持数量变了、饱食上升、饱和度上升、
         * 盔甲槽物品 id 变了（空串 → 某个 id 也算，那正是"戴上头盔"）。
         * 数值型证据的 {@link #UNKNOWN} 和盔甲的 {@code null} 一律不参与比较。
         */
        public boolean evidenceMoved(TickView view) {
            if (view.itemCount() != before.itemCount()) {
                return true;
            }
            if (before.food() != UNKNOWN && view.food() != UNKNOWN && view.food() > before.food()) {
                return true;
            }
            if (before.saturation() != UNKNOWN && view.saturation() != UNKNOWN
                    && view.saturation() > before.saturation()) {
                return true;
            }
            return before.armor() != null && view.armor() != null && !before.armor().equals(view.armor());
        }

        /** 序列走完后的判定（没有证据 → {@code no_effect}）。 */
        public String evidenceVerdict(TickView view) {
            return verdict(view, RESULT_NO_EFFECT);
        }

        private String verdict(TickView view, String nothingHappened) {
            if (evidenceMoved(view)) {
                return RESULT_USED;
            }
            // 被接受了但什么都没动：说 incomplete，别说 used（1.6.1 的假成功）。
            return consumedAny ? RESULT_INCOMPLETE : nothingHappened;
        }

        public int presses() {
            return presses;
        }

        public int itemBefore() {
            return before.itemCount();
        }

        /** 使用前的饱食等级（{@link #UNKNOWN} = 没观测）。 */
        public int foodBefore() {
            return before.food();
        }

        public String lastInteraction() {
            return lastInteraction;
        }
    }

    // ------------------------------------------------------------------ result

    /**
     * Assembles the {@code useItem} response:
     * {@code {"in_game":true,"result":...,"hand":"main","times":n,"pressed":k,
     * "hold_ticks":n,"item_before":n,"item_after":n,"food_before":n,
     * "food_after":n,"elapsed_ms":...}} plus
     * {@code interaction}/{@code screen_class}/{@code reason} when they exist.
     * {@code pressed} is how many presses actually went out (a {@code timeout}
     * or a superseding call can stop short of {@code times}); {@code hold_ticks}
     * is how many ticks the item was actually HELD DOWN (summed over presses) -
     * the number that explains an {@code incomplete} (a press that was accepted
     * but never got its progress bar filled). {@code food_before}/{@code food_after}
     * are the hunger level at start/end ({@link #UNKNOWN} when not observed).
     */
    public static JsonObject useItemResult(UseItemParams p, String result, int pressed, int holdTicks,
                                           String interaction, String screenClass,
                                           int itemBefore, int itemAfter, int foodBefore, int foodAfter,
                                           long elapsedMs, String reason) {
        JsonObject out = new JsonObject();
        out.addProperty("in_game", true);
        out.addProperty("result", result);
        out.addProperty("hand", p.hand().wireName());
        out.addProperty("times", p.times());
        out.addProperty("pressed", pressed);
        out.addProperty("hold_ticks", holdTicks);
        out.addProperty("item_before", itemBefore);
        out.addProperty("item_after", itemAfter);
        out.addProperty("food_before", foodBefore);
        out.addProperty("food_after", foodAfter);
        out.addProperty("elapsed_ms", elapsedMs);
        if (interaction != null) {
            out.addProperty("interaction", interaction);
        }
        if (screenClass != null) {
            out.addProperty("screen_class", screenClass);
        }
        if (reason != null) {
            out.addProperty("reason", reason);
        }
        return out;
    }
}
