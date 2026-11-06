package io.sirius.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * In-process smoke test for the M1-C perception tools, the M2-A pure input
 * logic, the M2-B event push channel and the M2-C GUI state tool (run via
 * {@code gradlew smokeTest}). No game, no client: it exercises the pure
 * halves - parameter validation, bbox cropping, the JPEG budget ladders,
 * response assembly, block scanning and entity filtering, the key-name ->
 * GLFW keycode table, the rate-limiter token bucket, input param validation
 * and evidence file naming, event subscription matching, notification frame
 * assembly, the screenshot stream throttle state machine and the streaming
 * budget ladder, plus widget/slot node assembly, the node cap + truncation
 * and all three getGuiState response shapes, the M2-D look param validation,
 * the eye->target rotation math (hand-computed cases), the permission-tier
 * decision matrix and the config round-trip of the new keys - exactly the
 * logic that would otherwise only be verifiable inside a running Minecraft.
 * M4.4 v1.4 adds the place parameter/geometry/vocabulary halves, the inventory
 * scope/filter/role/assembly logic and the players ordering/shape.
 * M4.5 v1.5 adds the use half: params/auto-face geometry, the three-field
 * verdict precedence and the timeout rule (a consumed interaction is still a
 * use).
 *
 * <p>Exit code 0 = all checks passed; any failure prints the check name and
 * exits 1.
 */
public final class SmokeMain {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        screenshotParams();
        worldQueryParams();
        imageOps();
        contracts();
        keyCodes();
        tokenBucket();
        inputContracts();
        evidenceNaming();
        bridgeConfig();
        eventsContracts();
        damageContracts();
        streamThrottle();
        streamLadder();
        guiContracts();
        lookContracts();
        digContracts();
        permissionContracts();
        chatContracts();
        movementLook();
        placeContracts();
        inventoryContracts();
        playersContracts();
        useContracts();
        // M5.0 v1.6 (protocol 1.6)
        worldQueryPaging();
        chatHistoryContracts();
        baritoneApiContracts();
        useItemContracts();
        entityContracts();
        inventorySelectContracts();
        statsEnvironment();
        blockContracts();
        containerContracts();
        coreEventContracts();
        capabilitiesCatalog();

        System.out.println();
        System.out.println("smoke: " + passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("  FAILED: " + f));
            System.exit(1);
        }
        System.out.println("smoke: OK");
    }

    // ------------------------------------------------------------------ checks

    private static void keyCodes() throws Exception {
        // --- letters/digits, case-insensitive
        check(KeyCodes.resolve("E") == 69 && KeyCodes.resolve("e") == 69 && KeyCodes.resolve("W") == 87,
                "keycodes: letters E/W resolve to GLFW 69/87");
        check(KeyCodes.resolve("0") == 48 && KeyCodes.resolve("9") == 57, "keycodes: digits 0/9 resolve");
        // --- common control keys and aliases
        check(KeyCodes.resolve("ENTER") == 257 && KeyCodes.resolve("RETURN") == 257,
                "keycodes: ENTER/RETURN -> 257");
        check(KeyCodes.resolve("ESC") == 256 && KeyCodes.resolve("ESCAPE") == 256, "keycodes: ESC -> 256");
        check(KeyCodes.resolve("SPACE") == 32, "keycodes: SPACE -> 32");
        check(KeyCodes.resolve("TAB") == 258 && KeyCodes.resolve("BACKSPACE") == 259,
                "keycodes: TAB/BACKSPACE -> 258/259");
        check(KeyCodes.resolve("F1") == 290 && KeyCodes.resolve("F3") == 292 && KeyCodes.resolve("F12") == 301,
                "keycodes: F1/F3/F12 -> 290/292/301");
        check(KeyCodes.resolve("SHIFT") == 340 && KeyCodes.resolve("LSHIFT") == 340
                && KeyCodes.resolve("LEFT_SHIFT") == 340 && KeyCodes.resolve("RSHIFT") == 344,
                "keycodes: SHIFT aliases -> 340/344");
        check(KeyCodes.resolve("CTRL") == 341 && KeyCodes.resolve("CONTROL") == 341
                && KeyCodes.resolve("RCTRL") == 345 && KeyCodes.resolve("ALT") == 342,
                "keycodes: CTRL/ALT aliases -> 341/345/342");
        check(KeyCodes.resolve("UP") == 265 && KeyCodes.resolve("DOWN") == 264
                && KeyCodes.resolve("LEFT") == 263 && KeyCodes.resolve("RIGHT") == 262,
                "keycodes: arrows -> 262..265");
        check(KeyCodes.resolve("PAGEUP") == 266 && KeyCodes.resolve("PAGE_DOWN") == 267
                && KeyCodes.resolve("HOME") == 268 && KeyCodes.resolve("END") == 269,
                "keycodes: PAGE_UP/HOME/END");
        check(KeyCodes.resolve("NUMPAD5") == 325 && KeyCodes.resolve("KPENTER") == 335,
                "keycodes: numpad keys");
        check(KeyCodes.resolve("GRAVE") == 96 && KeyCodes.resolve("SLASH") == 47,
                "keycodes: punctuation keys");
        // --- unknown / empty names rejected
        expectInvalid(() -> KeyCodes.resolve("NOPE"), "keycodes: unknown name rejected");
        expectInvalid(() -> KeyCodes.resolve(""), "keycodes: empty name rejected");
        expectInvalid(() -> KeyCodes.resolve("  "), "keycodes: blank name rejected");
        // --- reverse lookup
        check("E".equals(KeyCodes.name(69)) && "ENTER".equals(KeyCodes.name(257))
                && "SHIFT".equals(KeyCodes.name(340)),
                "keycodes: canonical reverse names");
        check(KeyCodes.name(292) != null && KeyCodes.name(31) == null,
                "keycodes: reverse lookup misses unknown codes");
        // --- integer keycode validity range
        check(KeyCodes.isValidKeycode(32) && KeyCodes.isValidKeycode(348) && !KeyCodes.isValidKeycode(31)
                && !KeyCodes.isValidKeycode(349) && !KeyCodes.isValidKeycode(-1),
                "keycodes: keycode range 32..348 enforced");
    }

    private static void tokenBucket() {
        // Deterministic clock: 1 tick = 1 ms.
        long[] nanos = {0};
        TokenBucket bucket = new TokenBucket(20, () -> nanos[0]);

        boolean[] results = new boolean[25];
        for (int i = 0; i < results.length; i++) {
            results[i] = bucket.tryAcquire(1);
        }
        boolean first20 = true;
        for (int i = 0; i < 20; i++) {
            first20 &= results[i];
        }
        check(first20 && !results[20] && !results[21],
                "bucket: burst of 20 passes, 21st/22nd rejected");

        nanos[0] += 50_000_000L; // +50ms -> exactly 1 token refilled
        check(bucket.tryAcquire(1) && !bucket.tryAcquire(1),
                "bucket: 50ms at 20/s refills exactly one token");

        nanos[0] += 2_000_000_000L; // +2s idle -> capped at capacity
        double available = bucket.availableTokens();
        check(available <= 20.0 && bucket.tryAcquire(20) && !bucket.tryAcquire(1),
                "bucket: idle 2s refills to capacity cap (20)");
        check(bucket.tryAcquire(0), "bucket: zero-token acquire succeeds");
        check(bucket.refillPerSecond() == 20, "bucket: rate exposed");
    }

    private static void inputContracts() throws Exception {
        // --- input.key
        InputContracts.KeyParams k = validParams(() -> InputContracts.keyParams(json("{\"code\":\"E\"}")));
        check(k.glfwKey() == 69 && "E".equals(k.keyName()) && k.durationMs() == 0 && k.modifiers() == 0,
                "input.key: name code with defaults");

        k = validParams(() -> InputContracts.keyParams(
                json("{\"code\":\"LEFT_SHIFT\",\"duration_ms\":500,\"modifiers\":[\"CTRL\"]}")));
        check(k.glfwKey() == 340 && k.durationMs() == 500 && k.modifiers() == InputContracts.MOD_CONTROL,
                "input.key: duration + single modifier parsed");

        k = validParams(() -> InputContracts.keyParams(
                json("{\"code\":257,\"modifiers\":[\"SHIFT\",\"CTRL\",\"ALT\",\"SUPER\"]}")));
        check(k.glfwKey() == 257 && k.modifiers() == 0xF,
                "input.key: integer code + all modifiers -> bits 0xF");

        k = validParams(() -> InputContracts.keyParams(json("{\"code\":69.0}")));
        check(k.glfwKey() == 69, "input.key: whole-number float code accepted");

        expectInvalid(() -> InputContracts.keyParams(json("{}")), "input.key: missing code rejected");
        expectInvalid(() -> InputContracts.keyParams(json("{\"code\":\"NOPE\"}")),
                "input.key: unknown name rejected");
        expectInvalid(() -> InputContracts.keyParams(json("{\"code\":1000}")),
                "input.key: out-of-range keycode rejected");
        expectInvalid(() -> InputContracts.keyParams(json("{\"code\":69.5}")),
                "input.key: fractional keycode rejected");
        expectInvalid(() -> InputContracts.keyParams(json("{\"code\":[69]}")),
                "input.key: array code rejected");
        expectInvalid(() -> InputContracts.keyParams(json("{\"code\":\"E\",\"duration_ms\":-1}")),
                "input.key: negative duration rejected");
        expectInvalid(() -> InputContracts.keyParams(json("{\"code\":\"E\",\"duration_ms\":61000}")),
                "input.key: duration over cap rejected");
        expectInvalid(() -> InputContracts.keyParams(json("{\"code\":\"E\",\"duration_ms\":80.5}")),
                "input.key: fractional duration rejected");
        expectInvalid(() -> InputContracts.keyParams(json("{\"code\":\"E\",\"modifiers\":\"SHIFT\"}")),
                "input.key: non-array modifiers rejected");
        expectInvalid(() -> InputContracts.keyParams(json("{\"code\":\"E\",\"modifiers\":[\"HYPER\"]}")),
                "input.key: unknown modifier rejected");
        expectInvalid(() -> InputContracts.keyParams(json("{\"code\":\"E\",\"modifiers\":[3]}")),
                "input.key: numeric modifier entry rejected");

        // --- input.text
        InputContracts.TextParams t = validParams(() -> InputContracts.textParams(json("{\"string\":\"hi 你好\"}")));
        check("hi 你好".equals(t.text()) && t.codepoints() == 5, "input.text: unicode string parsed");

        t = validParams(() -> InputContracts.textParams(json("{\"string\":\"\\ud83d\\ude00\"}")));
        check(t.codepoints() == 1, "input.text: surrogate pair counts as one codepoint");

        expectInvalid(() -> InputContracts.textParams(json("{}")), "input.text: missing string rejected");
        expectInvalid(() -> InputContracts.textParams(json("{\"string\":\"\"}")),
                "input.text: empty string rejected");
        expectInvalid(() -> InputContracts.textParams(json("{\"string\":42}")),
                "input.text: non-string rejected");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i <= InputContracts.MAX_TEXT_CODEPOINTS; i++) {
            longText.append('x');
        }
        final String tooLong = longText.toString();
        expectInvalid(() -> InputContracts.textParams(json("{\"string\":\"" + tooLong + "\"}")),
                "input.text: over-length string rejected");

        // --- input.mouseMove
        InputContracts.MouseMoveParams m = validParams(() -> InputContracts.mouseMoveParams(
                json("{\"x\":100,\"y\":200.5}")));
        check(m.x() == 100.0 && m.y() == 200.5, "input.mouseMove: numeric pair parsed");
        expectInvalid(() -> InputContracts.mouseMoveParams(json("{\"x\":100}")),
                "input.mouseMove: missing y rejected");
        expectInvalid(() -> InputContracts.mouseMoveParams(json("{\"x\":\"100\",\"y\":200}")),
                "input.mouseMove: string coordinate rejected");

        // --- input.mouseMove M4.2 camera guard: the three live states
        check(!InputContracts.mouseMoveSwingsCamera(true, true),
                "mouse guard: screen open + grabbed -> GUI cursor move, allowed");
        check(!InputContracts.mouseMoveSwingsCamera(false, false),
                "mouse guard: no screen + ungrabbed -> inert event, allowed");
        check(InputContracts.mouseMoveSwingsCamera(false, true),
                "mouse guard: no screen + grabbed -> would swing the camera, rejected");
        String grabMsg = InputContracts.mouseGrabbedMessage();
        check(grabMsg.startsWith("mouse_grabbed") && grabMsg.contains("look/lookAt"),
                "mouse guard: rejection message names the error and suggests look/lookAt");

        // --- input.click
        InputContracts.ClickParams c = validParams(() -> InputContracts.clickParams(json("{\"button\":0}")));
        check(c.button() == 0 && c.count() == 1 && c.holdMs() == null,
                "input.click: defaults (count 1, no hold - v1.0 behaviour)");
        c = validParams(() -> InputContracts.clickParams(json("{\"button\":2,\"count\":3}")));
        check(c.button() == 2 && c.count() == 3 && c.holdMs() == null, "input.click: button/count parsed");
        expectInvalid(() -> InputContracts.clickParams(json("{}")), "input.click: missing button rejected");
        expectInvalid(() -> InputContracts.clickParams(json("{\"button\":3}")),
                "input.click: button 3 rejected");
        expectInvalid(() -> InputContracts.clickParams(json("{\"button\":-1}")),
                "input.click: negative button rejected");
        expectInvalid(() -> InputContracts.clickParams(json("{\"button\":0,\"count\":0}")),
                "input.click: count 0 rejected");
        expectInvalid(() -> InputContracts.clickParams(
                json("{\"button\":0,\"count\":" + (InputContracts.MAX_CLICK_COUNT + 1) + "}")),
                "input.click: count over cap rejected");
        expectInvalid(() -> InputContracts.clickParams(json("{\"button\":0.5}")),
                "input.click: fractional button rejected");

        // --- input.click hold_ms (M3.5 v1.1)
        c = validParams(() -> InputContracts.clickParams(json("{\"button\":0,\"hold_ms\":600}")));
        check(c.button() == 0 && c.holdMs() == 600 && c.count() == 1,
                "input.click: hold_ms parsed (count defaults 1)");
        c = validParams(() -> InputContracts.clickParams(json("{\"button\":1,\"hold_ms\":0}")));
        check(c.holdMs() == 0, "input.click: hold_ms 0 accepted (immediate release)");
        c = validParams(() -> InputContracts.clickParams(json("{\"button\":1,\"hold_ms\":10000}")));
        check(c.holdMs() == 10000, "input.click: hold_ms boundary 10000 accepted");
        c = validParams(() -> InputContracts.clickParams(json("{\"button\":1,\"hold_ms\":null}")));
        check(c.holdMs() == null, "input.click: explicit null hold_ms -> v1.0 tap");
        expectInvalid(() -> InputContracts.clickParams(json("{\"button\":0,\"hold_ms\":10001}")),
                "input.click: hold_ms over cap rejected");
        expectInvalid(() -> InputContracts.clickParams(json("{\"button\":0,\"hold_ms\":-1}")),
                "input.click: negative hold_ms rejected");
        expectInvalid(() -> InputContracts.clickParams(json("{\"button\":0,\"hold_ms\":0.5}")),
                "input.click: fractional hold_ms rejected");
        expectInvalid(() -> InputContracts.clickParams(json("{\"button\":0,\"hold_ms\":\"600\"}")),
                "input.click: string hold_ms rejected");
        expectInvalid(() -> InputContracts.clickParams(json("{\"button\":0,\"hold_ms\":[600]}")),
                "input.click: array hold_ms rejected");
        expectInvalid(() -> InputContracts.clickParams(json("{\"button\":0,\"count\":2,\"hold_ms\":100}")),
                "input.click: count + hold_ms rejected (mutually exclusive)");
        expectInvalid(() -> InputContracts.clickParams(json("{\"button\":0,\"count\":1,\"hold_ms\":100}")),
                "input.click: explicit count 1 + hold_ms still rejected");

        c = validParams(() -> InputContracts.clickParams(json("{\"button\":0,\"at\":{\"x\":12.5,\"y\":0}}")));
        check(c.at().inside(13, 1) && !c.at().inside(12, 1) && !c.at().inside(13, 0),
                "input.click: GUI coordinates use exclusive upper bounds");
        expectInvalid(() -> InputContracts.clickParams(json("{\"button\":0,\"at\":{\"x\":0}}")),
                "input.click: GUI point requires both coordinates");
        expectInvalid(() -> InputContracts.clickParams(json("{\"button\":0,\"at\":{\"x\":-1,\"y\":0}}")),
                "input.click: GUI point rejects negative coordinates");

        // --- input.click schedule: the timed PRESS/RELEASE plan (pure, so the
        // hold timing is verifiable without a client)
        List<InputContracts.ScheduledClick> tap = InputContracts.clickSchedule(
                new InputContracts.ClickParams(0, 1, null, null));
        check(tap.size() == 1 && !tap.get(0).press() && tap.get(0).delayMs() == InputContracts.CLICK_HOLD_MS,
                "input.click: default schedule = single RELEASE at +25ms (v1.0 unchanged)");
        List<InputContracts.ScheduledClick> burst = InputContracts.clickSchedule(
                new InputContracts.ClickParams(0, 3, null, null));
        boolean burstOrder = burst.size() == 5;
        boolean[] burstPresses = {false, true, false, true, false};
        long[] burstDelays = {25, 50, 75, 100, 125};
        for (int i = 0; burstOrder && i < burst.size(); i++) {
            burstOrder &= burst.get(i).press() == burstPresses[i] && burst.get(i).delayMs() == burstDelays[i];
        }
        check(burstOrder, "input.click: count 3 schedule = R@25 P@50 R@75 P@100 R@125 (v1.0 unchanged)");
        List<InputContracts.ScheduledClick> hold = InputContracts.clickSchedule(
                new InputContracts.ClickParams(0, 1, 600, null));
        check(hold.size() == 1 && !hold.get(0).press() && hold.get(0).delayMs() == 600
                        && hold.get(0).delayMs() > InputContracts.CLICK_HOLD_MS,
                "input.click: hold_ms schedule = single RELEASE delayed to +600ms");
        List<InputContracts.ScheduledClick> zeroHold = InputContracts.clickSchedule(
                new InputContracts.ClickParams(2, 1, 0, null));
        check(zeroHold.size() == 1 && zeroHold.get(0).delayMs() == 0,
                "input.click: hold_ms 0 schedules the RELEASE immediately");

        // --- result shapes
        JsonObject keyResult = InputContracts.keyResult("E", 69, 50, 1, true);
        check(keyResult.get("injected").getAsBoolean() && "E".equals(keyResult.get("key").getAsString())
                && keyResult.get("glfw_key").getAsInt() == 69 && keyResult.get("duration_ms").getAsInt() == 50,
                "input results: keyResult shape");

        JsonObject textResult = InputContracts.textResult(3, 3, true);
        check(textResult.get("delivered").getAsInt() == 3 && textResult.get("screen_open").getAsBoolean(),
                "input results: textResult shape");

        JsonObject moveResult = InputContracts.mouseMoveResult(100, 200, 50, 100, false);
        check(moveResult.get("moved").getAsBoolean() && moveResult.get("gui_scaled").getAsJsonObject()
                        .get("x").getAsDouble() == 50,
                "input results: mouseMoveResult shape");

        JsonObject clickResult = InputContracts.clickResult(0, 2, true, "InventoryScreen",
                "evidence_click_20260818_120000000.jpg", 12345, null);
        check(clickResult.get("clicked").getAsBoolean()
                        && "InventoryScreen".equals(clickResult.get("screen").getAsString())
                        && clickResult.get("evidence").getAsJsonObject().get("bytes").getAsLong() == 12345
                        && !clickResult.has("hold_ms"),
                "input results: clickResult shape");

        JsonObject clickNoEvidence = InputContracts.clickResult(1, 1, false, null, null, 0, null);
        check(!clickNoEvidence.has("screen") && !clickNoEvidence.has("evidence")
                        && !clickNoEvidence.has("hold_ms") && !clickNoEvidence.has("release_scheduled"),
                "input results: clickResult omits null fields");

        JsonObject clickHold = InputContracts.clickResult(0, 1, false, null, null, 0, 600);
        check(clickHold.get("hold_ms").getAsInt() == 600
                        && clickHold.get("release_scheduled").getAsBoolean(),
                "input results: clickResult echoes hold_ms + release_scheduled (v1.1)");

        check(InputContracts.rateLimitedMessage(20).contains("20"),
                "input results: rate-limited message mentions the limit");
    }

    private static void evidenceNaming() {
        long epochMs = 1755470400123L; // 2025-08-18 (local) ish - exact value irrelevant
        String name = InputContracts.evidenceFileName("click", epochMs);
        check(name.startsWith("evidence_click_") && name.endsWith(".jpg"),
                "evidence: name has prefix and .jpg suffix");
        check(name.matches("evidence_click_\\d{8}_\\d{9}\\.jpg"),
                "evidence: name matches timestamp pattern yyyyMMdd_HHmmssSSS");
        check(name.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_' || ch == '.'),
                "evidence: filesystem-safe characters only");
        check(!InputContracts.evidenceFileName("click", epochMs + 1).equals(name),
                "evidence: names differ across milliseconds");
    }

    private static void bridgeConfig() throws Exception {
        Path dir = Files.createTempDirectory("sirius_smoke_config");

        // --- fresh file: focus key defaults to true, file gains the new key
        Path fresh = dir.resolve("fresh.toml");
        BridgeConfig c = BridgeConfig.load(fresh, new SecureRandom());
        check(c.keepRunningUnfocused == BridgeConfig.DEFAULT_KEEP_RUNNING_UNFOCUSED,
                "config: keep_running_unfocused defaults to " + BridgeConfig.DEFAULT_KEEP_RUNNING_UNFOCUSED);
        String saved = Files.readString(fresh);
        check(saved.contains("keep_running_unfocused = true"),
                "config: fresh save writes keep_running_unfocused = true");
        check(saved.contains("rate_limit_per_sec = 20") && saved.contains("gui_click_evidence = true")
                        && saved.contains("input_enabled = true") && saved.contains("port = 8765"),
                "config: fresh save keeps the M2-A keys with their defaults");

        // --- explicit false round-trips through save + reload
        Path offFile = dir.resolve("off.toml");
        Files.writeString(offFile, """
                port = 9000
                token = "smoketoken"
                input_enabled = false
                keep_running_unfocused = false
                """);
        BridgeConfig off = BridgeConfig.load(offFile, new SecureRandom());
        check(!off.keepRunningUnfocused && !off.inputEnabled && off.port == 9000
                        && "smoketoken".equals(off.token) && !off.tokenGenerated,
                "config: keep_running_unfocused=false parsed alongside M2-A keys");
        check(Files.readString(offFile).contains("keep_running_unfocused = false")
                        && Files.readString(offFile).contains("rate_limit_per_sec = 20"),
                "config: save rewrites false value and keeps other keys");

        // --- case-insensitive booleans
        Path caseFile = dir.resolve("case.toml");
        Files.writeString(caseFile, "token = \"t\"\nkeep_running_unfocused = FALSE\n");
        check(!BridgeConfig.load(caseFile, new SecureRandom()).keepRunningUnfocused,
                "config: FALSE (any case) parses as false");

        // --- invalid value degrades to the default + a note, never fatal
        Path badFile = dir.resolve("bad.toml");
        Files.writeString(badFile, "token = \"t\"\nkeep_running_unfocused = maybe\n");
        BridgeConfig bad = BridgeConfig.load(badFile, new SecureRandom());
        check(bad.keepRunningUnfocused == BridgeConfig.DEFAULT_KEEP_RUNNING_UNFOCUSED
                        && bad.notes.contains("keep_running_unfocused"),
                "config: invalid value falls back to default with a note");

        // --- unknown keys are still ignored (forward compatibility)
        Path extraFile = dir.resolve("extra.toml");
        Files.writeString(extraFile, "token = \"t\"\nsome_future_key = 42\nkeep_running_unfocused = false\n");
        check(!BridgeConfig.load(extraFile, new SecureRandom()).keepRunningUnfocused,
                "config: unknown keys ignored, focus key still parsed");

        // --- M2-D permission key: default full, round-trip, case, invalid fallback
        Path permFile = dir.resolve("perm.toml");
        BridgeConfig perm = BridgeConfig.load(permFile, new SecureRandom());
        check(perm.permission == BridgeConfig.DEFAULT_PERMISSION
                        && perm.permission == PermissionContracts.Tier.FULL,
                "config: permission defaults to full (pre-M2-D behaviour)");
        check(Files.readString(permFile).contains("permission = \"full\""),
                "config: fresh save writes permission = \"full\" (auto-added to old files too)");
        Files.writeString(permFile, "token = \"t\"\npermission = \"observe\"\n");
        check(BridgeConfig.load(permFile, new SecureRandom()).permission == PermissionContracts.Tier.OBSERVE
                        && Files.readString(permFile).contains("permission = \"observe\""),
                "config: observe round-trips through save + reload");
        Files.writeString(permFile, "token = \"t\"\npermission = INPUT_WORLD\n");
        check(BridgeConfig.load(permFile, new SecureRandom()).permission
                        == PermissionContracts.Tier.INPUT_WORLD,
                "config: tier names parse case-insensitively");
        Files.writeString(permFile, "token = \"t\"\npermission = sudo\n");
        BridgeConfig badPerm = BridgeConfig.load(permFile, new SecureRandom());
        check(badPerm.permission == BridgeConfig.DEFAULT_PERMISSION
                        && badPerm.notes.contains("permission"),
                "config: invalid permission falls back to full with a note");
    }

    private static void screenshotParams() throws Exception {
        ToolContracts.ScreenshotParams p;

        p = validParams(() -> ToolContracts.screenshotParams(json("{\"tier\":\"full\"}")));
        check("full".equals(p.tier()) && p.bbox() == null && p.quality() == 80,
                "screenshot: full defaults (bbox null, quality 80)");

        p = validParams(() -> ToolContracts.screenshotParams(
                json("{\"tier\":\"crop\",\"bbox\":[10,20,300,400],\"quality\":90}")));
        check(Arrays.equals(p.bbox(), new int[]{10, 20, 300, 400}) && p.quality() == 90,
                "screenshot: crop + bbox + quality parsed");

        p = validParams(() -> ToolContracts.screenshotParams(
                json("{\"tier\":\"crop\",\"bbox\":[10.4,20.6,100.9,50.2],\"quality\":null}")));
        check(Arrays.equals(p.bbox(), new int[]{10, 21, 101, 50}) && p.quality() == 80,
                "screenshot: float bbox rounds, null quality defaults");

        expectInvalid(() -> ToolContracts.screenshotParams(json("{\"tier\":\"crop\"}")),
                "screenshot: crop without bbox rejected");
        expectInvalid(() -> ToolContracts.screenshotParams(json("{}")),
                "screenshot: missing tier rejected");
        expectInvalid(() -> ToolContracts.screenshotParams(json("{\"tier\":\"huge\"}")),
                "screenshot: bad tier enum rejected");
        expectInvalid(() -> ToolContracts.screenshotParams(json("{\"tier\":\"crop\",\"bbox\":[1,2,3]}")),
                "screenshot: short bbox rejected");
        expectInvalid(() -> ToolContracts.screenshotParams(json("{\"tier\":\"crop\",\"bbox\":[\"a\",2,3,4]}")),
                "screenshot: non-numeric bbox rejected");
        expectInvalid(() -> ToolContracts.screenshotParams(json("{\"tier\":\"crop\",\"bbox\":[0,0,0,10]}")),
                "screenshot: zero-width bbox rejected");
        expectInvalid(() -> ToolContracts.screenshotParams(json("{\"tier\":\"full\",\"quality\":101}")),
                "screenshot: quality 101 rejected");
        expectInvalid(() -> ToolContracts.screenshotParams(json("{\"tier\":\"full\",\"quality\":80.5}")),
                "screenshot: fractional quality rejected");
        expectInvalid(() -> ToolContracts.screenshotParams(json("{\"tier\":\"full\",\"quality\":\"80\"}")),
                "screenshot: string quality rejected");
    }

    private static void worldQueryParams() throws Exception {
        ToolContracts.WorldQueryParams p;

        p = validParams(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"range\":16}")));
        check("blocks".equals(p.type()) && p.range() == 16.0, "world.query: blocks range 16 parsed");

        p = validParams(() -> ToolContracts.worldQueryParams(json("{\"type\":\"entities\",\"range\":0.5}")));
        check(p.range() == 0.5, "world.query: fractional range accepted");

        p = validParams(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"range\":64}")));
        check(p.range() == 64.0, "world.query: range boundary 64 accepted");

        p = validParams(() -> ToolContracts.worldQueryParams(json("{\"type\":\"entities\"}")));
        check(p.range() == 16.0, "world.query: missing range defaults to 16");

        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"range\":16}")),
                "world.query: missing type rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"chunks\",\"range\":8}")),
                "world.query: bad type enum rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"range\":0}")),
                "world.query: zero range rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"range\":-3}")),
                "world.query: negative range rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"range\":64.5}")),
                "world.query: range over cap rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"range\":\"16\"}")),
                "world.query: string range rejected");

        // --- filter (M3.5 v1.1): absent/null keeps the v1.0 behaviour
        check(validParams(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"range\":16}")))
                .filter() == null, "world.query: absent filter -> null (v1.0 default)");
        check(validParams(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"filter\":null}")))
                .filter() == null, "world.query: explicit null filter -> v1.0 default");

        p = validParams(() -> ToolContracts.worldQueryParams(
                json("{\"type\":\"blocks\",\"filter\":[\"spruce_log\"]}")));
        check(p.filter().equals(List.of("minecraft:spruce_log")),
                "world.query: bare id gains minecraft: prefix");

        p = validParams(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"filter\":[\"#logs\"]}")));
        check(p.filter().equals(List.of("#minecraft:logs")),
                "world.query: bare #tag gains minecraft: prefix");

        p = validParams(() -> ToolContracts.worldQueryParams(json(
                "{\"type\":\"blocks\",\"filter\":[\"minecraft:oak_log\",\"#somemod:ores\",\"modid:block_2.x\"]}")));
        check(p.filter().equals(List.of("minecraft:oak_log", "#somemod:ores", "modid:block_2.x")),
                "world.query: namespaced ids/tags pass through unchanged");

        p = validParams(() -> ToolContracts.worldQueryParams(json("{\"type\":\"entities\",\"filter\":[\"zombie\"]}")));
        check(p.filter().equals(List.of("minecraft:zombie")),
                "world.query: entities filter entries normalized too");

        // boundary sizes: 16 entries / 128 chars are still valid
        JsonObject maxEntries = new JsonObject();
        maxEntries.addProperty("type", "blocks");
        JsonArray sixteen = new JsonArray();
        for (int i = 0; i < 16; i++) {
            sixteen.add("minecraft:block_" + i);
        }
        maxEntries.add("filter", sixteen);
        check(validParams(() -> ToolContracts.worldQueryParams(maxEntries)).filter().size() == 16,
                "world.query: 16 filter entries accepted (boundary)");
        p = validParams(() -> ToolContracts.worldQueryParams(
                json("{\"type\":\"blocks\",\"filter\":[\"" + "a".repeat(128) + "\"]}")));
        check(p.filter().get(0).equals("minecraft:" + "a".repeat(128)),
                "world.query: 128-char filter entry accepted (boundary, post-normalization)");

        JsonObject seventeen = new JsonObject();
        seventeen.addProperty("type", "blocks");
        JsonArray tooMany = new JsonArray();
        for (int i = 0; i < 17; i++) {
            tooMany.add("minecraft:block_" + i);
        }
        seventeen.add("filter", tooMany);
        expectInvalid(() -> ToolContracts.worldQueryParams(seventeen),
                "world.query: 17 filter entries rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"filter\":[]}")),
                "world.query: empty filter array rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"filter\":[\"\"]}")),
                "world.query: empty filter entry rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(
                json("{\"type\":\"blocks\",\"filter\":[\"" + "a".repeat(129) + "\"]}")),
                "world.query: 129-char filter entry rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"filter\":[\"Spruce_Log\"]}")),
                "world.query: uppercase filter entry rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"filter\":[\"oak log\"]}")),
                "world.query: filter entry with space rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"filter\":[\"a:b:c\"]}")),
                "world.query: filter entry with two colons rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"filter\":[\":stone\"]}")),
                "world.query: leading-colon filter entry rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"filter\":[\"#\"]}")),
                "world.query: bare # tag marker rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"filter\":\"spruce_log\"}")),
                "world.query: non-array filter rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"filter\":[7]}")),
                "world.query: numeric filter entry rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(
                json("{\"type\":\"entities\",\"filter\":[\"#minecraft:skeletons\"]}")),
                "world.query: #tag filter on entities rejected");
    }

    private static void imageOps() throws Exception {
        // --- crop
        BufferedImage base = new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB);
        check(ImageOps.crop(base, new int[]{10, 10, 50, 20}).getWidth() == 50
                && ImageOps.crop(base, new int[]{10, 10, 50, 20}).getHeight() == 20,
                "image: exact crop is 50x20");
        BufferedImage clamped = ImageOps.crop(base, new int[]{90, 50, 100, 100});
        check(clamped.getWidth() == 10 && clamped.getHeight() == 10, "image: bbox clamped to image bounds");
        boolean threw = false;
        try {
            ImageOps.crop(base, new int[]{200, 200, 10, 10});
        } catch (IOException expected) {
            threw = true;
        }
        check(threw, "image: non-intersecting bbox throws");

        // --- JPEG round trip
        BufferedImage gradient = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 64; x++) {
                gradient.setRGB(x, y, (x * 4) << 16 | (y * 5) << 8 | 128);
            }
        }
        byte[] jpeg = ImageOps.encodeJpeg(gradient, 80);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        check(decoded != null && decoded.getWidth() == 64 && decoded.getHeight() == 48,
                "image: JPEG decodable with matching dimensions");
        check(ImageOps.base64Length(jpeg) == ImageOps.base64(jpeg).length(),
                "image: base64 length predictor matches actual");

        // --- quality ladder
        check(Arrays.equals(ImageOps.qualityLadder(80), new int[]{80, 70, 60, 50, 40}),
                "image: ladder 80 -> [80,70,60,50,40]");
        check(Arrays.equals(ImageOps.qualityLadder(45), new int[]{45, 40}), "image: ladder 45 -> [45,40]");
        check(Arrays.equals(ImageOps.qualityLadder(30), new int[]{30}), "image: ladder 30 stays at 30");
        check(Arrays.equals(ImageOps.qualityLadder(40), new int[]{40}), "image: ladder 40 stays at 40");

        // --- scale
        BufferedImage wide = new BufferedImage(2048, 1024, BufferedImage.TYPE_INT_RGB);
        BufferedImage scaled = ImageOps.scaleLongestEdge(wide, 1024);
        check(scaled.getWidth() == 1024 && scaled.getHeight() == 512, "image: longest edge scaled to 1024");
        check(ImageOps.scaleLongestEdge(scaled, 1024) == scaled, "image: small image not rescaled");

        // --- budget: compressible image stays at full size
        ImageOps.Encoded flat = ImageOps.encodeWithinBudget(gradient, 80);
        check(!flat.downscaled() && flat.base64Length() <= ImageOps.MAX_BASE64_LENGTH,
                "image: small image within budget without downscale");
        check(flat.width() == 64 && flat.height() == 48,
                "image: 2MB overload reports the encoded dimensions");

        // --- budget: 4K incompressible noise must degrade (4K noise at q40 still
        // overruns 2MB of base64, so the ladder has to reach the 1024px scale).
        BufferedImage noise = new BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new Random(42);
        int[] row = new int[3840];
        for (int y = 0; y < 2160; y++) {
            for (int x = 0; x < 3840; x++) {
                row[x] = random.nextInt(0x1000000);
            }
            noise.setRGB(0, y, 3840, 1, row, 0, 3840);
        }
        ImageOps.Encoded huge = ImageOps.encodeWithinBudget(noise, 80);
        BufferedImage hugeDecoded = ImageIO.read(new ByteArrayInputStream(huge.jpeg()));
        check(huge.base64Length() <= ImageOps.MAX_BASE64_LENGTH,
                "image: 4K noise ends within budget (" + huge.base64Length() + " b64 chars)");
        check(huge.downscaled() && Math.max(hugeDecoded.getWidth(), hugeDecoded.getHeight()) <= 1024,
                "image: 4K noise downscaled to <=1024 longest edge ("
                        + hugeDecoded.getWidth() + "x" + hugeDecoded.getHeight() + ")");
    }

    private static void contracts() {
        // --- notInGame
        check(!ToolContracts.notInGame().get("in_game").getAsBoolean(), "contracts: notInGame shape");

        // --- screenshotResult
        JsonObject shot = ToolContracts.screenshotResult("QUJD", 64, 48, 1724000000000L, 70, false);
        check("QUJD".equals(shot.get("image_b64").getAsString())
                && "jpeg".equals(shot.get("format").getAsString())
                && shot.get("width").getAsInt() == 64
                && shot.get("height").getAsInt() == 48
                && shot.get("taken_at").getAsLong() == 1724000000000L
                && shot.get("quality").getAsInt() == 70
                && !shot.get("downscaled").getAsBoolean(),
                "contracts: screenshotResult fields");

        // --- statsResult (yaw/pitch: M4.1 v1.3 additions, additive fields)
        ToolContracts.StatsSnapshot stats = new ToolContracts.StatsSnapshot(
                18.5f, 17, 4.2f, 300, 27, 0.6f,
                1.5, 64.0, -12.25, "minecraft:overworld", "survival",
                List.of(new ToolContracts.EffectFact("minecraft:speed", 1200, 1)), true,
                -91.5f, 12.25f,
                true, 6_000, 12, true, true, false, false, "minecraft:plains");
        JsonObject statsJson = ToolContracts.statsResult(stats);
        check(statsJson.get("in_game").getAsBoolean()
                && statsJson.get("health").getAsFloat() == 18.5f
                && statsJson.get("food").getAsInt() == 17
                && statsJson.get("saturation").getAsFloat() == 4.2f
                && statsJson.get("air").getAsInt() == 300
                && statsJson.get("xp_level").getAsInt() == 27
                && statsJson.get("xp_progress").getAsFloat() == 0.6f
                && statsJson.get("position").getAsJsonObject().get("y").getAsDouble() == 64.0
                && "minecraft:overworld".equals(statsJson.get("dimension").getAsString())
                && "survival".equals(statsJson.get("game_mode").getAsString())
                && statsJson.get("effects").getAsJsonArray().size() == 1
                && statsJson.get("effects").getAsJsonArray().get(0).getAsJsonObject()
                        .get("id").getAsString().equals("minecraft:speed")
                && statsJson.get("alive").getAsBoolean()
                && statsJson.get("yaw").getAsFloat() == -91.5f
                && statsJson.get("pitch").getAsFloat() == 12.25f,
                "contracts: statsResult full shape (incl. M4.1 yaw/pitch)");
        // v1.6 environment facts: additive members only, old names untouched
        check(statsJson.get("window_focused").getAsBoolean()
                && statsJson.get("day_time").getAsInt() == 6_000
                && statsJson.get("day").getAsInt() == 12
                && statsJson.get("is_day").getAsBoolean()
                && statsJson.get("has_sky").getAsBoolean()
                && !statsJson.get("raining").getAsBoolean()
                && !statsJson.get("thundering").getAsBoolean()
                && "minecraft:plains".equals(statsJson.get("biome").getAsString()),
                "contracts: statsResult carries the v1.6 window/time/weather/biome facts");

        // --- scanBlocks: solid 3x3x3 with one air hole, range 1
        JsonObject small = ToolContracts.scanBlocks(0, 0, 0, 1,
                (x, y, z) -> (x == 0 && y == 1 && z == 0) ? null : "minecraft:stone");
        check(small.get("count").getAsInt() == 26 && !small.get("truncated").getAsBoolean()
                        && small.get("blocks").getAsJsonArray().size() == 26
                        && "minecraft:stone".equals(small.get("blocks").getAsJsonArray().get(0)
                                .getAsJsonObject().get("block").getAsString()),
                "contracts: scanBlocks counts non-air, skips air");

        // --- scanBlocks: 11x11x11 solid -> truncated at 512
        JsonObject big = ToolContracts.scanBlocks(0, 0, 0, 5, (x, y, z) -> "minecraft:dirt");
        check(big.get("count").getAsInt() == ToolContracts.BLOCKS_CAP
                        && big.get("truncated").getAsBoolean(),
                "contracts: scanBlocks truncates at " + ToolContracts.BLOCKS_CAP);

        // --- scanBlocks: range 0.5 -> radius 1 cube (27 blocks max)
        JsonObject tiny = ToolContracts.scanBlocks(0, 0, 0, 0.5, (x, y, z) -> "minecraft:stone");
        check(tiny.get("count").getAsInt() == 27, "contracts: fractional range uses ceil radius");

        // --- filterEntities: distance + health omission + cap
        List<ToolContracts.EntityFact> facts = List.of(
                new ToolContracts.EntityFact("u-self", "Steve", "minecraft:player", 0, 64, 0, 20f),
                new ToolContracts.EntityFact("u-near", "Zombie", "minecraft:zombie", 5, 64, 0, 12f),
                new ToolContracts.EntityFact("u-far", "Zombie", "minecraft:zombie", 10, 64, 0, 12f),
                new ToolContracts.EntityFact("u-item", "Diamond", "minecraft:item", 2, 64, 0, Float.NaN));
        JsonObject entities = ToolContracts.filterEntities(facts, 0, 64, 0, 8, null);
        check(entities.get("count").getAsInt() == 3
                        && entities.get("entities").getAsJsonArray().size() == 3
                        && !entities.get("truncated").getAsBoolean(),
                "contracts: entities filtered by range (truncated false below cap)");
        JsonElement item = entities.get("entities").getAsJsonArray().get(2);
        check(!item.getAsJsonObject().has("health"),
                "contracts: NaN health omitted from entity entry");

        // --- filterEntities: item entities carry the dropped stack's registry
        // id + count (T7); non-item entities keep the exact pre-T7 shape
        List<ToolContracts.EntityFact> dropFacts = List.of(
                new ToolContracts.EntityFact("d-1", "Diamond", "minecraft:item",
                        2, 64, 1, Float.NaN, "minecraft:oak_log", 3),
                new ToolContracts.EntityFact("d-2", "Zombie", "minecraft:zombie",
                        3, 64, 1, 8f));
        JsonObject drops = ToolContracts.filterEntities(dropFacts, 0, 64, 0, 8, null);
        JsonObject dropEntry = drops.get("entities").getAsJsonArray().get(0).getAsJsonObject();
        check(dropEntry.has("item")
                        && "minecraft:oak_log".equals(dropEntry.get("item").getAsString())
                        && dropEntry.get("count").getAsInt() == 3,
                "contracts: item entity carries stack registry id + count (T7)");
        JsonObject nonItemEntry = drops.get("entities").getAsJsonArray().get(1).getAsJsonObject();
        check(!nonItemEntry.has("item") && !nonItemEntry.has("count"),
                "contracts: non-item entity keeps the pre-T7 shape (no item/count)");
        check(ToolContracts.filterEntities(dropFacts, 0, 64, 0, 16,
                        List.of("minecraft:item")).get("count").getAsInt() == 1,
                "contracts: entities type filter picks the item entity by type id");

        // --- filterEntities: M4 additive category + width fields
        List<ToolContracts.EntityFact> mobFacts = List.of(
                new ToolContracts.EntityFact("m-1", "Zombie", "minecraft:zombie",
                        2, 64, 1, 20f, null, 0, "monster", 0.6),
                new ToolContracts.EntityFact("m-2", "Cow", "minecraft:cow",
                        3, 64, 1, 10f, null, 0, "creature", 0.9),
                new ToolContracts.EntityFact("m-3", "Old", "minecraft:zombie",
                        1, 64, 1, 20f));
        JsonObject mobs = ToolContracts.filterEntities(mobFacts, 0, 64, 0, 8, null);
        JsonObject zombie = mobs.get("entities").getAsJsonArray().get(0).getAsJsonObject();
        check(zombie.has("category") && "monster".equals(zombie.get("category").getAsString())
                        && zombie.has("width") && Math.abs(zombie.get("width").getAsDouble() - 0.6) < 1e-9,
                "contracts: entity entry carries registry mob category + collision width (M4)");
        JsonObject cow = mobs.get("entities").getAsJsonArray().get(1).getAsJsonObject();
        check("creature".equals(cow.get("category").getAsString()),
                "contracts: passive mobs report their own category");
        JsonObject oldShape = mobs.get("entities").getAsJsonArray().get(2).getAsJsonObject();
        check(!oldShape.has("category") && !oldShape.has("width"),
                "contracts: pre-M4 constructors keep the old entry shape (additive only)");

        List<ToolContracts.EntityFact> crowd = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            crowd.add(new ToolContracts.EntityFact("u" + i, "E" + i, "minecraft:zombie", i % 4, 64, 0, 1f));
        }
        check(ToolContracts.filterEntities(crowd, 0, 64, 0, 16, null).get("count").getAsInt()
                        == ToolContracts.ENTITIES_CAP,
                "contracts: entities capped at " + ToolContracts.ENTITIES_CAP);
        check(ToolContracts.filterEntities(crowd, 0, 64, 0, 16, null).get("truncated").getAsBoolean(),
                "contracts: entity overflow flagged truncated (v1.1 fix)");

        List<ToolContracts.EntityFact> exactly = new ArrayList<>();
        for (int i = 0; i < ToolContracts.ENTITIES_CAP; i++) {
            exactly.add(new ToolContracts.EntityFact("u" + i, "E" + i, "minecraft:zombie", 1, 64, 0, 1f));
        }
        JsonObject full = ToolContracts.filterEntities(exactly, 0, 64, 0, 16, null);
        check(full.get("count").getAsInt() == ToolContracts.ENTITIES_CAP
                        && !full.get("truncated").getAsBoolean(),
                "contracts: exactly CAP entities -> truncated false");

        // --- filterEntities: type filter (v1.1)
        JsonObject zombies = ToolContracts.filterEntities(facts, 0, 64, 0, 16, List.of("minecraft:zombie"));
        check(zombies.get("count").getAsInt() == 2
                        && "minecraft:zombie".equals(zombies.get("entities").getAsJsonArray().get(0)
                                .getAsJsonObject().get("type").getAsString()),
                "contracts: entities type filter keeps only matching type");
        check(ToolContracts.filterEntities(facts, 0, 64, 0, 16, List.of("minecraft:creeper"))
                        .get("count").getAsInt() == 0,
                "contracts: entities type filter with no matches -> empty");
        check(ToolContracts.filterEntities(crowd, 0, 64, 0, 16, List.of("minecraft:zombie"))
                        .get("truncated").getAsBoolean(),
                "contracts: filtered entity overflow still flags truncated");

        // --- scanBlocks filtered (v1.1): id match, nearest-first, tie-break,
        // truncation, #tag and mixed filters. Player at (0.5, 64.5, 0.5) -
        // block centers are (x+0.5, y+0.5, z+0.5), so distSq(x,y,z) is
        // dx^2+dy^2+dz^2 against those offsets.
        ToolContracts.BlockProbe woods = (x, y, z) -> {
            if (x == 0 && y == 65 && z == 0) return "minecraft:oak_log";   // distSq 1
            if (x == 2 && y == 64 && z == 0) return "minecraft:oak_log";   // distSq 4
            if (x == -3 && y == 64 && z == 0) return "minecraft:oak_log";  // distSq 9 (tie, x breaks it)
            if (x == 0 && y == 64 && z == 3) return "minecraft:oak_log";   // distSq 9 (tie)
            if (x == 4 && y == 64 && z == 0) return "minecraft:stone";     // non-match
            return null;
        };
        JsonObject filtered = ToolContracts.scanBlocks(0.5, 64.5, 0.5, 8, woods,
                ToolContracts.BlockFilter.parse(List.of("minecraft:oak_log")), null);
        JsonArray kept = filtered.get("blocks").getAsJsonArray();
        check(filtered.get("count").getAsInt() == 4 && !filtered.get("truncated").getAsBoolean()
                        && kept.size() == 4,
                "contracts: filtered scan keeps only id matches");
        boolean nearestOrder = true;
        int[][] expected = {{0, 65, 0}, {2, 64, 0}, {-3, 64, 0}, {0, 64, 3}};
        double lastDist = -1;
        for (int i = 0; i < expected.length; i++) {
            JsonObject block = kept.get(i).getAsJsonObject();
            nearestOrder &= block.get("x").getAsInt() == expected[i][0]
                    && block.get("y").getAsInt() == expected[i][1]
                    && block.get("z").getAsInt() == expected[i][2]
                    && "minecraft:oak_log".equals(block.get("block").getAsString());
            double dx = block.get("x").getAsInt() + 0.5 - 0.5;
            double dy = block.get("y").getAsInt() + 0.5 - 64.5;
            double dz = block.get("z").getAsInt() + 0.5 - 0.5;
            double distSq = dx * dx + dy * dy + dz * dz;
            nearestOrder &= distSq >= lastDist;
            lastDist = distSq;
        }
        check(nearestOrder, "contracts: filtered scan ordered nearest-first (ties by x,y,z)");

        // 40 matches -> the 32 NEAREST survive, truncated true
        ToolContracts.BlockProbe row = (x, y, z) ->
                (y == 64 && z == 0 && x >= 1 && x <= 40) ? "minecraft:oak_log" : null;
        JsonObject overflow = ToolContracts.scanBlocks(0.5, 64.5, 0.5, 64, row,
                ToolContracts.BlockFilter.parse(List.of("minecraft:oak_log")), null);
        JsonArray overflowBlocks = overflow.get("blocks").getAsJsonArray();
        check(overflow.get("count").getAsInt() == ToolContracts.FILTERED_BLOCKS_CAP
                        && overflow.get("truncated").getAsBoolean(),
                "contracts: filtered scan caps at " + ToolContracts.FILTERED_BLOCKS_CAP + " with truncated");
        check(overflowBlocks.get(0).getAsJsonObject().get("x").getAsInt() == 1
                        && overflowBlocks.get(overflowBlocks.size() - 1).getAsJsonObject().get("x").getAsInt() == 32,
                "contracts: overflow keeps the NEAREST 32 (x=1..32 of 1..40)");

        // exactly 32 matches -> truncated false (boundary)
        ToolContracts.BlockProbe row32 = (x, y, z) ->
                (y == 64 && z == 0 && x >= 1 && x <= 32) ? "minecraft:oak_log" : null;
        JsonObject exact = ToolContracts.scanBlocks(0.5, 64.5, 0.5, 64, row32,
                ToolContracts.BlockFilter.parse(List.of("minecraft:oak_log")), null);
        check(exact.get("count").getAsInt() == ToolContracts.FILTERED_BLOCKS_CAP
                        && !exact.get("truncated").getAsBoolean(),
                "contracts: exactly 32 matches -> truncated false");

        // #tag filter through a fake TagProbe (real one wraps BlockState.is)
        ToolContracts.TagProbe logsTag = (name, tagId) -> "minecraft:logs".equals(tagId)
                && (name.equals("minecraft:oak_log") || name.equals("minecraft:spruce_log"));
        ToolContracts.BlockProbe grove = (x, y, z) -> switch (x + "," + y + "," + z) {
            case "1,64,0" -> "minecraft:oak_log";     // distSq 1
            case "-2,64,0" -> "minecraft:spruce_log"; // distSq 4
            case "3,64,0" -> "minecraft:stone";       // not in tag
            default -> null;
        };
        JsonObject tagged = ToolContracts.scanBlocks(0.5, 64.5, 0.5, 8, grove,
                ToolContracts.BlockFilter.parse(List.of("#minecraft:logs")), logsTag);
        JsonArray taggedBlocks = tagged.get("blocks").getAsJsonArray();
        check(tagged.get("count").getAsInt() == 2
                        && "minecraft:oak_log".equals(taggedBlocks.get(0).getAsJsonObject().get("block").getAsString())
                        && "minecraft:spruce_log".equals(taggedBlocks.get(1).getAsJsonObject().get("block").getAsString()),
                "contracts: #tag filter matches tag members only, nearest-first");

        // mixed id + tag entries
        JsonObject mixed = ToolContracts.scanBlocks(0.5, 64.5, 0.5, 8, grove,
                ToolContracts.BlockFilter.parse(List.of("minecraft:stone", "#minecraft:logs")), logsTag);
        check(mixed.get("count").getAsInt() == 3 && !mixed.get("truncated").getAsBoolean(),
                "contracts: mixed id+#tag filter unions matches");

        // nothing matches -> empty result, not an error
        JsonObject miss = ToolContracts.scanBlocks(0.5, 64.5, 0.5, 8, woods,
                ToolContracts.BlockFilter.parse(List.of("minecraft:diamond_ore")), null);
        check(miss.get("count").getAsInt() == 0 && miss.get("blocks").getAsJsonArray().size() == 0
                        && !miss.get("truncated").getAsBoolean(),
                "contracts: filter with no matches -> empty, truncated false");

        // --- T6 dense-scene regression (M3.5-T5a evidence): a NEAR match must
        // survive a filtered query with far more than 512 matches. Player at
        // (0.5,64.5,0.5) - i.e. standing INSIDE block (0,64,0) (distSq 0) with
        // a second match at distSq 1; the rel-band filler yields ~1700 farther
        // matches across the cube (x ascending - the same corner order that
        // made the unfiltered v1.0 path drop near blocks at range>=5.5).
        // Collect-all + sort must keep the two nearest regardless.
        ToolContracts.BlockProbe dense = (x, y, z) -> {
            if (x == 0 && y == 65 && z == 0) return "minecraft:oak_log"; // distSq 1 - the "3.71-block target" stand-in
            if (x == 0 && y == 64 && z == 0) return "minecraft:oak_log"; // distSq 0 - block at the player's feet
            int rel = x + (y - 64) + z;
            return rel >= -5 && rel <= 5 ? "minecraft:oak_log" : null; // ~1700 farther fillers
        };
        JsonObject denseQuery = ToolContracts.scanBlocks(0.5, 64.5, 0.5, 8, dense,
                ToolContracts.BlockFilter.parse(List.of("minecraft:oak_log")), null);
        JsonArray denseBlocks = denseQuery.get("blocks").getAsJsonArray();
        boolean nearKept = false;
        for (JsonElement e : denseBlocks) {
            JsonObject b = e.getAsJsonObject();
            nearKept |= b.get("x").getAsInt() == 0 && b.get("y").getAsInt() == 65 && b.get("z").getAsInt() == 0;
        }
        check(denseQuery.get("count").getAsInt() == ToolContracts.FILTERED_BLOCKS_CAP
                        && denseQuery.get("truncated").getAsBoolean()
                        && denseBlocks.get(0).getAsJsonObject().get("x").getAsInt() == 0
                        && denseBlocks.get(0).getAsJsonObject().get("y").getAsInt() == 64
                        && denseBlocks.get(0).getAsJsonObject().get("z").getAsInt() == 0
                        && nearKept,
                "contracts: T6 dense filtered query keeps the NEAREST matches despite >512 matches");

        // containment law (the T5a suggested regression): a smaller range's
        // hits must all appear in the bigger range's (truncated) result -
        // truncation may only ever drop the FAR tail.
        ToolContracts.BlockProbe sparse = (x, y, z) ->
                ((y == 64 || y == 65) && Math.abs(x) <= 6 && Math.abs(z) <= 6
                        && (x + z) % 2 == 0) ? "minecraft:oak_log" : null;
        JsonObject smallRange = ToolContracts.scanBlocks(0.5, 64.5, 0.5, 1.5, sparse,
                ToolContracts.BlockFilter.parse(List.of("minecraft:oak_log")), null);
        JsonObject bigRange = ToolContracts.scanBlocks(0.5, 64.5, 0.5, 6.4, sparse,
                ToolContracts.BlockFilter.parse(List.of("minecraft:oak_log")), null);
        java.util.Set<String> bigSet = new java.util.HashSet<>();
        for (JsonElement e : bigRange.get("blocks").getAsJsonArray()) {
            JsonObject b = e.getAsJsonObject();
            bigSet.add(b.get("x") + "," + b.get("y") + "," + b.get("z"));
        }
        boolean contained = !smallRange.get("blocks").getAsJsonArray().isEmpty();
        for (JsonElement e : smallRange.get("blocks").getAsJsonArray()) {
            JsonObject b = e.getAsJsonObject();
            contained &= bigSet.contains(b.get("x") + "," + b.get("y") + "," + b.get("z"));
        }
        check(contained && !smallRange.get("truncated").getAsBoolean()
                        && bigRange.get("truncated").getAsBoolean(),
                "contracts: T6 small-range hits are contained in the big-range result (truncation drops only the far tail)");

        // memory guard: a pathological filter over a solid cube flags truncated
        // at the guard without building an unbounded list
        ToolContracts.BlockProbe solid = (x, y, z) -> "minecraft:stone";
        JsonObject guarded = ToolContracts.scanBlocks(0.5, 64.5, 0.5, 64, solid,
                ToolContracts.BlockFilter.parse(List.of("minecraft:stone")), null);
        check(guarded.get("count").getAsInt() == ToolContracts.FILTERED_BLOCKS_CAP
                        && guarded.get("truncated").getAsBoolean(),
                "contracts: T6 filtered memory guard flags truncated on pathological filters");
    }

    // ------------------------------------------------------------------ M2-B: events

    private static void eventsContracts() throws Exception {
        EventsContracts.EventLevel CRITICAL = EventsContracts.EventLevel.CRITICAL;
        EventsContracts.EventLevel WARNING = EventsContracts.EventLevel.WARNING;
        EventsContracts.EventLevel INFO = EventsContracts.EventLevel.INFO;

        // --- level ordering + parsing
        check(CRITICAL.atLeast(WARNING) && WARNING.atLeast(INFO) && CRITICAL.atLeast(CRITICAL)
                        && !INFO.atLeast(WARNING) && !WARNING.atLeast(CRITICAL),
                "events: level ordering CRITICAL > WARNING > INFO");
        check(EventsContracts.EventLevel.fromName("critical") == CRITICAL
                        && EventsContracts.EventLevel.fromName("WARNING") == WARNING
                        && EventsContracts.EventLevel.fromName("nope") == null
                        && EventsContracts.EventLevel.fromName(null) == null,
                "events: level name parsing (case-insensitive, unknown/null -> null)");

        // --- subscription matching: exact / wildcard / empty / level filter
        EventsContracts.Subscription all = new EventsContracts.Subscription(java.util.Set.of("*"), null);
        check(all.matches("chat", INFO) && all.matches("death", CRITICAL),
                "events: \"*\" subscription matches every type");
        EventsContracts.Subscription empty = new EventsContracts.Subscription(java.util.Set.of(), WARNING);
        check(empty.matches("chat", WARNING) && empty.matches("death", CRITICAL) && !empty.matches("chat", INFO),
                "events: empty types = all types, min_level still filters");
        EventsContracts.Subscription chatOnly = new EventsContracts.Subscription(java.util.Set.of("chat"), null);
        check(chatOnly.matches("chat", INFO) && !chatOnly.matches("gui_open", WARNING),
                "events: exact type match, other types rejected");
        EventsContracts.Subscription criticalOnly =
                new EventsContracts.Subscription(java.util.Set.of("death", "fire"), CRITICAL);
        check(criticalOnly.matches("death", CRITICAL) && !criticalOnly.matches("chat", CRITICAL),
                "events: exact types + CRITICAL floor");
        check(new EventsContracts.Subscription(java.util.Set.of("chat"), null).effectiveMinLevel() == INFO,
                "events: null min_level defaults to INFO (no filtering)");

        // --- events.subscribe params (frozen schema tools/events.subscribe.json)
        EventsContracts.SubscribeParams p = validParams(() ->
                EventsContracts.subscribeParams(json("{\"types\":[\"chat\",\"death\"]}")));
        check(p.types().size() == 2 && p.types().contains("chat") && p.types().contains("death")
                        && p.minLevel() == null,
                "events.subscribe: types parsed, min_level defaults null");
        p = validParams(() -> EventsContracts.subscribeParams(
                json("{\"types\":[\"*\"],\"min_level\":\"CRITICAL\"}")));
        check(p.types().contains("*") && p.minLevel() == CRITICAL,
                "events.subscribe: wildcard + CRITICAL min_level parsed");
        p = validParams(() -> EventsContracts.subscribeParams(json("{\"types\":[]}")));
        check(p.types().isEmpty(), "events.subscribe: empty types array accepted (= all events)");
        p = validParams(() -> EventsContracts.subscribeParams(
                json("{\"types\":[\"chat\"],\"min_level\":null}")));
        check(p.minLevel() == null, "events.subscribe: explicit null min_level accepted");
        p = validParams(() -> EventsContracts.subscribeParams(
                json("{\"types\":[\"chat\",\"chat\"]}")));
        check(p.types().size() == 1, "events.subscribe: duplicate types collapse");

        expectInvalid(() -> EventsContracts.subscribeParams(json("{}")),
                "events.subscribe: missing types rejected");
        expectInvalid(() -> EventsContracts.subscribeParams(json("{\"types\":\"chat\"}")),
                "events.subscribe: non-array types rejected");
        expectInvalid(() -> EventsContracts.subscribeParams(json("{\"types\":[42]}")),
                "events.subscribe: non-string type entry rejected");
        expectInvalid(() -> EventsContracts.subscribeParams(json("{\"types\":null}")),
                "events.subscribe: null types rejected");
        expectInvalid(() -> EventsContracts.subscribeParams(
                json("{\"types\":[\"chat\"],\"min_level\":\"LOUD\"}")),
                "events.subscribe: unknown min_level rejected");
        expectInvalid(() -> EventsContracts.subscribeParams(
                json("{\"types\":[\"chat\"],\"min_level\":3}")),
                "events.subscribe: numeric min_level rejected");

        // --- subscribe result shape
        JsonObject result = EventsContracts.subscribeResult(
                new EventsContracts.SubscribeParams(new java.util.LinkedHashSet<>(List.of("chat", "gui_open")),
                        WARNING));
        check(result.get("subscribed").getAsBoolean()
                        && result.get("types").getAsJsonArray().size() == 2
                        && "WARNING".equals(result.get("min_level").getAsString())
                        && result.get("note").getAsString().contains("no pushes"),
                "events.subscribe: result echoes types/min_level/note");
        JsonObject defaultLevel = EventsContracts.subscribeResult(
                new EventsContracts.SubscribeParams(java.util.Set.of("chat"), null));
        check("INFO".equals(defaultLevel.get("min_level").getAsString()),
                "events.subscribe: result echoes effective INFO default");

        // --- notification frame assembly
        JsonObject data = new JsonObject();
        data.addProperty("hp", 3);
        JsonObject frame = EventsContracts.notification("fire", data, CRITICAL, 1724000000.5, 7);
        check("notification".equals(frame.get("type").getAsString())
                        && "fire".equals(frame.get("event").getAsString()),
                "events: notification type/event fields");
        check(frame.get("data").getAsJsonObject().get("hp").getAsInt() == 3
                        && "CRITICAL".equals(frame.get("data").getAsJsonObject().get("level").getAsString()),
                "events: level injected into data alongside original fields");
        check(frame.get("timestamp").getAsJsonPrimitive().isNumber()
                        && frame.get("timestamp").getAsDouble() == 1724000000.5,
                "events: timestamp is a float number (epoch seconds)");
        check(frame.get("seq").getAsJsonPrimitive().isNumber()
                        && frame.get("seq").getAsLong() == 7
                        && frame.get("seq").getAsDouble() == 7.0,
                "events: seq is an integral number");
        check(!data.has("level"),
                "events: caller data object not mutated (deep copy)");
        JsonObject preset = new JsonObject();
        preset.addProperty("level", "INFO");
        frame = EventsContracts.notification("x", preset, CRITICAL, 0.0, 0);
        check("INFO".equals(frame.get("data").getAsJsonObject().get("level").getAsString()),
                "events: existing data.level wins over injected level");
        frame = EventsContracts.notification("x", null, WARNING, 0.0, 0);
        check(frame.get("data").getAsJsonObject().size() == 1
                        && frame.get("data").getAsJsonObject().has("level"),
                "events: null data yields level-only payload");
        check(EventsContracts.timestampNowSeconds(1724000000123L) == 1724000000.123,
                "events: milliseconds -> epoch seconds conversion (Python time.time() units)");
    }

    private static void damageContracts() {
        check(DamageContracts.damageData(null, 20.0F, null) == null
                        && DamageContracts.damageData(20.0F, 20.0F, null) == null
                        && DamageContracts.damageData(19.0F, 20.0F, null) == null,
                "damage: baseline, unchanged health and healing do not emit");
        JsonObject unknown = DamageContracts.damageData(20.0F, 19.5F, null);
        check(unknown != null && unknown.get("health_before").getAsFloat() == 20.0F
                        && unknown.get("health_after").getAsFloat() == 19.5F
                        && unknown.get("amount").getAsFloat() == 0.5F
                        && "unknown".equals(unknown.get("damage_type").getAsString())
                        && unknown.get("attacker").isJsonNull()
                        && unknown.get("direct_entity").isJsonNull(),
                "damage: any actual health decrease emits with honest unknown attribution");
        DamageContracts.EntityFact zombie = new DamageContracts.EntityFact(
                "Zombie", "minecraft:zombie", "zombie-uuid");
        JsonObject hit = DamageContracts.damageData(19.5F, 16.0F,
                new DamageContracts.SourceFact("minecraft:mob_attack", zombie, zombie));
        check(hit.get("amount").getAsFloat() == 3.5F
                        && "minecraft:mob_attack".equals(hit.get("damage_type").getAsString())
                        && "Zombie".equals(hit.getAsJsonObject("attacker").get("name").getAsString())
                        && "minecraft:zombie".equals(hit.getAsJsonObject("direct_entity").get("type").getAsString())
                        && "zombie-uuid".equals(hit.getAsJsonObject("attacker").get("uuid").getAsString()),
                "damage: source type, attacker, direct entity and health delta are preserved");

        DamageContracts.Attributor late = new DamageContracts.Attributor();
        late.sample(20.0F, null, 0, 0, 0);
        check(late.sample(19.5F, null, 0, 0, 1000) == null,
                "damage: health-first transition waits for attribution instead of emitting unknown");
        JsonObject lateHit = late.sample(19.5F,
                new DamageContracts.SourceFact("minecraft:mob_attack", zombie, zombie), 1, 1050, 1050);
        check(lateHit != null && "Zombie".equals(lateHit.getAsJsonObject("attacker").get("name").getAsString())
                        && lateHit.get("health_before").getAsFloat() == 20.0F
                        && lateHit.get("health_after").getAsFloat() == 19.5F,
                "damage: source arriving after health is paired within the grace window");

        DamageContracts.Attributor unknownLater = new DamageContracts.Attributor();
        unknownLater.sample(20.0F, null, 0, 0, 0);
        unknownLater.sample(19.0F, null, 0, 0, 1000);
        check(unknownLater.sample(19.0F, null, 0, 0, 1249) == null
                        && "unknown".equals(unknownLater.sample(19.0F, null, 0, 0, 1250)
                        .get("damage_type").getAsString()),
                "damage: unknown is emitted only after the 250ms grace window");

        DamageContracts.Attributor stale = new DamageContracts.Attributor();
        stale.sample(20.0F, null, 0, 0, 0);
        stale.sample(19.0F, null, 0, 0, 1000);
        check(stale.sample(19.0F, new DamageContracts.SourceFact("minecraft:mob_attack", zombie, zombie),
                        1, 0, 1000) == null,
                "damage: source older than the grace window is not attributed");
        check("unknown".equals(stale.sample(19.0F, null, 0, 0, 1250)
                        .get("damage_type").getAsString()),
                "damage: stale source still resolves to honest unknown");

        DamageContracts.Attributor noReuse = new DamageContracts.Attributor();
        noReuse.sample(20.0F, null, 0, 0, 0);
        noReuse.sample(19.5F, new DamageContracts.SourceFact("minecraft:mob_attack", zombie, zombie),
                1, 1000, 1000);
        noReuse.sample(19.0F, null, 1, 1000, 1100);
        check("unknown".equals(noReuse.sample(19.0F, null, 1, 1000, 1350)
                        .get("damage_type").getAsString()),
                "damage: consumed source is not reused for the next hit");

        DamageContracts.Attributor combined = new DamageContracts.Attributor();
        combined.sample(20.0F, null, 0, 0, 0);
        combined.sample(19.0F, null, 0, 0, 1000);
        combined.sample(18.0F, null, 0, 0, 1100);
        JsonObject combinedData = combined.sample(18.0F, null, 0, 0, 1350);
        check(combinedData.get("health_before").getAsFloat() == 20.0F
                        && combinedData.get("health_after").getAsFloat() == 18.0F,
                "damage: pending hits keep the earliest before and latest after values");
    }

    private static void streamThrottle() {
        // Deterministic injected clock; 6s window like the real stream.
        EventsContracts.StreamThrottle<String> throttle =
                new EventsContracts.StreamThrottle<>(6_000);

        check(throttle.offer("a", 0) == EventsContracts.StreamThrottle.Decision.PUSH_NOW,
                "throttle: first frame pushes immediately");
        check(throttle.offer("b", 5_000) == EventsContracts.StreamThrottle.Decision.DEFER
                        && throttle.hasPending(),
                "throttle: frame inside window defers to pending slot");
        check(throttle.takeDue(5_999) == null,
                "throttle: flush not due before the boundary");
        check(throttle.offer("c", 5_500) == EventsContracts.StreamThrottle.Decision.DEFER,
                "throttle: deferred frame replaces pending (latest wins)");
        check("c".equals(throttle.takeDue(6_000)) && !throttle.hasPending(),
                "throttle: delayed flush fires at the boundary with the newest frame");
        check(throttle.offer("d", 11_999) == EventsContracts.StreamThrottle.Decision.DEFER,
                "throttle: window closed again right after a boundary flush");
        check(throttle.offer("d", 12_000) == EventsContracts.StreamThrottle.Decision.PUSH_NOW,
                "throttle: window reopens exactly min-interval after the flush");

        // PUSH_NOW cancels an armed flush and clears the slot (N.E.K.O race note).
        EventsContracts.StreamThrottle<String> armed =
                new EventsContracts.StreamThrottle<>(6_000);
        armed.offer("a", 0);
        armed.offer("b", 1_000);
        check(armed.flushAtMs() == 6_000,
                "throttle: one flush armed at window-open time");
        check(armed.offer("c", 7_000) == EventsContracts.StreamThrottle.Decision.PUSH_NOW,
                "throttle: window reopened by wall clock");
        check(armed.flushAtMs() == -1 && !armed.hasPending(),
                "throttle: immediate push disarms flush and clears pending slot");
        check(armed.takeDue(8_000) == null,
                "throttle: no stale flush surfaces an older frame");

        EventsContracts.StreamThrottle<String> zero =
                new EventsContracts.StreamThrottle<>(0);
        check(zero.offer("x", 1) == EventsContracts.StreamThrottle.Decision.PUSH_NOW
                        && zero.offer("y", 1) == EventsContracts.StreamThrottle.Decision.PUSH_NOW,
                "throttle: zero interval never defers");
    }

    private static void streamLadder() throws Exception {
        // --- ladder parameterization
        check(Arrays.equals(ImageOps.streamQualityLadder(80), new int[]{80, 65, 50, 40, 30}),
                "ladder: quality 80 -> [80,65,50,40,30]");
        check(Arrays.equals(ImageOps.streamQualityLadder(100), new int[]{100, 65, 50, 40, 30}),
                "ladder: quality 100 -> [100,65,50,40,30]");
        check(Arrays.equals(ImageOps.streamQualityLadder(65), new int[]{65, 50, 40, 30}),
                "ladder: quality 65 -> [65,50,40,30]");
        check(Arrays.equals(ImageOps.streamQualityLadder(30), new int[]{30}),
                "ladder: quality 30 stays at 30");
        check(Arrays.equals(ImageOps.streamQualityLadder(20), new int[]{20}),
                "ladder: quality below the list floor collapses to itself");
        check(Arrays.equals(ImageOps.streamEdgeLadder(1024), new int[]{1024, 512, 256}),
                "ladder: edge 1024 -> [1024,512,256]");
        check(Arrays.equals(ImageOps.streamEdgeLadder(100), new int[]{100, 50, 25}),
                "ladder: edge 100 -> [100,50,25]");
        check(ImageOps.streamEdgeLadder(0).length == 0,
                "ladder: edge 0 disables scaling (empty ladder)");

        // --- streaming budget encode: incompressible noise forces ladder descent
        BufferedImage noise = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new Random(7);
        int[] row = new int[800];
        for (int y = 0; y < 600; y++) {
            for (int x = 0; x < 800; x++) {
                row[x] = random.nextInt(0x1000000);
            }
            noise.setRGB(0, y, 800, 1, row, 0, 800);
        }
        ImageOps.Encoded stream = ImageOps.encodeWithinBudget(noise, EventsContracts.STREAM_QUALITY,
                EventsContracts.STREAM_MAX_BASE64, EventsContracts.STREAM_LONGEST_EDGE);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stream.jpeg()));
        check(stream.base64Length() <= EventsContracts.STREAM_MAX_BASE64,
                "ladder: noise stream frame ends within the 100KB budget ("
                        + stream.base64Length() + " b64 chars)");
        check(stream.quality() <= EventsContracts.STREAM_QUALITY
                        && Math.max(decoded.getWidth(), decoded.getHeight()) <= EventsContracts.STREAM_LONGEST_EDGE,
                "ladder: descent respected quality cap and edge cap (q" + stream.quality()
                        + " " + decoded.getWidth() + "x" + decoded.getHeight() + ")");
        check(stream.downscaled() && stream.width() == decoded.getWidth()
                        && stream.height() == decoded.getHeight(),
                "ladder: Encoded reports downscaled + the encoded image's dimensions");

        // --- small image, generous budget: first rung, no descent
        BufferedImage flat = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ImageOps.Encoded easy = ImageOps.encodeWithinBudget(flat, 80, EventsContracts.STREAM_MAX_BASE64, 1024);
        check(!easy.downscaled() && easy.quality() == 80 && easy.width() == 64 && easy.height() == 48,
                "ladder: tiny image stays at first rung unscaled");

        // --- impossible budget: ship the smallest attempt, never drop the frame
        ImageOps.Encoded lastResort = ImageOps.encodeWithinBudget(noise, 80, 500, 1024);
        check(lastResort != null && lastResort.jpeg().length > 0 && lastResort.base64Length() > 500,
                "ladder: unreachable budget ships the smallest attempt (no drop)");
        check(lastResort.quality() == 30 && lastResort.downscaled(),
                "ladder: smallest attempt is the last rung (q30, scaled)");

        // --- ring buffer maxlen (spec: 3 frames)
        ArrayDeque<Integer> ring = new ArrayDeque<>();
        for (int i = 1; i <= 5; i++) {
            EventsContracts.ringAdd(ring, i, EventsContracts.STREAM_RING_SIZE);
        }
        check(ring.size() == EventsContracts.STREAM_RING_SIZE && ring.peekFirst() == 3 && ring.peekLast() == 5,
                "events: ring buffer keeps the 3 newest frames, oldest evicted");
    }

    // ------------------------------------------------------------------ M2-C: gui state

    private static void guiContracts() throws Exception {
        // --- params: frozen schema declares an empty object
        GuiContracts.guiStateParams(json("{}"));
        check(true, "gui: empty params accepted");
        expectInvalid(() -> {
            GuiContracts.guiStateParams(json("{\"tier\":\"full\"}"));
            return null;
        }, "gui: non-empty params rejected");

        // --- collector cap + truncation (world.query discipline)
        GuiContracts.WidgetCollector small = new GuiContracts.WidgetCollector();
        small.add(new GuiContracts.WidgetNode("Button", 1, 2, 3, 4, true, true, "OK", null));
        check(small.nodes().size() == 1 && !small.truncated() && !small.full(),
                "gui: collector accepts nodes below the cap");
        GuiContracts.WidgetCollector full = new GuiContracts.WidgetCollector();
        for (int i = 0; i < GuiContracts.WIDGETS_CAP + 5; i++) {
            full.add(new GuiContracts.WidgetNode("W" + i, 0, 0, 1, 1, true, true, null, null));
        }
        check(full.nodes().size() == GuiContracts.WIDGETS_CAP && full.truncated() && full.full(),
                "gui: collector stops at " + GuiContracts.WIDGETS_CAP + " and flags truncated");

        // --- widget node JSON: fields, empty message omitted, EditBox text kept
        GuiContracts.WidgetCollector widgets = new GuiContracts.WidgetCollector();
        widgets.add(new GuiContracts.WidgetNode("Button", 10, 20, 200, 20, true, false, "Done", null));
        widgets.add(new GuiContracts.WidgetNode("EditBox", 5, 6, 100, 16, true, true, "", "hello"));
        widgets.add(new GuiContracts.WidgetNode("Image", 0, 0, 16, 16, false, true, null, null));
        JsonObject standard = GuiContracts.guiStateResult(true, "ChatScreen", widgets, null);
        JsonObject first = standard.get("widgets").getAsJsonArray().get(0).getAsJsonObject();
        check("Button".equals(first.get("type").getAsString())
                        && first.get("x").getAsInt() == 10 && first.get("y").getAsInt() == 20
                        && first.get("width").getAsInt() == 200 && first.get("height").getAsInt() == 20
                        && first.get("visible").getAsBoolean() && !first.get("active").getAsBoolean()
                        && "Done".equals(first.get("message").getAsString()),
                "gui: widget node carries type/geometry/flags/message");
        JsonObject second = standard.get("widgets").getAsJsonArray().get(1).getAsJsonObject();
        check(!second.has("message") && "hello".equals(second.get("text").getAsString()),
                "gui: empty message omitted, EditBox text included");
        JsonObject third = standard.get("widgets").getAsJsonArray().get(2).getAsJsonObject();
        check(!third.has("message") && !third.has("text") && !third.get("visible").getAsBoolean(),
                "gui: null message/text omitted, invisible widget still reported");

        // --- standard response shape (non-container: no slots field)
        check(standard.get("screen_open").getAsBoolean() && standard.get("in_game").getAsBoolean()
                        && "ChatScreen".equals(standard.get("screen_class").getAsString())
                        && !standard.has("slots") && !standard.get("truncated").getAsBoolean(),
                "gui: standard result shape, slots omitted for non-container screens");

        // --- container response shape + slot JSON (item null vs name, note)
        GuiContracts.WidgetCollector empty = new GuiContracts.WidgetCollector();
        List<GuiContracts.SlotFact> slots = List.of(
                new GuiContracts.SlotFact(0, 0, 100, 20, GuiContracts.ROLE_RESULT, "minecraft:oak_log", 12, null),
                new GuiContracts.SlotFact(9, 9, 30, 40, GuiContracts.ROLE_PLAYER, null, 0, null),
                new GuiContracts.SlotFact(40, 40, 77, 62, GuiContracts.ROLE_CONTAINER, null, 0,
                        "item access failed: boom"));
        JsonObject container = GuiContracts.guiStateResult(true, "InventoryScreen", empty, slots);
        check(container.has("slots") && container.get("widgets").getAsJsonArray().size() == 0,
                "gui: container result carries slots and can have zero widgets");
        JsonObject slot0 = container.get("slots").getAsJsonArray().get(0).getAsJsonObject();
        check(slot0.get("index").getAsInt() == 0 && slot0.get("container_slot").getAsInt() == 0
                        && slot0.get("x").getAsInt() == 100
                        && slot0.get("y").getAsInt() == 20
                        && "minecraft:oak_log".equals(slot0.get("item").getAsString())
                        && slot0.get("count").getAsInt() == 12 && !slot0.has("note"),
                "gui: slot entry fields (index/container_slot/x/y/role/item/count), note omitted when fine");
        JsonObject slot1 = container.get("slots").getAsJsonArray().get(1).getAsJsonObject();
        check(slot1.get("item").isJsonNull() && slot1.get("count").getAsInt() == 0,
                "gui: empty slot reports item:null count:0");
        JsonObject slot2 = container.get("slots").getAsJsonArray().get(2).getAsJsonObject();
        check(slot2.get("item").isJsonNull() && slot2.get("note").getAsString().contains("boom"),
                "gui: broken slot degrades to item:null + note");

        // --- no-screen shape
        JsonObject none = GuiContracts.noScreenResult();
        check(!none.get("screen_open").getAsBoolean() && none.keySet().size() == 1,
                "gui: no-screen result is exactly {screen_open:false}");

        // --- fallback shape: rects from partial collection, note, no widgets field
        GuiContracts.WidgetCollector partial = new GuiContracts.WidgetCollector();
        partial.add(new GuiContracts.WidgetNode("Button", 1, 2, 30, 40, true, true, "Done", "txt"));
        JsonObject fallback = GuiContracts.fallbackResult(false, "ModdedScreen", partial,
                "widget traversal failed: NPE");
        check(fallback.get("screen_open").getAsBoolean() && !fallback.get("in_game").getAsBoolean()
                        && fallback.get("fallback").getAsBoolean()
                        && "ModdedScreen".equals(fallback.get("screen_class").getAsString())
                        && fallback.get("note").getAsString().contains("traversal failed"),
                "gui: fallback result flags fallback + carries screen_class and note");
        JsonObject rect = fallback.get("rects").getAsJsonArray().get(0).getAsJsonObject();
        check("Button".equals(rect.get("type").getAsString()) && rect.get("width").getAsInt() == 30
                        && !rect.has("visible") && !rect.has("message") && !rect.has("text")
                        && !fallback.has("widgets"),
                "gui: fallback rects keep geometry only (no flags/message/text)");
        JsonObject emptyFallback = GuiContracts.fallbackResult(true, "X", new GuiContracts.WidgetCollector(), "e");
        check(emptyFallback.get("rects").getAsJsonArray().size() == 0,
                "gui: fallback with no collected nodes yields empty rects");

        // --- role strings (the generic classification vocabulary)
        java.util.Set<String> roles = java.util.Set.of(GuiContracts.ROLE_CRAFTING, GuiContracts.ROLE_RESULT,
                GuiContracts.ROLE_HOTBAR, GuiContracts.ROLE_PLAYER, GuiContracts.ROLE_ARMOR,
                GuiContracts.ROLE_OFFHAND, GuiContracts.ROLE_CONTAINER);
        check(roles.size() == 7, "gui: seven distinct role strings");
        check("crafting".equals(GuiContracts.ROLE_CRAFTING) && "result".equals(GuiContracts.ROLE_RESULT)
                        && "hotbar".equals(GuiContracts.ROLE_HOTBAR) && "player".equals(GuiContracts.ROLE_PLAYER)
                        && "armor".equals(GuiContracts.ROLE_ARMOR) && "offhand".equals(GuiContracts.ROLE_OFFHAND)
                        && "container".equals(GuiContracts.ROLE_CONTAINER),
                "gui: role string mapping (crafting/result/hotbar/player/armor/offhand/container)");

        // --- roleOf: hotbar boundary uses the CONTAINER slot index, never the
        // menu position (Slot.index is clobbered by addSlot - real-machine bug:
        // armor at menu positions 5-8 was classified hotbar)
        check(GuiContracts.roleOf(false, false, true, 0) == GuiContracts.ROLE_HOTBAR
                        && GuiContracts.roleOf(false, false, true, 8) == GuiContracts.ROLE_HOTBAR,
                "gui: roleOf container slots 0-8 are hotbar");
        check(GuiContracts.roleOf(false, false, true, 9) == GuiContracts.ROLE_PLAYER
                        && GuiContracts.roleOf(false, false, true, 35) == GuiContracts.ROLE_PLAYER,
                "gui: roleOf container slots 9-35 are player");
        check(GuiContracts.roleOf(false, false, true, 36) == GuiContracts.ROLE_ARMOR
                        && GuiContracts.roleOf(false, false, true, 39) == GuiContracts.ROLE_ARMOR,
                "gui: roleOf armor (36-39) is its own role - storage filters must exclude it");
        check(GuiContracts.roleOf(false, false, true, 40) == GuiContracts.ROLE_OFFHAND,
                "gui: roleOf offhand (40) is its own role despite menu position 45");
        check(GuiContracts.roleOf(true, true, true, 0) == GuiContracts.ROLE_CRAFTING
                        && GuiContracts.roleOf(false, true, false, 0) == GuiContracts.ROLE_RESULT,
                "gui: roleOf crafting takes precedence, then result");
        check(GuiContracts.roleOf(false, false, false, 3) == GuiContracts.ROLE_CONTAINER,
                "gui: roleOf non-player containers fall back to container");

        // --- typeName: anonymous subclasses report the first named superclass
        // (ChatScreen's EditBox is an anonymous override - getSimpleName() is "")
        check("String".equals(GuiContracts.typeName(String.class)),
                "gui: typeName returns the simple name for named classes");
        check("FakeWidget".equals(GuiContracts.typeName(new FakeWidget() {}.getClass()))
                        && "FakeWidget".equals(GuiContracts.typeName(FakeWidget.class)),
                "gui: typeName falls back to the named superclass for anonymous widgets");
    }

    // ------------------------------------------------------------------ M2-D: look + permissions

    private static void lookContracts() throws Exception {
        // --- look params: frozen schema bounds are inclusive
        LookContracts.LookParams p = validParams(() ->
                LookContracts.lookParams(json("{\"yaw\":180,\"pitch\":-90}")));
        check(p.yaw() == 180.0 && p.pitch() == -90.0,
                "look: inclusive upper/lower bounds (180 / -90) accepted");
        p = validParams(() -> LookContracts.lookParams(json("{\"yaw\":-180,\"pitch\":90}")));
        check(p.yaw() == -180.0 && p.pitch() == 90.0,
                "look: inclusive lower/upper bounds (-180 / 90) accepted");
        p = validParams(() -> LookContracts.lookParams(json("{\"yaw\":0.5,\"pitch\":-0.25}")));
        check(p.yaw() == 0.5 && p.pitch() == -0.25, "look: fractional angles accepted");
        expectInvalid(() -> LookContracts.lookParams(json("{\"yaw\":180.5,\"pitch\":0}")),
                "look: yaw 180.5 rejected");
        expectInvalid(() -> LookContracts.lookParams(json("{\"yaw\":-180.5,\"pitch\":0}")),
                "look: yaw -180.5 rejected");
        expectInvalid(() -> LookContracts.lookParams(json("{\"yaw\":0,\"pitch\":90.5}")),
                "look: pitch 90.5 rejected");
        expectInvalid(() -> LookContracts.lookParams(json("{\"yaw\":0,\"pitch\":-90.5}")),
                "look: pitch -90.5 rejected");
        expectInvalid(() -> LookContracts.lookParams(json("{\"pitch\":0}")),
                "look: missing yaw rejected");
        expectInvalid(() -> LookContracts.lookParams(json("{\"yaw\":0}")),
                "look: missing pitch rejected");
        expectInvalid(() -> LookContracts.lookParams(json("{\"yaw\":\"10\",\"pitch\":0}")),
                "look: string yaw rejected");
        expectInvalid(() -> LookContracts.lookParams(json("{\"yaw\":null,\"pitch\":0}")),
                "look: null yaw rejected");

        // --- lookAt params
        LookContracts.LookAtParams q = validParams(() ->
                LookContracts.lookAtParams(json("{\"x\":1.5,\"y\":64,\"z\":-12.25}")));
        check(q.x() == 1.5 && q.y() == 64.0 && q.z() == -12.25, "lookAt: numeric triple parsed");
        expectInvalid(() -> LookContracts.lookAtParams(json("{\"x\":1,\"y\":2}")),
                "lookAt: missing z rejected");
        expectInvalid(() -> LookContracts.lookAtParams(json("{\"x\":1,\"y\":\"2\",\"z\":3}")),
                "lookAt: non-number member rejected");
        expectInvalid(() -> LookContracts.lookAtParams(json("{}")),
                "lookAt: empty object rejected");

        // --- rotationTowards: hand-computed cases (vanilla Entity.lookAt formula).
        // yaw 0 = +Z (south), yaw -90 = +X (east), negative pitch = up - F3 axes.
        double[] south = LookContracts.rotationTowards(0, 64, 0, 0, 64, 10);
        check(south[0] == 0.0 && south[1] == 0.0 && Math.abs(south[2] - 10.0) < 1e-9,
                "look math: +Z target -> yaw 0, pitch 0, distance 10");
        double[] east = LookContracts.rotationTowards(0, 64, 0, 10, 64, 0);
        check(east[0] == -90.0 && east[1] == 0.0, "look math: +X target -> yaw -90 (east)");
        double[] north = LookContracts.rotationTowards(0, 64, 0, 0, 64, -10);
        check(north[0] == -180.0 && north[1] == 0.0, "look math: -Z target -> yaw -180 (north)");
        double[] up = LookContracts.rotationTowards(0, 64, 0, 0, 74, 0);
        check(up[1] == -90.0 && up[0] == -90.0,
                "look math: straight up -> pitch -90 (degenerate yaw -90, vanilla atan2(0,0)=0)");
        double[] down = LookContracts.rotationTowards(0, 64, 0, 0, 54, 0);
        check(down[1] == 90.0, "look math: straight down -> pitch +90");
        double[] diag = LookContracts.rotationTowards(0, 64, 0, 3, 68, 4);
        check(Math.abs(diag[0] - (-36.86989764584402)) < 1e-9
                        && Math.abs(diag[1] - (-38.65980825409009)) < 1e-9
                        && Math.abs(diag[2] - Math.sqrt(41.0)) < 1e-9,
                "look math: 3-4-5 diagonal -> yaw atan2(4,3)-90, pitch -atan2(4,5), distance sqrt(41)");
        // result yaw/pitch always inside the frozen schema bounds
        boolean bounded = true;
        for (int angle = 0; angle < 360; angle += 7) {
            double rad = Math.toRadians(angle);
            double[] r = LookContracts.rotationTowards(0, 64, 0, Math.cos(rad) * 10, Math.sin(rad) * 10,
                    Math.sin(rad) * 5);
            bounded &= r[0] >= LookContracts.YAW_MIN && r[0] <= LookContracts.YAW_MAX
                    && r[1] >= LookContracts.PITCH_MIN && r[1] <= LookContracts.PITCH_MAX;
        }
        check(bounded, "look math: results stay within schema bounds for a full circle of targets");

        // --- wrapDegrees (vanilla Mth.wrapDegrees boundary behaviour)
        check(LookContracts.wrapDegrees(190.0) == -170.0 && LookContracts.wrapDegrees(-190.0) == 170.0
                        && LookContracts.wrapDegrees(180.0) == -180.0 && LookContracts.wrapDegrees(-180.0) == -180.0
                        && LookContracts.wrapDegrees(540.0) == -180.0 && LookContracts.wrapDegrees(0.0) == 0.0,
                "look math: wrapDegrees matches vanilla (180 wraps to -180, [-180, 180) reduction)");

        // --- result shapes
        JsonObject wrappedLook = LookContracts.lookResult(-1720.28, 0, 534, 0);
        check(Math.abs(wrappedLook.getAsJsonObject("previous").get("yaw").getAsDouble() - 79.72) < 0.001
                        && wrappedLook.get("yaw").getAsDouble() == 174
                        && 530 + LookContracts.yawDelta(530, 174) == 534,
                "look: report normalized yaw while keeping an equivalent continuous landing");
        check("minecraft:iron_ore".equals(validParams(() -> DigContracts.digParams(
                        json("{\"x\":0,\"y\":64,\"z\":0,\"expected_block\":\"minecraft:iron_ore\"}"))).expectedBlock()),
                "dig: strict target accepts namespaced block ID");
        check(!validParams(() -> DigContracts.digParams(json("{\"x\":0,\"y\":64,\"z\":0}"))).autoTool(), "dig: auto_tool defaults off");
        check(validParams(() -> DigContracts.digParams(json("{\"x\":0,\"y\":64,\"z\":0,\"auto_tool\":true}"))).autoTool(), "dig: auto_tool enabled");
        expectInvalid(() -> DigContracts.digParams(json("{\"x\":0,\"y\":64,\"z\":0,\"auto_tool\":\"true\"}")), "dig: auto_tool rejects strings");
        var correctSlow = new DigContracts.ToolCandidate(1, true, 2, false, 1);
        var incorrectFast = new DigContracts.ToolCandidate(2, false, 50, false, 1);
        check(DigContracts.betterTool(correctSlow, incorrectFast, false), "auto tool: harvest tier beats speed");
        check(!DigContracts.betterTool(incorrectFast, correctSlow, false), "auto tool: no downgrade to incorrect tool");
        check(DigContracts.betterTool(new DigContracts.ToolCandidate(2, true, 12, false, 3), correctSlow, false), "auto tool: faster enchanted tool wins");
        check(DigContracts.betterTool(new DigContracts.ToolCandidate(2, true, 2, true, 3), correctSlow, true), "auto tool: silk tie preference");
        check(DigContracts.betterTool(new DigContracts.ToolCandidate(2, true, 2, false, 0), correctSlow, false), "auto tool: cheaper equal-speed tool");
        check(!DigContracts.betterTool(new DigContracts.ToolCandidate(2, true, 2, false, 1), correctSlow, false), "auto tool: stable equal candidates");
        check(validParams(() -> DigContracts.digParams(json("{\"x\":0,\"y\":64,\"z\":0}"))).safetyChecks(),
                "dig: old callers retain safety checks");
        check(!validParams(() -> DigContracts.digParams(json("{\"x\":0,\"y\":64,\"z\":0,\"safety_checks\":false}"))).safetyChecks(),
                "dig: caller can explicitly disable supplemental safety checks");
        expectInvalid(() -> DigContracts.digParams(json("{\"x\":0,\"y\":64,\"z\":0,\"safety_checks\":\"false\"}")),
                "dig: safety_checks rejects string booleans");
        JsonObject look = LookContracts.lookResult(10, -5, -90, 30);
        check(look.get("in_game").getAsBoolean() && look.get("looked").getAsBoolean()
                        && look.get("yaw").getAsDouble() == -90.0 && look.get("pitch").getAsDouble() == 30.0
                        && look.get("previous").getAsJsonObject().get("yaw").getAsDouble() == 10.0
                        && look.get("previous").getAsJsonObject().get("pitch").getAsDouble() == -5.0,
                "look results: lookResult carries new + previous rotation");
        JsonObject lookAt = LookContracts.lookAtResult(1, 2, 3, -15.5, 22.25, 7.5);
        check(lookAt.get("in_game").getAsBoolean() && lookAt.get("looked").getAsBoolean()
                        && lookAt.get("target").getAsJsonObject().get("y").getAsDouble() == 2.0
                        && lookAt.get("yaw").getAsDouble() == -15.5
                        && lookAt.get("pitch").getAsDouble() == 22.25
                        && lookAt.get("distance").getAsDouble() == 7.5,
                "look results: lookAtResult carries target, rotation and distance");
        JsonObject absent = LookContracts.notInGameLook();
        check(!absent.get("in_game").getAsBoolean() && !absent.get("looked").getAsBoolean()
                        && absent.keySet().size() == 2,
                "look results: not-in-game shape is exactly {in_game:false, looked:false}");
    }

    // ------------------------------------------------------------------ M3.5 v1.2: dig + smooth turn

    private static void digContracts() throws Exception {
        // --- dig params
        DigContracts.DigParams p = validParams(() -> DigContracts.digParams(
                json("{\"x\":11,\"y\":63,\"z\":26}")));
        check(p.x() == 11 && p.y() == 63 && p.z() == 26
                        && p.timeoutMs() == DigContracts.DEFAULT_TIMEOUT_MS,
                "dig: integer coords parsed, timeout defaults to " + DigContracts.DEFAULT_TIMEOUT_MS);
        p = validParams(() -> DigContracts.digParams(
                json("{\"x\":-5,\"y\":0,\"z\":3.0,\"timeout_ms\":600}")));
        check(p.x() == -5 && p.timeoutMs() == 600, "dig: negative/whole-float coords + min timeout accepted");
        p = validParams(() -> DigContracts.digParams(
                json("{\"x\":0,\"y\":64,\"z\":0,\"timeout_ms\":30000}")));
        check(p.timeoutMs() == 30000, "dig: max timeout boundary accepted");
        p = validParams(() -> DigContracts.digParams(
                json("{\"x\":0,\"y\":64,\"z\":0,\"timeout_ms\":null}")));
        check(p.timeoutMs() == DigContracts.DEFAULT_TIMEOUT_MS, "dig: explicit null timeout -> default");
        expectInvalid(() -> DigContracts.digParams(json("{}")), "dig: missing coords rejected");
        expectInvalid(() -> DigContracts.digParams(json("{\"x\":1,\"y\":2}")), "dig: missing z rejected");
        expectInvalid(() -> DigContracts.digParams(json("{\"x\":1.5,\"y\":2,\"z\":3}")),
                "dig: fractional coord rejected");
        expectInvalid(() -> DigContracts.digParams(json("{\"x\":\"1\",\"y\":2,\"z\":3}")),
                "dig: string coord rejected");
        expectInvalid(() -> DigContracts.digParams(json("{\"x\":1,\"y\":2,\"z\":3,\"timeout_ms\":599}")),
                "dig: timeout below min rejected");
        expectInvalid(() -> DigContracts.digParams(json("{\"x\":1,\"y\":2,\"z\":3,\"timeout_ms\":30001}")),
                "dig: timeout above max rejected");
        expectInvalid(() -> DigContracts.digParams(json("{\"x\":1,\"y\":2,\"z\":3,\"timeout_ms\":1000.5}")),
                "dig: fractional timeout rejected");

        // --- result shapes
        JsonObject broken = DigContracts.digResult(DigContracts.RESULT_BROKEN, "minecraft:oak_log",
                3210L, true, null);
        check(broken.get("in_game").getAsBoolean()
                        && "broken".equals(broken.get("result").getAsString())
                        && "minecraft:oak_log".equals(broken.get("block").getAsString())
                        && broken.get("elapsed_ms").getAsLong() == 3210L
                        && broken.get("broken_via_occluder").getAsBoolean() && !broken.has("reason"),
                "dig results: broken + via_occluder shape");
        JsonObject air = DigContracts.digResult(DigContracts.RESULT_ALREADY_AIR, null, 4L, null, null);
        check(air.get("block").isJsonNull() && !air.has("broken_via_occluder"),
                "dig results: already_air carries null block");
        JsonObject blocked = DigContracts.digResult(DigContracts.RESULT_BLOCKED_LIQUID, "minecraft:stone",
                9L, null, "liquid minecraft:water at neighbor 12,63,26 would flow in");
        check("blocked_liquid".equals(blocked.get("result").getAsString())
                        && blocked.get("reason").getAsString().contains("water"),
                "dig results: blocked_* carries the reason");

        // --- M3.6 empirical drops: aggregate new in-radius item entities
        List<ToolContracts.EntityFact> facts = Arrays.asList(
                new ToolContracts.EntityFact("d1", "Oak Log", "minecraft:item",
                        6.5, 64.5, 0.5, Float.NaN, "minecraft:oak_log", 1),
                new ToolContracts.EntityFact("d2", "Oak Log", "minecraft:item",
                        7.5, 64.5, 0.5, Float.NaN, "minecraft:oak_log", 2),
                new ToolContracts.EntityFact("foreign", "Oak Log", "minecraft:item",
                        6.5, 64.5, 1.5, Float.NaN, "minecraft:oak_log", 1),
                new ToolContracts.EntityFact("far", "Oak Log", "minecraft:item",
                        20.0, 64.0, 0.0, Float.NaN, "minecraft:oak_log", 1),
                new ToolContracts.EntityFact("z1", "Zombie", "minecraft:zombie",
                        6.0, 64.0, 0.0, 20.0F));
        java.util.Set<String> seenBefore = java.util.Set.of("foreign");
        List<JsonObject> drops = DigContracts.aggregateDrops(facts, seenBefore, 6.5, 64.5, 0.5,
                DigContracts.DROPS_SCAN_RADIUS);
        check(drops.size() == 1
                        && "minecraft:oak_log".equals(drops.get(0).get("item").getAsString())
                        && drops.get(0).get("count").getAsInt() == 3,
                "dig drops: new in-radius item entities summed per id (pre-existing/far/non-item excluded)");
        check(DigContracts.aggregateDrops(facts,
                        java.util.Set.of("d1", "d2", "foreign", "far"), 6.5, 64.5, 0.5, 4.0).isEmpty(),
                "dig drops: fully-snapshotted scene -> empty report (nothing new appeared)");
        JsonObject withDrops = DigContracts.digResult(DigContracts.RESULT_BROKEN, "minecraft:oak_log",
                3210L, null, null, drops);
        check(withDrops.get("drops").isJsonArray()
                        && withDrops.get("drops").getAsJsonArray().size() == 1
                        && withDrops.get("drops").getAsJsonArray().get(0).getAsJsonObject()
                        .get("count").getAsInt() == 3,
                "dig results: broken carries the drops [{item,count}] array");
        check(!DigContracts.digResult(DigContracts.RESULT_ALREADY_AIR, null, 4L, null, null).has("drops")
                        && !DigContracts.digResult(DigContracts.RESULT_BROKEN, "minecraft:oak_log",
                        100L, null, null, (List<JsonObject>) null).has("drops"),
                "dig results: null drops omitted (old-jar response shape intact)");
        check(DigContracts.DROPS_WAIT_TICKS >= 10 && DigContracts.DROPS_WAIT_TICKS <= 20
                        && DigContracts.DROPS_SCAN_RADIUS == 4.0,
                "dig drops: wait window bounded to <= 1 s, scan radius 4 (RPC latency budget)");

        // --- monitor: aim -> press -> break (the happy path)
        DigContracts.DigMonitor m = new DigContracts.DigMonitor(15000, false);
        DigContracts.TickView aiming = new DigContracts.TickView(false, false, false, false, 0);
        DigContracts.TickView aimed = new DigContracts.TickView(true, false, false, false, 0);
        DigContracts.TickView pressing = new DigContracts.TickView(true, false, false, true, 100);
        check(m.tick(aiming).action() == DigContracts.Action.WAIT, "dig monitor: waits while aiming");
        DigContracts.Verdict press = m.tick(aimed);
        check(press.action() == DigContracts.Action.PRESS, "dig monitor: presses once aim converges");
        DigContracts.Verdict hold = m.tick(pressing);
        check(hold.action() == DigContracts.Action.HOLD, "dig monitor: holds while destroying");
        DigContracts.Verdict done = m.tick(new DigContracts.TickView(true, true, false, false, 3000));
        check(done.action() == DigContracts.Action.FINISH
                        && DigContracts.RESULT_BROKEN.equals(done.result()) && !done.viaOccluder(),
                "dig monitor: block change -> FINISH broken");

        // --- monitor: target already gone during aiming -> idempotent broken
        m = new DigContracts.DigMonitor(15000, false);
        done = m.tick(new DigContracts.TickView(false, true, false, false, 50));
        check(done.action() == DigContracts.Action.FINISH
                        && DigContracts.RESULT_BROKEN.equals(done.result()),
                "dig monitor: target gone while aiming -> broken (someone else broke it)");

        // --- monitor: occluder tolerance + broken_via_occluder (the T5a leaves-in-front case)
        m = new DigContracts.DigMonitor(15000, false);
        m.tick(aimed); // PRESS
        boolean held = true;
        for (int i = 0; i < 80; i++) { // 4s of chewing a leaf occluder, stage active, hit NOT on target
            DigContracts.Verdict v = m.tick(new DigContracts.TickView(true, false, true, true, 100 + i * 50));
            held &= v.action() == DigContracts.Action.HOLD;
        }
        DigContracts.Verdict through = m.tick(new DigContracts.TickView(true, true, true, false, 4200));
        check(held, "dig monitor: occluder hit NEVER fails the dig (hysteresis)");
        check(through.action() == DigContracts.Action.FINISH
                        && DigContracts.RESULT_BROKEN.equals(through.result()) && through.viaOccluder(),
                "dig monitor: broken after occluder -> via_occluder=true");
        // hit jitter during AIMING must NOT count as an occluder (crosshair sweeps)
        DigContracts.DigMonitor aimOnly = new DigContracts.DigMonitor(15000, false);
        aimOnly.tick(new DigContracts.TickView(false, false, true, false, 0)); // WAIT (aiming, occluder hit)
        DigContracts.Verdict noOccluder = aimOnly.tick(new DigContracts.TickView(true, true, false, false, 100));
        check(!noOccluder.viaOccluder(),
                "dig monitor: occluder hit during AIMING is not tracked as via_occluder");

        // --- monitor: aim stall guard (view never lands -> not_digging, not a bare timeout)
        m = new DigContracts.DigMonitor(15000, false);
        DigContracts.Verdict stalled = null;
        for (int i = 0; i < DigContracts.AIM_STALL_TICKS; i++) {
            stalled = m.tick(new DigContracts.TickView(false, false, false, false, i * 50));
        }
        check(stalled.action() == DigContracts.Action.FINISH
                        && DigContracts.RESULT_NOT_DIGGING.equals(stalled.result()),
                "dig monitor: aim stall for " + DigContracts.AIM_STALL_TICKS + " ticks -> not_digging (fail fast)");

        // --- monitor: not_digging telemetry early-stop (stage -1 for 40 pressing ticks)
        m = new DigContracts.DigMonitor(15000, false);
        m.tick(aimed); // PRESS
        DigContracts.Verdict last = null;
        for (int i = 1; i <= DigContracts.NOT_DIGGING_TICKS; i++) {
            last = m.tick(new DigContracts.TickView(true, false, false, false, i * 50));
        }
        check(last.action() == DigContracts.Action.FINISH
                        && DigContracts.RESULT_NOT_DIGGING.equals(last.result()),
                "dig monitor: 40 consecutive idle-stage ticks -> not_digging");
        // stage flicker resets the streak
        m = new DigContracts.DigMonitor(15000, false);
        m.tick(aimed);
        DigContracts.Action a39 = null;
        for (int i = 1; i <= 39; i++) {
            a39 = m.tick(new DigContracts.TickView(true, false, false, i % 5 == 0, i * 50)).action();
        }
        check(a39 == DigContracts.Action.HOLD,
                "dig monitor: intermittent stage activity resets the not-digging streak");

        // --- monitor: timeout (also while aiming)
        m = new DigContracts.DigMonitor(15000, false);
        DigContracts.Verdict to = m.tick(new DigContracts.TickView(false, false, false, false, 15000));
        check(to.action() == DigContracts.Action.FINISH
                        && DigContracts.RESULT_TIMEOUT.equals(to.result()),
                "dig monitor: elapsed >= timeout_ms -> timeout");
        m = new DigContracts.DigMonitor(15000, false);
        m.tick(aimed);
        to = m.tick(new DigContracts.TickView(true, false, false, true, 15100));
        check(to.action() == DigContracts.Action.FINISH
                        && DigContracts.RESULT_TIMEOUT.equals(to.result()),
                "dig monitor: timeout outranks an active stage");

        // --- monitor: insta-mine short press (protects the block behind)
        m = new DigContracts.DigMonitor(15000, true);
        m.tick(aimed); // PRESS
        boolean shortHold = true;
        for (int i = 1; i < DigContracts.INSTA_PRESS_TICKS; i++) {
            shortHold &= m.tick(new DigContracts.TickView(true, false, false, true, i * 50)).action()
                    == DigContracts.Action.HOLD;
        }
        DigContracts.Verdict unpress = m.tick(new DigContracts.TickView(true, false, false, true, 150));
        check(shortHold && unpress.action() == DigContracts.Action.UNPRESS,
                "dig monitor: insta-mine holds " + DigContracts.INSTA_PRESS_TICKS + " ticks then unpresses");
        DigContracts.Verdict instaBroken = m.tick(new DigContracts.TickView(true, true, false, false, 250));
        check(instaBroken.action() == DigContracts.Action.FINISH
                        && DigContracts.RESULT_BROKEN.equals(instaBroken.result()),
                "dig monitor: insta-mine break lands during verify -> broken");
        // ...and when the insta press misses: bounded retries then not_digging
        m = new DigContracts.DigMonitor(15000, true);
        int presses = 0;
        DigContracts.Verdict v = null;
        for (int i = 0; i < 200 && (v == null || v.action() != DigContracts.Action.FINISH); i++) {
            v = m.tick(new DigContracts.TickView(true, false, false, false, i * 50 + 100));
            if (v.action() == DigContracts.Action.PRESS) {
                presses++;
            }
        }
        check(v.action() == DigContracts.Action.FINISH
                        && DigContracts.RESULT_NOT_DIGGING.equals(v.result())
                        && presses == DigContracts.INSTA_MAX_PRESSES,
                "dig monitor: failed insta presses bounded at " + DigContracts.INSTA_MAX_PRESSES
                        + " then not_digging");

        // --- smooth-turn math (v1.2)
        check(LookContracts.yawDelta(0, 180) == -180.0 && LookContracts.yawDelta(0, -180) == -180.0,
                "turn math: exactly-180 deltas reduce to -180 (vanilla [-180,180) wrap; turns left)");
        check(LookContracts.yawDelta(0, 190) == -170.0 && LookContracts.yawDelta(350, 10) == 20.0,
                "turn math: yawDelta wraps the short way round");
        check(LookContracts.approach(10, 5, 15) == 15.0 && LookContracts.approach(10, -5, 15) == 5.0,
                "turn math: approach clamps to maxStep");
        check(LookContracts.approach(10, 3, 15) == 13.0 && LookContracts.approach(10, -20, 15) == -5.0,
                "turn math: approach lands exactly when within one step");
        check(LookContracts.turnConverged(-0.5, 10.9, 0, 10)
                        && !LookContracts.turnConverged(1.5, 10, 0, 10)
                        && !LookContracts.turnConverged(0, 11.5, 0, 10),
                "turn math: convergence window is <1 deg on both axes");
        check(LookContracts.maxTurnMs(300) == 2700L && LookContracts.maxTurnMs(30) == 13500L,
                "turn math: maxTurnMs = 360deg/speed + 1.5s slack");
        check(DigContracts.DIG_AIM_TURN_SPEED_DEG_S == 300.0,
                "turn math: dig aims at 300 deg/s (the T6 ruling)");

        // --- lookAt turn_speed_deg_s params (v1.2)
        LookContracts.LookAtParams q = validParams(() -> LookContracts.lookAtParams(
                json("{\"x\":1,\"y\":2,\"z\":3}")));
        check(q.turnSpeedDegS() == null, "lookAt: absent turn_speed -> instant (v1.0 behaviour)");
        q = validParams(() -> LookContracts.lookAtParams(
                json("{\"x\":1,\"y\":2,\"z\":3,\"turn_speed_deg_s\":null}")));
        check(q.turnSpeedDegS() == null, "lookAt: explicit null turn_speed -> instant");
        q = validParams(() -> LookContracts.lookAtParams(
                json("{\"x\":1,\"y\":2,\"z\":3,\"turn_speed_deg_s\":300}")));
        check(q.turnSpeedDegS() == 300.0, "lookAt: turn_speed 300 parsed");
        q = validParams(() -> LookContracts.lookAtParams(
                json("{\"x\":1,\"y\":2,\"z\":3,\"turn_speed_deg_s\":30}")));
        check(q.turnSpeedDegS() == 30.0, "lookAt: turn_speed min boundary 30 accepted");
        q = validParams(() -> LookContracts.lookAtParams(
                json("{\"x\":1,\"y\":2,\"z\":3,\"turn_speed_deg_s\":720}")));
        check(q.turnSpeedDegS() == 720.0, "lookAt: turn_speed max boundary 720 accepted");
        q = validParams(() -> LookContracts.lookAtParams(
                json("{\"x\":1,\"y\":2,\"z\":3,\"turn_speed_deg_s\":180.5}")));
        check(q.turnSpeedDegS() == 180.5, "lookAt: fractional turn_speed accepted (number, not integer)");
        expectInvalid(() -> LookContracts.lookAtParams(
                json("{\"x\":1,\"y\":2,\"z\":3,\"turn_speed_deg_s\":29.9}")),
                "lookAt: turn_speed below 30 rejected");
        expectInvalid(() -> LookContracts.lookAtParams(
                json("{\"x\":1,\"y\":2,\"z\":3,\"turn_speed_deg_s\":721}")),
                "lookAt: turn_speed above 720 rejected");
        expectInvalid(() -> LookContracts.lookAtParams(
                json("{\"x\":1,\"y\":2,\"z\":3,\"turn_speed_deg_s\":\"300\"}")),
                "lookAt: string turn_speed rejected");

        // --- smooth lookAt result shape
        JsonObject smooth = LookContracts.lookAtSmoothResult(1, 2, 3, -15.5, 22.25, 7.5, true, 620L);
        check(smooth.get("converged").getAsBoolean() && smooth.get("elapsed_ms").getAsLong() == 620L
                        && smooth.get("distance").getAsDouble() == 7.5,
                "lookAt results: smooth result carries converged + elapsed_ms");

        // --- PermissionContracts re-check for dig's class: dig rides Action.INPUT
        // (the input_world tier allows it without a screen - same as input.*;
        // full matrix covered by permissionContracts() below)
        check(PermissionContracts.allows(PermissionContracts.Tier.INPUT_WORLD, false,
                        PermissionContracts.Action.INPUT)
                        && PermissionContracts.deniedRegardlessOfScreen(
                        PermissionContracts.Tier.INPUT_GUI, PermissionContracts.Action.LOOK),
                "dig tier: dig rides the INPUT action class (input_world: yes without a screen)");
    }

    private static void permissionContracts() {
        PermissionContracts.Tier OBSERVE = PermissionContracts.Tier.OBSERVE;
        PermissionContracts.Tier INPUT_GUI = PermissionContracts.Tier.INPUT_GUI;
        PermissionContracts.Tier INPUT_WORLD = PermissionContracts.Tier.INPUT_WORLD;
        PermissionContracts.Tier FULL = PermissionContracts.Tier.FULL;
        PermissionContracts.Action INPUT = PermissionContracts.Action.INPUT;
        PermissionContracts.Action LOOK = PermissionContracts.Action.LOOK;

        // --- the decision matrix (tier x screen x action)
        check(!PermissionContracts.allows(OBSERVE, true, INPUT)
                        && !PermissionContracts.allows(OBSERVE, false, INPUT)
                        && !PermissionContracts.allows(OBSERVE, true, LOOK)
                        && !PermissionContracts.allows(OBSERVE, false, LOOK),
                "permissions: observe denies input.* and look regardless of screen");
        check(PermissionContracts.allows(INPUT_GUI, true, INPUT)
                        && !PermissionContracts.allows(INPUT_GUI, false, INPUT)
                        && !PermissionContracts.allows(INPUT_GUI, true, LOOK)
                        && !PermissionContracts.allows(INPUT_GUI, false, LOOK),
                "permissions: input_gui allows input.* ONLY with a screen, never look");
        check(!PermissionContracts.allows(INPUT_WORLD, true, INPUT)
                        && PermissionContracts.allows(INPUT_WORLD, false, INPUT)
                        && PermissionContracts.allows(INPUT_WORLD, true, LOOK)
                        && PermissionContracts.allows(INPUT_WORLD, false, LOOK),
                "permissions: input_world allows input.* ONLY without a screen, always look");
        check(PermissionContracts.allows(FULL, true, INPUT) && PermissionContracts.allows(FULL, false, INPUT)
                        && PermissionContracts.allows(FULL, true, LOOK) && PermissionContracts.allows(FULL, false, LOOK),
                "permissions: full allows everything (default = M2-A behaviour)");

        // --- M4.1 chat.send rides the CHAT action: GUI-immune, only observe denies
        PermissionContracts.Action CHAT = PermissionContracts.Action.CHAT;
        check(!PermissionContracts.allows(OBSERVE, true, CHAT)
                        && !PermissionContracts.allows(OBSERVE, false, CHAT),
                "permissions: observe denies chat.send (read-only tier)");
        check(PermissionContracts.allows(INPUT_GUI, true, CHAT)
                        && PermissionContracts.allows(INPUT_GUI, false, CHAT)
                        && PermissionContracts.allows(INPUT_WORLD, true, CHAT)
                        && PermissionContracts.allows(INPUT_WORLD, false, CHAT)
                        && PermissionContracts.deniedRegardlessOfScreen(OBSERVE, CHAT)
                        && !PermissionContracts.deniedRegardlessOfScreen(INPUT_GUI, CHAT),
                "permissions: chat.send is screen-immune under every acting tier (M4.1 death-screen path)");

        // --- pre-flight veto helper (callers deny without a main-thread round trip)
        check(PermissionContracts.deniedRegardlessOfScreen(OBSERVE, INPUT)
                        && PermissionContracts.deniedRegardlessOfScreen(OBSERVE, LOOK)
                        && PermissionContracts.deniedRegardlessOfScreen(INPUT_GUI, LOOK),
                "permissions: screen-independent vetoes (observe any, input_gui look)");
        check(!PermissionContracts.deniedRegardlessOfScreen(INPUT_GUI, INPUT)
                        && !PermissionContracts.deniedRegardlessOfScreen(INPUT_WORLD, INPUT)
                        && !PermissionContracts.deniedRegardlessOfScreen(INPUT_WORLD, LOOK)
                        && !PermissionContracts.deniedRegardlessOfScreen(FULL, INPUT)
                        && !PermissionContracts.deniedRegardlessOfScreen(FULL, LOOK),
                "permissions: gui-gated combos need the live screen state");

        // --- parse: case-insensitive + trimmed; unknown -> null (config falls back)
        check(PermissionContracts.parse("observe") == OBSERVE
                        && PermissionContracts.parse("INPUT_GUI") == INPUT_GUI
                        && PermissionContracts.parse("  input_world ") == INPUT_WORLD
                        && PermissionContracts.parse("full") == FULL,
                "permissions: tier names parse case-insensitively (whitespace trimmed)");
        check(PermissionContracts.parse("bogus") == null && PermissionContracts.parse("") == null
                        && PermissionContracts.parse(null) == null,
                "permissions: unknown/empty/null tier names rejected");

        // --- message + config spelling
        check("input_gui".equals(INPUT_GUI.configName()) && "observe".equals(OBSERVE.configName()),
                "permissions: config spelling is lowercase");
        String message = PermissionContracts.deniedMessage(OBSERVE, false, INPUT);
        check(message.startsWith("permission_denied")
                        && message.contains("observe")
                        && message.contains("sirius_bridge.toml"),
                "permissions: deniedMessage names the tier and the config key");
    }

    private static void chatContracts() throws Exception {
        // --- validation: required, 1..256 code points
        expectInvalid(() -> ChatContracts.chatSendParams(json("{}")),
                "chat.send: missing string rejected");
        expectInvalid(() -> ChatContracts.chatSendParams(json("{\"string\": \"\"}")),
                "chat.send: empty string rejected");
        expectInvalid(() -> ChatContracts.chatSendParams(json("{\"string\": 42}")),
                "chat.send: non-string rejected");
        expectInvalid(() -> ChatContracts.chatSendParams(
                json("{\"string\": \"" + "x".repeat(257) + "\"}")),
                "chat.send: 257 chars rejected (vanilla limit)");
        ChatContracts.ChatSendParams ok = ChatContracts.chatSendParams(
                json("{\"string\": \"我死了……\"}"));
        check(ok.text().equals("我死了……"),
                "chat.send: CJK text passes validation verbatim");

        // --- result shapes
        JsonObject notInGame = ChatContracts.notInGameChat();
        check(!notInGame.get("in_game").getAsBoolean() && !notInGame.get("sent").getAsBoolean(),
                "chat.send: not-in-game result is not an error");
        JsonObject sent = ChatContracts.sentResult("hello");
        check(sent.get("in_game").getAsBoolean() && sent.get("sent").getAsBoolean()
                        && sent.get("length").getAsInt() == 5,
                "chat.send: sent result carries in_game/sent/length");
    }

    private static void movementLook() {
        check(!MovementLook.shouldApply(true, false)
                        && !MovementLook.shouldApply(false, true)
                        && MovementLook.shouldApply(true, true),
                "movement look: only enabled Baritone navigation may steer the view");

        // --- stationary / vertical-only motion: stay silent
        check(MovementLook.nextYaw(90.0, 0.0, 0.0, 300.0) == null
                        && MovementLook.nextYaw(90.0, 0.0, 0.0, 300.0) == null,
                "movement look: no horizontal speed -> no write");
        check(MovementLook.nextYaw(90.0, 0.0, 0.03, 300.0) == null,
                "movement look: sub-threshold drift (fall wobble) -> no write");

        // --- heading math: yaw 0 = +Z (south); moving +Z targets yaw 0,
        //     so from current yaw 90 one 300deg/s tick steps -15 (shortest way)
        Double north = MovementLook.nextYaw(90.0, 0.0, 0.215, 300.0);
        check(north != null && Math.abs(north - 75.0) < 1e-9,
                "movement look: one 50ms tick steps 15 deg toward the heading");
        Double east = MovementLook.nextYaw(0.0, 0.215, 0.0, 300.0);
        check(east != null && Math.abs(east - (-15.0)) < 1e-9,
                "movement look: moving +X (east, yaw -90) turns the short way negative");

        // --- deadzone: already facing the movement direction
        check(MovementLook.nextYaw(0.0, 0.0, 0.215, 300.0) == null,
                "movement look: within deadzone of the heading -> no write");

        // --- wrap-around takes the short way: current 170, moving toward
        //     heading -170 (velocity = the -170 view direction) -> delta +20
        //     -> one step lands at 185 (unwrapped; vanilla normalizes on use)
        Double wrap = MovementLook.nextYaw(170.0, 0.1736, -0.9848, 300.0);
        check(wrap != null && Math.abs(wrap - 185.0) < 1e-9,
                "movement look: wraps across +-180 along the shortest signed path");

        // --- step clamps at the fixed angular speed, never overshoots a near target
        Double small = MovementLook.nextYaw(5.0, 0.0, 0.06, 30.0);
        check(small != null && Math.abs(small - 3.5) < 1e-9,
                "movement look: 30deg/s tick (1.5 deg) never overshoots a 5-deg correction");

        // --- M4.2 pitch leveling: same movement gate, target always 0
        check(MovementLook.nextPitch(-40.0, 0.0, 0.0, 150.0) == null,
                "pitch level: no horizontal speed -> no write (standing keeps its aim)");
        Double canopy = MovementLook.nextPitch(-40.0, 0.0, 0.215, 150.0);
        check(canopy != null && Math.abs(canopy - (-32.5)) < 1e-9,
                "pitch level: walking with a -40 canopy leftover steps +7.5 deg (150deg/s tick)");
        Double floor = MovementLook.nextPitch(40.0, 0.215, 0.0, 150.0);
        check(floor != null && Math.abs(floor - 32.5) < 1e-9,
                "pitch level: walking with a +40 underfoot leftover steps -7.5 deg toward 0");
        check(MovementLook.nextPitch(0.8, 0.0, 0.215, 150.0) == null
                        && MovementLook.nextPitch(-1.0, 0.0, 0.215, 150.0) == null,
                "pitch level: within the 1 deg deadzone of level -> no write");
        Double settle = MovementLook.nextPitch(3.0, 0.0, 0.06, 30.0);
        check(settle != null && Math.abs(settle - 1.5) < 1e-9,
                "pitch level: 30deg/s tick never overshoots a 3-deg leftover");
        // convergence bound: worst-case dig leftover levels inside ~6 ticks
        double p = -40.0;
        int ticks = 0;
        while (Math.abs(p) > 2.0 && ticks < 100) {
            Double next = MovementLook.nextPitch(p, 0.0, 0.215, 150.0);
            if (next == null) {
                break; // deadzone reached
            }
            p = next;
            ticks++;
        }
        check(Math.abs(p) <= 2.0 && ticks <= 6,
                "pitch level: iterating from a -40 leftover converges into |pitch|<=2 deg within 6 ticks");
    }

    // ------------------------------------------------------------------ M4.4 v1.4 place

    private static void placeContracts() throws Exception {
        // --- params: required coords + defaults
        PlaceContracts.PlaceParams p = PlaceContracts.placeParams(
                json("{\"x\":10,\"y\":65,\"z\":-3}"));
        check(p.x() == 10 && p.y() == 65 && p.z() == -3
                        && p.face() == PlaceContracts.Face.DOWN && p.item() == null
                        && p.timeoutMs() == PlaceContracts.DEFAULT_TIMEOUT_MS,
                "place: coords parsed, face defaults to down, item null, timeout 3000");
        check(PlaceContracts.DEFAULT_TIMEOUT_MS == 3000
                        && PlaceContracts.MIN_TIMEOUT_MS == 600
                        && PlaceContracts.MAX_TIMEOUT_MS == 10000
                        && PlaceContracts.REACH == 4.5
                        && PlaceContracts.AIM_TURN_SPEED_DEG_S == 300.0,
                "place: frozen constants (timeout 600..10000/default 3000, reach 4.5, aim 300deg/s)");
        check(PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"face\":null}"))
                        .face() == PlaceContracts.Face.DOWN,
                "place: explicit null face -> default down");
        check(PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"face\":\"up\"}"))
                        .face() == PlaceContracts.Face.UP,
                "place: face \"up\" parsed");
        for (PlaceContracts.Face face : PlaceContracts.Face.values()) {
            check(PlaceContracts.Face.parse(face.wireName()) == face
                            && PlaceContracts.Face.parse(face.wireName().toUpperCase()) == null,
                    "place: face name round-trip (case-sensitive) " + face.wireName());
        }
        expectInvalid(() -> PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"face\":\"sideways\"}")),
                "place: unknown face rejected (lists the six names)");
        expectInvalid(() -> PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"face\":1}")),
                "place: non-string face rejected");
        expectInvalid(() -> PlaceContracts.placeParams(json("{\"y\":65,\"z\":-3}")),
                "place: missing x rejected");
        expectInvalid(() -> PlaceContracts.placeParams(json("{\"x\":1,\"y\":2}")),
                "place: missing z rejected");
        expectInvalid(() -> PlaceContracts.placeParams(json("{\"x\":1.5,\"y\":2,\"z\":3}")),
                "place: fractional coord rejected");
        expectInvalid(() -> PlaceContracts.placeParams(json("{\"x\":\"1\",\"y\":2,\"z\":3}")),
                "place: string coord rejected");

        // --- timeout_ms bounds
        check(PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"timeout_ms\":null}"))
                        .timeoutMs() == PlaceContracts.DEFAULT_TIMEOUT_MS,
                "place: explicit null timeout -> default");
        check(PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"timeout_ms\":600}")).timeoutMs() == 600
                        && PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"timeout_ms\":10000}"))
                        .timeoutMs() == 10000,
                "place: timeout boundaries 600/10000 accepted");
        expectInvalid(() -> PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"timeout_ms\":599}")),
                "place: timeout below min rejected");
        expectInvalid(() -> PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"timeout_ms\":10001}")),
                "place: timeout above max rejected");
        expectInvalid(() -> PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"timeout_ms\":1500.5}")),
                "place: fractional timeout rejected");

        // --- item normalization (bare name -> minecraft:; tags refused)
        check("minecraft:oak_planks".equals(PlaceContracts.placeParams(
                        json("{\"x\":1,\"y\":2,\"z\":3,\"item\":\"oak_planks\"}")).item())
                        && "minecraft:oak_planks".equals(PlaceContracts.placeParams(
                        json("{\"x\":1,\"y\":2,\"z\":3,\"item\":\"minecraft:oak_planks\"}")).item())
                        && "mymod:thing".equals(PlaceContracts.placeParams(
                        json("{\"x\":1,\"y\":2,\"z\":3,\"item\":\"mymod:thing\"}")).item()),
                "place: item bare name gets the minecraft: prefix, explicit namespaces kept");
        check(PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"item\":null}")).item() == null,
                "place: explicit null item -> current hand");
        expectInvalid(() -> PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"item\":\"#minecraft:planks\"}")),
                "place: #tag item rejected (a place needs one concrete item)");
        expectInvalid(() -> PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"item\":\"Oak_Planks\"}")),
                "place: uppercase item id rejected");
        expectInvalid(() -> PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"item\":\"\"}")),
                "place: empty item id rejected");
        expectInvalid(() -> PlaceContracts.placeParams(json("{\"x\":1,\"y\":2,\"z\":3,\"item\":7}")),
                "place: non-string item rejected");

        // --- geometry: support = target.relative(face), hit point = support centre
        //     pushed 0.5 along face.getOpposite() (hand-computed cases)
        PlaceContracts.Cell target = new PlaceContracts.Cell(10, 65, -3);
        PlaceContracts.Cell supportDown = PlaceContracts.supportOf(target, PlaceContracts.Face.DOWN);
        PlaceContracts.Hit hitDown = PlaceContracts.hitPoint(supportDown, PlaceContracts.Face.UP);
        check(supportDown.equals(new PlaceContracts.Cell(10, 64, -3))
                        && hitDown.equals(new PlaceContracts.Hit(10.5, 65.0, -2.5)),
                "place geometry: face=down -> support below, click its UP face at y=65.0");
        PlaceContracts.Cell supportNorth = PlaceContracts.supportOf(target, PlaceContracts.Face.NORTH);
        PlaceContracts.Hit hitNorth = PlaceContracts.hitPoint(supportNorth, PlaceContracts.Face.SOUTH);
        check(supportNorth.equals(new PlaceContracts.Cell(10, 65, -4))
                        && hitNorth.equals(new PlaceContracts.Hit(10.5, 65.5, -3.0)),
                "place geometry: face=north -> support at z-1, click its SOUTH face at z=-3.0");
        PlaceContracts.Cell supportEast = PlaceContracts.supportOf(target, PlaceContracts.Face.EAST);
        PlaceContracts.Hit hitEast = PlaceContracts.hitPoint(supportEast, PlaceContracts.Face.WEST);
        check(supportEast.equals(new PlaceContracts.Cell(11, 65, -3))
                        && hitEast.equals(new PlaceContracts.Hit(11.0, 65.5, -2.5)),
                "place geometry: face=east -> support at x+1, click its WEST face at x=11.0");
        PlaceContracts.Hit hitUp = PlaceContracts.hitPoint(
                PlaceContracts.supportOf(new PlaceContracts.Cell(0, 0, 0), PlaceContracts.Face.UP),
                PlaceContracts.Face.DOWN);
        check(hitUp.equals(new PlaceContracts.Hit(0.5, 1.0, 0.5)),
                "place geometry: face=up -> support above, click its DOWN face at y=1.0");
        boolean aimMatches = true;
        for (PlaceContracts.Face face : PlaceContracts.Face.values()) {
            aimMatches &= PlaceContracts.aimPoint(target, face).equals(
                    PlaceContracts.hitPoint(PlaceContracts.supportOf(target, face), face.opposite()));
        }
        check(aimMatches, "place geometry: aimPoint == hitPoint(support, face.getOpposite()) for all six faces");
        check(PlaceContracts.Face.UP.opposite() == PlaceContracts.Face.DOWN
                        && PlaceContracts.Face.NORTH.opposite() == PlaceContracts.Face.SOUTH
                        && PlaceContracts.Face.EAST.opposite() == PlaceContracts.Face.WEST,
                "place geometry: opposite() pairs the six faces");
        String reachMessage = PlaceContracts.outOfReachMessage(target, PlaceContracts.Face.DOWN, "5.2");
        check(reachMessage.contains("4.5") && reachMessage.contains("Walk closer"),
                "place: out-of-reach message teaches the 4.5-block reach");

        // --- result vocabulary (frozen: the brain maps each to a teaching phrase)
        List<String> vocabulary = Arrays.asList(
                PlaceContracts.RESULT_PLACED, PlaceContracts.RESULT_ALREADY_PLACED,
                PlaceContracts.RESULT_NO_ITEM, PlaceContracts.RESULT_NOT_IN_HOTBAR,
                PlaceContracts.RESULT_BLOCKED, PlaceContracts.RESULT_SUPPORT_MISSING,
                PlaceContracts.RESULT_OUT_OF_REACH, PlaceContracts.RESULT_GUI_OPEN,
                PlaceContracts.RESULT_REJECTED_BY_SERVER, PlaceContracts.RESULT_TIMEOUT,
                PlaceContracts.RESULT_NOT_PLACING);
        check(vocabulary.size() == 11 && new java.util.HashSet<>(vocabulary).size() == 11
                        && vocabulary.containsAll(Arrays.asList("placed", "already_placed", "no_item",
                        "not_in_hotbar", "blocked", "support_missing", "out_of_reach", "gui_open",
                        "rejected_by_server", "timeout", "not_placing")),
                "place: 11 distinct result strings, all spelled as the schema documents them");
        check(PlaceContracts.RESULT_NOT_PLACING.equals(PlaceContracts.timeoutVerdict(true))
                        && PlaceContracts.RESULT_TIMEOUT.equals(PlaceContracts.timeoutVerdict(false)),
                "place: timeout verdict splits into not_placing (still replaceable) / timeout (filled)");

        // --- response shape
        JsonObject placed = PlaceContracts.placeResult(p, PlaceContracts.RESULT_PLACED,
                "minecraft:oak_planks", 180L, 3, null);
        check(placed.get("in_game").getAsBoolean()
                        && "placed".equals(placed.get("result").getAsString())
                        && "minecraft:oak_planks".equals(placed.get("block").getAsString())
                        && placed.getAsJsonObject("at").get("x").getAsInt() == 10
                        && placed.getAsJsonObject("at").get("y").getAsInt() == 65
                        && placed.getAsJsonObject("at").get("z").getAsInt() == -3
                        && "down".equals(placed.get("face").getAsString())
                        && placed.getAsJsonObject("support").get("y").getAsInt() == 64
                        && placed.get("elapsed_ms").getAsLong() == 180L
                        && placed.get("slot").getAsInt() == 3
                        && !placed.has("reason"),
                "place results: placed carries at/face/support/elapsed_ms/slot");
        JsonObject noItem = PlaceContracts.placeResult(p, PlaceContracts.RESULT_NO_ITEM, null, 2L, null, null);
        check(noItem.get("block").isJsonNull() && !noItem.has("slot") && !noItem.has("reason"),
                "place results: unknown block -> JSON null, slot omitted when unused");
        JsonObject blocked = PlaceContracts.placeResult(p, PlaceContracts.RESULT_BLOCKED,
                "minecraft:chest", 3L, null, "minecraft:chest at 10,65,-3 is not replaceable");
        check("blocked".equals(blocked.get("result").getAsString())
                        && blocked.get("reason").getAsString().contains("chest"),
                "place results: blocked carries the reason");

        // --- place rides the INPUT action class (observe vetoes it pre-flight)
        check(PermissionContracts.deniedRegardlessOfScreen(
                        PermissionContracts.Tier.OBSERVE, PermissionContracts.Action.INPUT)
                        && PermissionContracts.allows(PermissionContracts.Tier.INPUT_WORLD, false,
                        PermissionContracts.Action.INPUT),
                "place tier: rides the INPUT action class (observe denied, input_world allowed without a screen)");
    }

    // ------------------------------------------------------------------ M4.4 v1.4 inventory

    private static void inventoryContracts() throws Exception {
        // --- scope parsing
        InventoryContracts.InventoryParams ip = InventoryContracts.inventoryParams(json("{}"));
        check("all".equals(ip.scope()) && ip.filter() == null,
                "inventory: scope defaults to all, filter null");
        check("all".equals(InventoryContracts.inventoryParams(json("{\"scope\":null}")).scope()),
                "inventory: explicit null scope -> all");
        for (String scope : Arrays.asList("hotbar", "main", "armor", "offhand")) {
            check(scope.equals(InventoryContracts.inventoryParams(
                            json("{\"scope\":\"" + scope + "\"}")).scope()),
                    "inventory: scope \"" + scope + "\" accepted");
        }
        expectInvalid(() -> InventoryContracts.inventoryParams(json("{\"scope\":\"chest\"}")),
                "inventory: unknown scope rejected");
        expectInvalid(() -> InventoryContracts.inventoryParams(json("{\"scope\":1}")),
                "inventory: non-string scope rejected");

        // --- filter normalization (bare names + #tags)
        InventoryContracts.Filter filter = InventoryContracts.inventoryParams(
                json("{\"filter\":[\"oak_log\",\"minecraft:stone\",\"#logs\",\"#minecraft:planks\"]}")).filter();
        check(filter != null
                        && filter.ids().equals(Arrays.asList("minecraft:oak_log", "minecraft:stone"))
                        && filter.tagIds().equals(Arrays.asList("minecraft:logs", "minecraft:planks"))
                        && filter.hasTags(),
                "inventory filter: bare names namespaced, #tags normalized (#logs -> minecraft:logs)");
        check(InventoryContracts.inventoryParams(json("{\"filter\":null}")).filter() == null,
                "inventory filter: explicit null -> unfiltered");
        expectInvalid(() -> InventoryContracts.inventoryParams(json("{\"filter\":[]}")),
                "inventory filter: empty array rejected");
        expectInvalid(() -> InventoryContracts.inventoryParams(
                        json("{\"filter\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"k\",\"l\","
                                + "\"m\",\"n\",\"o\",\"p\",\"q\"]}")),
                "inventory filter: 17 entries rejected (cap 16)");
        expectInvalid(() -> InventoryContracts.inventoryParams(json("{\"filter\":\"oak_log\"}")),
                "inventory filter: non-array rejected");
        expectInvalid(() -> InventoryContracts.inventoryParams(json("{\"filter\":[\"#\"]}")),
                "inventory filter: bare # rejected");
        expectInvalid(() -> InventoryContracts.inventoryParams(json("{\"filter\":[\"Oak_Log\"]}")),
                "inventory filter: uppercase entry rejected");
        expectInvalid(() -> InventoryContracts.inventoryParams(json("{\"filter\":[\"\"]}")),
                "inventory filter: empty entry rejected");

        // --- scope membership (Inventory container indices)
        check(InventoryContracts.inScope(0, "hotbar") && InventoryContracts.inScope(8, "hotbar")
                        && !InventoryContracts.inScope(9, "hotbar")
                        && InventoryContracts.inScope(9, "main") && InventoryContracts.inScope(35, "main")
                        && !InventoryContracts.inScope(36, "main")
                        && InventoryContracts.inScope(36, "armor") && InventoryContracts.inScope(39, "armor")
                        && !InventoryContracts.inScope(40, "armor")
                        && InventoryContracts.inScope(40, "offhand") && !InventoryContracts.inScope(39, "offhand")
                        && InventoryContracts.inScope(40, "all") && InventoryContracts.inScope(0, "all")
                        && !InventoryContracts.inScope(-1, "all") && !InventoryContracts.inScope(41, "all"),
                "inventory scope: 0-8 hotbar / 9-35 main / 36-39 armor / 40 offhand boundaries");

        // --- 背包槽 → 玩家背包菜单槽（P0-A 回归锚点）
        // 菜单布局和背包布局不是同一套编号：菜单 0 合成输出 / 1-4 合成格 / 5-8 盔甲 /
        // 9-35 主背包 / 36-44 快捷栏。2026-11-01 真机 bug 就是拿背包下标当菜单槽
        // （点击落到合成格/盔甲格），于是主背包里的方块永远换不到手上、place {item} 一直
        // 报 not_in_hotbar。下面 0→36 这条就是那次的回归锚点。
        check(InventoryContracts.menuSlotForPlayerSlot(0) == 36,
                "inventory menu slot: player slot 0 -> menu 36 (2026-11-01 主背包换手上 bug 的回归锚点)");
        check(InventoryContracts.menuSlotForPlayerSlot(8) == 44,
                "inventory menu slot: player slot 8 -> menu 44 (last hotbar slot)");
        check(InventoryContracts.menuSlotForPlayerSlot(9) == 9,
                "inventory menu slot: first main slot 9 -> menu 9 (1:1)");
        check(InventoryContracts.menuSlotForPlayerSlot(20) == 20,
                "inventory menu slot: main slot 20 -> menu 20 (1:1)");
        check(InventoryContracts.menuSlotForPlayerSlot(35) == 35,
                "inventory menu slot: last main slot 35 -> menu 35 (1:1)");
        check(InventoryContracts.menuSlotForPlayerSlot(36) == 5
                        && InventoryContracts.menuSlotForPlayerSlot(39) == 8,
                "inventory menu slot: armor 36-39 -> menu 5-8");
        check(InventoryContracts.menuSlotForPlayerSlot(40) == 45,
                "inventory menu slot: offhand 40 -> menu 45 (outside the hotbar remap)");

        // --- roles agree with getGuiState's classification
        check("hotbar".equals(InventoryContracts.role(0)) && "hotbar".equals(InventoryContracts.role(8))
                        && "player".equals(InventoryContracts.role(9))
                        && "player".equals(InventoryContracts.role(35))
                        && "armor".equals(InventoryContracts.role(36))
                        && "armor".equals(InventoryContracts.role(39))
                        && "offhand".equals(InventoryContracts.role(40)),
                "inventory roles: hotbar/player/armor/offhand follow the container-slot boundaries");

        // --- assembly over a hand-built 41-slot backpack
        List<InventoryContracts.ItemFact> facts = fakeInventory();
        JsonObject all = InventoryContracts.inventoryResult(3, "all", facts, null, (slot, id) -> false);
        check(all.get("in_game").getAsBoolean() && all.get("selected").getAsInt() == 3
                        && all.getAsJsonArray("slots").size() == InventoryContracts.SLOT_COUNT,
                "inventory all: 41 slots reported, selected echoed");
        check(all.getAsJsonObject("counts").get("minecraft:oak_log").getAsInt() == 17
                        && all.getAsJsonObject("counts").get("minecraft:stone").getAsInt() == 64
                        && all.getAsJsonObject("counts").size() == 2,
                "inventory counts: stacks of the same id summed (oak_log 12+5, stone 64)");
        JsonArray emptyHotbar = all.getAsJsonArray("empty_hotbar");
        JsonArray emptyMain = all.getAsJsonArray("empty_main");
        check(emptyHotbar.size() == 7 && contains(emptyHotbar, 1) && contains(emptyHotbar, 8)
                        && !contains(emptyHotbar, 0) && !contains(emptyHotbar, 3),
                "inventory empty_hotbar: empty hotbar indices only ([1,2,4..8])");
        check(emptyMain.size() == 26 && contains(emptyMain, 9) && contains(emptyMain, 11)
                        && contains(emptyMain, 35) && !contains(emptyMain, 10),
                "inventory empty_main: 26 empty main slots, occupied slot 10 excluded");
        JsonObject firstSlot = all.getAsJsonArray("slots").get(0).getAsJsonObject();
        JsonObject emptySlot = all.getAsJsonArray("slots").get(1).getAsJsonObject();
        check(firstSlot.get("slot").getAsInt() == 0
                        && "minecraft:oak_log".equals(firstSlot.get("item").getAsString())
                        && firstSlot.get("count").getAsInt() == 12
                        && "hotbar".equals(firstSlot.get("role").getAsString())
                        && emptySlot.get("item").isJsonNull() && emptySlot.get("count").getAsInt() == 0
                        && "hotbar".equals(emptySlot.get("role").getAsString()),
                "inventory slots: {slot,item,count,role} entries, empty ones carry null/0");
        check(all.getAsJsonArray("matching").size() == 0,
                "inventory matching: empty without a filter");
        // v1.6.1: 显示名与 registry id 一起给（模组物品的 id 模型认不出，显示名才认得出）
        check(firstSlot.has("display") && "Oak Log".equals(firstSlot.get("display").getAsString()),
                "inventory slots: display name rides along with the registry id");
        check(!emptySlot.has("display"),
                "inventory slots: empty slots carry no display name");

        // --- scope narrowing keeps the emptiness lists global (destination slots)
        JsonObject hotbar = InventoryContracts.inventoryResult(3, "hotbar", facts, null, (slot, id) -> false);
        check(hotbar.getAsJsonArray("slots").size() == 9
                        && hotbar.getAsJsonObject("counts").get("minecraft:oak_log").getAsInt() == 17
                        && !hotbar.getAsJsonObject("counts").has("minecraft:stone")
                        && hotbar.getAsJsonArray("empty_main").size() == 26,
                "inventory scope=hotbar: 9 slots, counts narrowed, emptiness lists still global");
        JsonObject armor = InventoryContracts.inventoryResult(3, "armor", facts, null, (slot, id) -> false);
        check(armor.getAsJsonArray("slots").size() == 4
                        && "armor".equals(armor.getAsJsonArray("slots").get(0)
                        .getAsJsonObject().get("role").getAsString()),
                "inventory scope=armor: the four armor slots, role armor");

        // --- filtered view: matching entries only, indices in `matching`
        InventoryContracts.Filter oakFilter = InventoryContracts.inventoryParams(
                json("{\"filter\":[\"oak_log\"]}")).filter();
        JsonObject filtered = InventoryContracts.inventoryResult(3, "all", facts, oakFilter,
                (slot, id) -> "minecraft:oak_log".equals(id));
        check(filtered.getAsJsonArray("slots").size() == 2
                        && contains(filtered.getAsJsonArray("matching"), 0)
                        && contains(filtered.getAsJsonArray("matching"), 3)
                        && filtered.getAsJsonObject("counts").get("minecraft:oak_log").getAsInt() == 17
                        && filtered.getAsJsonArray("empty_hotbar").size() == 7,
                "inventory filter: reports only matching non-empty slots + matching indices");
        InventoryContracts.Filter tagFilter = InventoryContracts.inventoryParams(
                json("{\"filter\":[\"#logs\"]}")).filter();
        JsonObject tagged = InventoryContracts.inventoryResult(3, "all", facts, tagFilter,
                (slot, id) -> slot == 3); // the fake tag probe: only slot 3 is "in the logs tag"
        check(tagged.getAsJsonArray("slots").size() == 1
                        && contains(tagged.getAsJsonArray("matching"), 3)
                        && tagged.getAsJsonArray("slots").get(0).getAsJsonObject()
                        .get("slot").getAsInt() == 3,
                "inventory #tag filter: tag membership comes from the probe, applied per slot");
        check(tagged.getAsJsonObject("counts").get("minecraft:oak_log").getAsInt() == 5,
                "inventory filtered counts: only the matched slot's stack counts");

        // --- inventory.drop params and observed verdicts
        InventoryContracts.DropParams drop = InventoryContracts.dropParams(json("{\"slot\":8,\"count\":3}"));
        check(drop.slot() == 8 && drop.count() == 3,
                "inventory drop: slot and exact count parsed");
        check(InventoryContracts.dropParams(json("{\"slot\":40}")).count() == null
                        && InventoryContracts.dropParams(json("{\"slot\":0,\"count\":null}")).count() == null,
                "inventory drop: omitted/null count means whole stack");
        expectInvalid(() -> InventoryContracts.dropParams(json("{}")),
                "inventory drop: missing slot rejected");
        expectInvalid(() -> InventoryContracts.dropParams(json("{\"slot\":41}")),
                "inventory drop: slot above 40 rejected");
        expectInvalid(() -> InventoryContracts.dropParams(json("{\"slot\":1.5}")),
                "inventory drop: fractional slot rejected");
        expectInvalid(() -> InventoryContracts.dropParams(json("{\"slot\":0,\"count\":0}")),
                "inventory drop: zero count rejected");
        expectInvalid(() -> InventoryContracts.dropParams(json("{\"slot\":0,\"count\":65}")),
                "inventory drop: count above 64 rejected");
        check("dropped".equals(InventoryContracts.dropVerdict(3, 10, 7, true))
                        && "dropped".equals(InventoryContracts.dropVerdict(10, 10, 0, true)),
                "inventory drop: exact partial-count and whole-stack decreases confirm dropped");
        check("partial".equals(InventoryContracts.dropVerdict(3, 10, 8, true)),
                "inventory drop: smaller decrease reports partial");
        check("unknown".equals(InventoryContracts.dropVerdict(3, 10, 10, true))
                        && "unknown".equals(InventoryContracts.dropVerdict(3, 10, 11, true))
                        && "unknown".equals(InventoryContracts.dropVerdict(3, 10, 7, false)),
                "inventory drop: no decrease, growth or item replacement is unknown");
        JsonObject dropped = InventoryContracts.dropResult("dropped",
                new InventoryContracts.DropFacts(0, 36, "minecraft:oak_log", 3, 10, 3, 7), true);
        check(dropped.get("in_game").getAsBoolean() && dropped.get("menu_slot").getAsInt() == 36
                        && dropped.get("dropped").getAsInt() == 3 && dropped.get("remaining").getAsInt() == 7
                        && "client_snapshot".equals(dropped.get("evidence").getAsString()),
                "inventory drop: result carries slot mapping, counts and honest evidence");

        // --- not in a world
        JsonObject offline = InventoryContracts.notInGameResult();
        check(!offline.get("in_game").getAsBoolean()
                        && offline.getAsJsonArray("slots").size() == 0
                        && offline.get("selected").getAsInt() == -1
                        && offline.getAsJsonObject("counts").size() == 0
                        && !offline.has("empty_hotbar"),
                "inventory not-in-game: empty shape, selected -1 (not an error)");
    }

    /** A hand-built 41-slot player inventory for the inventory smoke checks. */
    private static List<InventoryContracts.ItemFact> fakeInventory() {
        List<InventoryContracts.ItemFact> facts = new ArrayList<>();
        for (int slot = 0; slot < InventoryContracts.SLOT_COUNT; slot++) {
            if (slot == 0) {
                facts.add(new InventoryContracts.ItemFact(slot, "minecraft:oak_log", "Oak Log", 12));
            } else if (slot == 3) {
                facts.add(new InventoryContracts.ItemFact(slot, "minecraft:oak_log", "Oak Log", 5));
            } else if (slot == 10) {
                facts.add(new InventoryContracts.ItemFact(slot, "minecraft:stone", "Stone", 64));
            } else {
                facts.add(new InventoryContracts.ItemFact(slot, null, null, 0));
            }
        }
        return facts;
    }

    // ------------------------------------------------------------------ M4.4 v1.4 players

    private static void playersContracts() throws Exception {
        // --- no parameters (the getGuiState discipline)
        PlayersContracts.playersParams(json("{}")); // must not throw
        check(true, "players: empty params accepted");
        expectInvalid(() -> {
            PlayersContracts.playersParams(json("{\"x\":1}"));
            return null;
        }, "players: any member rejected (-32602)");
        expectInvalid(() -> {
            PlayersContracts.playersParams(json("{\"scope\":\"all\"}"));
            return null;
        }, "players: a plausible-but-unknown member rejected too");

        // --- self first, then dictionary order by name
        List<PlayersContracts.PlayerFact> facts = Arrays.asList(
                new PlayersContracts.PlayerFact("Steve", "uuid-steve", false, 42, "survival"),
                new PlayersContracts.PlayerFact("Sirius", "uuid-self", true, 0, "creative"),
                new PlayersContracts.PlayerFact("Alex", "uuid-alex", false, 7, "survival"));
        JsonObject result = PlayersContracts.playersResult(facts);
        JsonArray players = result.getAsJsonArray("players");
        check(result.get("in_game").getAsBoolean() && result.get("count").getAsInt() == 3
                        && players.size() == 3,
                "players: in_game + count + players array");
        check("Sirius".equals(players.get(0).getAsJsonObject().get("name").getAsString())
                        && "Alex".equals(players.get(1).getAsJsonObject().get("name").getAsString())
                        && "Steve".equals(players.get(2).getAsJsonObject().get("name").getAsString()),
                "players: self first, the rest name-sorted (deterministic for asserts)");
        JsonObject self = players.get(0).getAsJsonObject();
        check(self.get("self").getAsBoolean()
                        && "uuid-self".equals(self.get("uuid").getAsString())
                        && self.get("latency_ms").getAsInt() == 0
                        && "creative".equals(self.get("gamemode").getAsString()),
                "players: entry carries self/uuid/latency_ms/gamemode");
        check(self.keySet().equals(java.util.Set.of("name", "uuid", "self", "latency_ms", "gamemode")),
                "players: entry exposes exactly five members (no skin/address leak)");
        check("Steve".equals(facts.get(0).name()) && facts.get(1).self(),
                "players: input list not mutated (defensive copy sorted)");

        // --- null name/uuid tolerated (a half-populated tab entry)
        JsonObject nulls = PlayersContracts.playersResult(Arrays.asList(
                new PlayersContracts.PlayerFact("Zed", "uuid-zed", false, 3, "adventure"),
                new PlayersContracts.PlayerFact(null, null, false, 4, null)));
        check(nulls.getAsJsonArray("players").get(0).getAsJsonObject().get("name").isJsonNull()
                        && nulls.getAsJsonArray("players").get(0).getAsJsonObject().get("uuid").isJsonNull()
                        && nulls.getAsJsonArray("players").get(0).getAsJsonObject().get("gamemode").isJsonNull()
                        && "Zed".equals(nulls.getAsJsonArray("players").get(1)
                        .getAsJsonObject().get("name").getAsString()),
                "players: null name/uuid/gamemode -> JSON null, sorts before named entries");

        // --- not in a world
        JsonObject offline = PlayersContracts.notInGamePlayers();
        check(!offline.get("in_game").getAsBoolean()
                        && offline.get("count").getAsInt() == 0
                        && offline.getAsJsonArray("players").size() == 0,
                "players not-in-game: empty shape (not an error)");
    }

    // ------------------------------------------------------------------ M4.5 v1.5 use

    /**
     * The {@code use} half: params/auto-face geometry, the three-field verdict
     * precedence (screen > block change > FAIL > timeout), the "consumed but
     * invisible" rule and the aim stall guard.
     */
    private static void useContracts() throws Exception {
        UseContracts.UseParams p = UseContracts.useParams(json("{\"x\":10,\"y\":64,\"z\":-20}"));
        check(p.x() == 10 && p.y() == 64 && p.z() == -20
                        && p.face() == null && p.hand() == UseContracts.Hand.MAIN
                        && p.timeoutMs() == UseContracts.DEFAULT_TIMEOUT_MS,
                "use: coords parsed, face auto (null), hand main, timeout 2000");
        check(UseContracts.DEFAULT_TIMEOUT_MS == 2000
                        && UseContracts.MIN_TIMEOUT_MS == 600
                        && UseContracts.MAX_TIMEOUT_MS == 10000
                        && UseContracts.REACH == 4.5
                        && UseContracts.AIM_TURN_SPEED_DEG_S == 300.0
                        && UseContracts.AIM_STALL_TICKS == 100
                        && UseContracts.AIM_TOLERANCE_DEG == 2.0,
                "use: frozen constants (timeout 600..10000/default 2000, reach 4.5, aim 300deg/s,"
                        + " stall 100, tolerance 2deg)");
        check(UseContracts.useParams(json("{\"x\":1,\"y\":2,\"z\":3,\"face\":null}")).face() == null,
                "use: explicit null face -> auto");
        check(UseContracts.useParams(json("{\"x\":1,\"y\":2,\"z\":3,\"face\":\"up\"}")).face()
                        == PlaceContracts.Face.UP,
                "use: face \"up\" parsed (target block's own face)");
        check(UseContracts.useParams(json("{\"x\":1,\"y\":2,\"z\":3,\"hand\":\"off\"}")).hand()
                        == UseContracts.Hand.OFF,
                "use: hand \"off\" parsed");
        check(UseContracts.useParams(json("{\"x\":1,\"y\":2,\"z\":3,\"hand\":null}")).hand()
                        == UseContracts.Hand.MAIN,
                "use: explicit null hand -> main");
        expectInvalid(() -> UseContracts.useParams(json("{\"x\":1,\"y\":2,\"z\":3,\"face\":\"sideways\"}")),
                "use: unknown face rejected (lists the six names)");
        expectInvalid(() -> UseContracts.useParams(json("{\"x\":1,\"y\":2,\"z\":3,\"face\":1}")),
                "use: non-string face rejected");
        expectInvalid(() -> UseContracts.useParams(json("{\"x\":1,\"y\":2,\"z\":3,\"hand\":\"left\"}")),
                "use: unknown hand rejected (main/off only)");
        expectInvalid(() -> UseContracts.useParams(json("{\"y\":65,\"z\":-3}")),
                "use: missing x rejected");
        expectInvalid(() -> UseContracts.useParams(json("{\"x\":1.5,\"y\":2,\"z\":3}")),
                "use: fractional coord rejected");
        expectInvalid(() -> UseContracts.useParams(json("{\"x\":\"1\",\"y\":2,\"z\":3}")),
                "use: string coord rejected");
        check(UseContracts.useParams(json("{\"x\":1,\"y\":2,\"z\":3,\"timeout_ms\":null}"))
                        .timeoutMs() == UseContracts.DEFAULT_TIMEOUT_MS,
                "use: explicit null timeout -> default");
        check(UseContracts.useParams(json("{\"x\":1,\"y\":2,\"z\":3,\"timeout_ms\":600}"))
                        .timeoutMs() == 600,
                "use: timeout 600 (min) accepted");
        expectInvalid(() -> UseContracts.useParams(json("{\"x\":1,\"y\":2,\"z\":3,\"timeout_ms\":599}")),
                "use: timeout below 600 rejected");
        expectInvalid(() -> UseContracts.useParams(json("{\"x\":1,\"y\":2,\"z\":3,\"timeout_ms\":10001}")),
                "use: timeout above 10000 rejected");

        // --- geometry: the click point sits on the TARGET block (not a support block)
        PlaceContracts.Cell target = new PlaceContracts.Cell(10, 64, -20);
        PlaceContracts.Hit up = UseContracts.hitPoint(target, PlaceContracts.Face.UP);
        check(up.x() == 10.5 && up.y() == 65.0 && up.z() == -19.5,
                "use: hitPoint(up) = face centre, 0.5 above the block centre");
        PlaceContracts.Hit east = UseContracts.hitPoint(target, PlaceContracts.Face.EAST);
        check(east.x() == 11.0 && east.y() == 64.5 && east.z() == -19.5,
                "use: hitPoint(east) = face centre, 0.5 east of the block centre");
        PlaceContracts.Hit north = UseContracts.hitPoint(target, PlaceContracts.Face.NORTH);
        check(north.x() == 10.5 && north.y() == 64.5 && north.z() == -20.0,
                "use: hitPoint(north) = face centre, 0.5 north of the block centre");

        // --- AUTO face = the block side pointing at the eye (largest |axis| delta)
        check(UseContracts.autoFace(new PlaceContracts.Hit(10.5, 70.0, -19.5), target)
                        == PlaceContracts.Face.UP,
                "use: auto face from above -> up");
        check(UseContracts.autoFace(new PlaceContracts.Hit(10.5, 60.0, -19.5), target)
                        == PlaceContracts.Face.DOWN,
                "use: auto face from below -> down");
        check(UseContracts.autoFace(new PlaceContracts.Hit(14.0, 64.5, -19.5), target)
                        == PlaceContracts.Face.EAST,
                "use: auto face from +x -> east");
        check(UseContracts.autoFace(new PlaceContracts.Hit(6.0, 64.5, -19.5), target)
                        == PlaceContracts.Face.WEST,
                "use: auto face from -x -> west");
        check(UseContracts.autoFace(new PlaceContracts.Hit(10.5, 64.5, -16.0), target)
                        == PlaceContracts.Face.SOUTH,
                "use: auto face from +z -> south");
        check(UseContracts.autoFace(new PlaceContracts.Hit(10.5, 64.5, -24.0), target)
                        == PlaceContracts.Face.NORTH,
                "use: auto face from -z -> north");
        check(UseContracts.autoFace(new PlaceContracts.Hit(13.0, 67.0, -19.5), target)
                        == PlaceContracts.Face.UP,
                "use: auto face tie (equal x/y deltas) -> up (deterministic)");

        check(UseContracts.outOfReachMessage(target, PlaceContracts.Face.UP, "6.2")
                        .contains("6.2") && UseContracts.outOfReachMessage(
                        target, PlaceContracts.Face.UP, "6.2").contains("4.5")
                        && UseContracts.outOfReachMessage(target, PlaceContracts.Face.UP, "6.2")
                        .contains("up"),
                "use: out-of-reach message names the distance, the reach and the face");

        // --- vocabularies
        check(UseContracts.consumed("success") && UseContracts.consumed("consume")
                        && !UseContracts.consumed("pass") && !UseContracts.consumed("fail")
                        && !UseContracts.consumed(null),
                "use: consumed() true only for success/consume");
        check(UseContracts.RESULT_USED.equals(UseContracts.timeoutVerdict("success"))
                        && UseContracts.RESULT_USED.equals(UseContracts.timeoutVerdict("consume")),
                "use: timeout after a consumed interaction is still \"used\"");
        check(UseContracts.RESULT_NO_EFFECT.equals(UseContracts.timeoutVerdict("pass"))
                        && UseContracts.RESULT_NO_EFFECT.equals(UseContracts.timeoutVerdict("fail")),
                "use: timeout after an unconsumed interaction is \"no_effect\"");

        // --- response assembly
        UseContracts.UseParams chest = UseContracts.useParams(json("{\"x\":10,\"y\":64,\"z\":-20}"));
        JsonObject opened = UseContracts.useResult(chest, UseContracts.RESULT_SCREEN_OPENED,
                "minecraft:chest", PlaceContracts.Face.UP, "main",
                UseContracts.EFFECT_SCREEN, UseContracts.INTERACTION_SUCCESS, "ChestScreen", 180L, null);
        check(opened.get("in_game").getAsBoolean()
                        && UseContracts.RESULT_SCREEN_OPENED.equals(opened.get("result").getAsString())
                        && "minecraft:chest".equals(opened.get("block").getAsString())
                        && "up".equals(opened.get("face").getAsString())
                        && "main".equals(opened.get("hand").getAsString())
                        && UseContracts.EFFECT_SCREEN.equals(opened.get("effect").getAsString())
                        && UseContracts.INTERACTION_SUCCESS.equals(opened.get("interaction").getAsString())
                        && "ChestScreen".equals(opened.get("screen_class").getAsString())
                        && opened.get("elapsed_ms").getAsLong() == 180L
                        && opened.getAsJsonObject("at").get("x").getAsInt() == 10,
                "use: response carries result/block/face/hand/effect/interaction/screen_class/at");
        JsonObject refused = UseContracts.useResult(chest, UseContracts.RESULT_NO_EFFECT,
                null, null, "main", null, UseContracts.INTERACTION_PASS, null, 2005L, "nothing happened");
        check(refused.get("block").isJsonNull() && !refused.has("face")
                        && !refused.has("effect") && !refused.has("screen_class")
                        && "nothing happened".equals(refused.get("reason").getAsString()),
                "use: unknown block is JSON null; face/effect/screen_class omitted when absent");

        // --- monitor: aim -> press -> evidence
        UseContracts.UseMonitor m = new UseContracts.UseMonitor(2000);
        check(m.tick(view(false, false, false, false, null, 0L)).action() == UseContracts.Action.WAIT,
                "use monitor: not aimed yet -> WAIT");
        check(m.tick(view(true, false, false, false, null, 50L)).action() == UseContracts.Action.PRESS,
                "use monitor: aimed and not pressed -> PRESS");
        UseContracts.Verdict screen = m.tick(view(true, true, true, true, "success", 100L));
        check(screen.action() == UseContracts.Action.FINISH
                        && UseContracts.RESULT_SCREEN_OPENED.equals(screen.result())
                        && UseContracts.EFFECT_SCREEN.equals(screen.effect()),
                "use monitor: screen outranks a simultaneous block change -> screen_opened");

        UseContracts.Verdict flipped = new UseContracts.UseMonitor(2000)
                .tick(view(true, true, false, true, "success", 100L));
        check(flipped.action() == UseContracts.Action.FINISH
                        && UseContracts.RESULT_USED.equals(flipped.result())
                        && UseContracts.EFFECT_BLOCK_CHANGED.equals(flipped.effect()),
                "use monitor: block state flipped (property-only counts) -> used/block_changed");

        UseContracts.Verdict fail = new UseContracts.UseMonitor(2000)
                .tick(view(true, true, false, false, "fail", 100L));
        check(fail.action() == UseContracts.Action.FINISH
                        && UseContracts.RESULT_REJECTED.equals(fail.result())
                        && fail.effect() == null,
                "use monitor: client FAIL -> rejected (no evidence)");

        UseContracts.Verdict silent = new UseContracts.UseMonitor(2000)
                .tick(view(true, true, false, false, "pass", 2000L));
        check(silent.action() == UseContracts.Action.FINISH
                        && UseContracts.RESULT_NO_EFFECT.equals(silent.result())
                        && silent.effect() == null,
                "use monitor: timeout with an unconsumed interaction -> no_effect");

        UseContracts.Verdict invisible = new UseContracts.UseMonitor(2000)
                .tick(view(true, true, false, false, "success", 2000L));
        check(invisible.action() == UseContracts.Action.FINISH
                        && UseContracts.RESULT_USED.equals(invisible.result())
                        && UseContracts.EFFECT_NONE_VISIBLE.equals(invisible.effect()),
                "use monitor: timeout with a consumed interaction -> used/none_visible");

        UseContracts.Verdict late = new UseContracts.UseMonitor(2000)
                .tick(view(true, true, true, false, "success", 5000L));
        check(UseContracts.RESULT_SCREEN_OPENED.equals(late.result()),
                "use monitor: a screen arriving after the timeout still wins (evidence outranks time)");

        UseContracts.Verdict neverPressed = new UseContracts.UseMonitor(2000)
                .tick(view(false, false, false, false, null, 2000L));
        check(neverPressed.action() == UseContracts.Action.FINISH
                        && UseContracts.RESULT_NOT_USING.equals(neverPressed.result()),
                "use monitor: timeout before the press -> not_using (the aim owns the failure)");

        UseContracts.UseMonitor stalled = new UseContracts.UseMonitor(10000);
        UseContracts.Verdict stallVerdict = null;
        for (int i = 0; i < UseContracts.AIM_STALL_TICKS; i++) {
            stallVerdict = stalled.tick(view(false, false, false, false, null, i * 50L));
        }
        check(stallVerdict != null && stallVerdict.action() == UseContracts.Action.FINISH
                        && UseContracts.RESULT_NOT_USING.equals(stallVerdict.result()),
                "use monitor: aim stall after 100 ticks -> not_using (no silent timeout)");
    }

    /** A use TickView shorthand (aimed, pressed, screenOpen, blockChanged, interaction, elapsed). */
    private static UseContracts.TickView view(boolean aimed, boolean pressed, boolean screenOpen,
                                              boolean blockChanged, String interaction, long elapsedMs) {
        return new UseContracts.TickView(aimed, pressed, screenOpen, blockChanged, interaction, elapsedMs);
    }

    // ------------------------------------------------------------------ M5.0 v1.6: world.query paging

    /**
     * The v1.6 {@code world.query} half that can be checked without a game:
     * the paging params, the page arithmetic ({@code cursor}/{@code limit}/
     * {@code next_cursor}/{@code has_more}/{@code count}) and the aggregate
     * counting + ordering.
     */
    private static void worldQueryPaging() throws Exception {
        // --- params: defaults, explicit values, bounds
        ToolContracts.WorldQueryParams p = validParams(() ->
                ToolContracts.worldQueryParams(json("{\"type\":\"blocks\",\"range\":16}")));
        check(p.cursor() == 0 && p.limit() == ToolContracts.DEFAULT_LIMIT && !p.aggregate()
                        && ToolContracts.DEFAULT_LIMIT == 128,
                "world.query paging: defaults cursor 0 / limit 128 / aggregate false");
        p = validParams(() -> ToolContracts.worldQueryParams(
                json("{\"type\":\"entities\",\"range\":8,\"cursor\":5,\"limit\":10,\"aggregate\":true}")));
        check(p.cursor() == 5 && p.limit() == 10 && p.aggregate(),
                "world.query paging: cursor/limit/aggregate parsed");
        check(validParams(() -> ToolContracts.worldQueryParams(
                        json("{\"type\":\"blocks\",\"range\":8,\"limit\":null,\"cursor\":null,\"aggregate\":null}")))
                        .limit() == ToolContracts.DEFAULT_LIMIT,
                "world.query paging: explicit nulls fall back to the defaults");
        expectInvalid(() -> ToolContracts.worldQueryParams(
                        json("{\"type\":\"blocks\",\"range\":8,\"limit\":0}")),
                "world.query paging: limit 0 rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(
                        json("{\"type\":\"blocks\",\"range\":8,\"limit\":513}")),
                "world.query paging: limit 513 rejected (schema max 512)");
        expectInvalid(() -> ToolContracts.worldQueryParams(
                        json("{\"type\":\"blocks\",\"range\":8,\"limit\":2.5}")),
                "world.query paging: fractional limit rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(
                        json("{\"type\":\"blocks\",\"range\":8,\"cursor\":-1}")),
                "world.query paging: negative cursor rejected");
        expectInvalid(() -> ToolContracts.worldQueryParams(
                        json("{\"type\":\"blocks\",\"range\":8,\"aggregate\":\"yes\"}")),
                "world.query paging: string aggregate rejected");

        // --- page arithmetic over a hand-built row of 10 matches (nearest first)
        ToolContracts.BlockProbe row = (x, y, z) ->
                (y == 64 && z == 0 && x >= 1 && x <= 10) ? "minecraft:stone" : null;
        JsonObject page0 = ToolContracts.scanBlocksPaged(0.5, 64.5, 0.5, 16, row, null, null, 0, 4, false);
        check(page0.get("blocks").getAsJsonArray().size() == 4
                        && page0.get("count").getAsInt() == 10
                        && page0.get("cursor").getAsInt() == 0
                        && page0.get("next_cursor").getAsInt() == 4
                        && page0.get("has_more").getAsBoolean()
                        && page0.get("truncated").getAsBoolean()
                        && page0.get("limit").getAsInt() == 4,
                "world.query paging: page 0 = 4 of 10, count 10, next_cursor 4, has_more/truncated");
        JsonObject page1 = ToolContracts.scanBlocksPaged(0.5, 64.5, 0.5, 16, row, null, null, 4, 4, false);
        JsonObject page2 = ToolContracts.scanBlocksPaged(0.5, 64.5, 0.5, 16, row, null, null, 8, 4, false);
        check(page1.get("blocks").getAsJsonArray().size() == 4
                        && page1.get("next_cursor").getAsInt() == 8
                        && page1.get("has_more").getAsBoolean()
                        && page2.get("blocks").getAsJsonArray().size() == 2
                        && page2.get("next_cursor").isJsonNull()
                        && !page2.get("has_more").getAsBoolean()
                        && !page2.get("truncated").getAsBoolean(),
                "world.query paging: middle page continues, last page closes (next_cursor null)");
        check(page1.get("blocks").getAsJsonArray().get(0).getAsJsonObject().get("x").getAsInt() == 5
                        && page2.get("blocks").getAsJsonArray().get(1).getAsJsonObject().get("x").getAsInt() == 10,
                "world.query paging: pages continue the distance-sorted sequence (x=5.., ..x=10)");
        JsonObject beyond = ToolContracts.scanBlocksPaged(0.5, 64.5, 0.5, 16, row, null, null, 50, 4, false);
        check(beyond.get("blocks").getAsJsonArray().size() == 0
                        && !beyond.get("has_more").getAsBoolean()
                        && beyond.get("count").getAsInt() == 10,
                "world.query paging: cursor past the end -> empty page, count still the total");
        JsonObject everything = ToolContracts.scanBlocksPaged(0.5, 64.5, 0.5, 16, row, null, null, 0, 512, false);
        check(everything.get("blocks").getAsJsonArray().size() == 10
                        && !everything.get("has_more").getAsBoolean()
                        && everything.get("next_cursor").isJsonNull(),
                "world.query paging: a limit covering everything reports no remainder");

        // --- aggregate: counts only, no coordinates, descending by count
        ToolContracts.BlockProbe mixed = (x, y, z) -> {
            if (y != 64 || z != 0 || x < 1 || x > 10) {
                return null;
            }
            return x <= 6 ? "minecraft:stone" : "minecraft:dirt";
        };
        JsonObject agg = ToolContracts.scanBlocksPaged(0.5, 64.5, 0.5, 16, mixed, null, null, 0, 4, true);
        JsonArray counts = agg.getAsJsonArray("counts");
        check(agg.get("aggregate").getAsBoolean()
                        && !agg.has("blocks")
                        && agg.get("count").getAsInt() == 10
                        && counts.size() == 2
                        && "minecraft:stone".equals(counts.get(0).getAsJsonObject().get("name").getAsString())
                        && counts.get(0).getAsJsonObject().get("count").getAsInt() == 6
                        && "minecraft:dirt".equals(counts.get(1).getAsJsonObject().get("name").getAsString())
                        && counts.get(1).getAsJsonObject().get("count").getAsInt() == 4
                        && !agg.get("has_more").getAsBoolean()
                        && agg.get("next_cursor").isJsonNull(),
                "world.query aggregate: counts sorted desc, no blocks array, count = total");
        ToolContracts.BlockProbe wide = (x, y, z) -> ("w_" + x + "_" + y + "_" + z);
        JsonObject wideAgg = ToolContracts.scanBlocksPaged(0.5, 64.5, 0.5, 2, wide, null, null, 0, 128, true);
        check(wideAgg.getAsJsonArray("counts").size() == ToolContracts.AGGREGATE_MAX_TYPES
                        && wideAgg.get("truncated").getAsBoolean()
                        && wideAgg.get("count").getAsInt() == 125,
                "world.query aggregate: at most " + ToolContracts.AGGREGATE_MAX_TYPES
                        + " names, truncation flagged (125 distinct types)");

        // --- filtered + unfiltered share one distance-ordered sequence
        ToolContracts.BlockFilter stoneOnly =
                ToolContracts.BlockFilter.parse(List.of("minecraft:stone"));
        JsonObject filteredPage = ToolContracts.scanBlocksPaged(0.5, 64.5, 0.5, 16, mixed, stoneOnly, null, 0, 3, false);
        check(filteredPage.get("count").getAsInt() == 6
                        && filteredPage.get("blocks").getAsJsonArray().size() == 3
                        && "minecraft:stone".equals(filteredPage.get("blocks").getAsJsonArray().get(0)
                        .getAsJsonObject().get("block").getAsString()),
                "world.query paging: filter narrows the total, paging applies to the filtered sequence");

        // --- entities: same contract, aggregate by entity type
        List<ToolContracts.EntityFact> facts = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            facts.add(new ToolContracts.EntityFact("u" + i, "E" + i,
                    i <= 3 ? "minecraft:zombie" : "minecraft:cow", i, 64, 0, 10f));
        }
        JsonObject entities0 = ToolContracts.queryEntitiesPaged(facts, 0, 64, 0, 16, null, 0, 2, false);
        check(entities0.get("count").getAsInt() == 5
                        && entities0.get("entities").getAsJsonArray().size() == 2
                        && entities0.get("next_cursor").getAsInt() == 2
                        && entities0.get("has_more").getAsBoolean()
                        && "u1".equals(entities0.get("entities").getAsJsonArray().get(0)
                        .getAsJsonObject().get("uuid").getAsString()),
                "world.query entities: distance-ordered page 0 (nearest first, total 5)");
        JsonObject entitiesLast = ToolContracts.queryEntitiesPaged(facts, 0, 64, 0, 16, null, 4, 2, false);
        check(entitiesLast.get("entities").getAsJsonArray().size() == 1
                        && !entitiesLast.get("has_more").getAsBoolean()
                        && entitiesLast.get("next_cursor").isJsonNull(),
                "world.query entities: last page closes");
        JsonObject entityAgg = ToolContracts.queryEntitiesPaged(facts, 0, 64, 0, 16, null, 0, 128, true);
        check(entityAgg.get("aggregate").getAsBoolean()
                        && !entityAgg.has("entities")
                        && entityAgg.getAsJsonArray("counts").size() == 2
                        && "minecraft:zombie".equals(entityAgg.getAsJsonArray("counts").get(0)
                        .getAsJsonObject().get("name").getAsString())
                        && entityAgg.getAsJsonArray("counts").get(0).getAsJsonObject().get("count").getAsInt() == 3,
                "world.query entities aggregate: counts by entity type, desc");
        JsonObject zombies = ToolContracts.queryEntitiesPaged(facts, 0, 64, 0, 16,
                List.of("minecraft:zombie"), 0, 128, false);
        check(zombies.get("count").getAsInt() == 3
                        && zombies.get("entities").getAsJsonArray().size() == 3
                        && !zombies.get("has_more").getAsBoolean(),
                "world.query entities: type filter narrows count and page");
        check(ToolContracts.queryEntitiesPaged(facts, 0, 64, 0, 1.5, null, 0, 128, false)
                        .get("count").getAsInt() == 1,
                "world.query entities: range still filters before paging");
    }

    // ------------------------------------------------------------------ M5.0 v1.6: chat.history

    private static void chatHistoryContracts() throws Exception {
        // --- params
        ChatHistoryContracts.HistoryParams hp = ChatHistoryContracts.historyParams(json("{}"));
        check(hp.limit() == ChatHistoryContracts.DEFAULT_LIMIT && hp.regex() == null
                        && ChatHistoryContracts.SOURCE_ALL.equals(hp.source()) && hp.sinceMs() == null,
                "chat.history: defaults limit 40 / no regex / source all / no since_ms");
        hp = ChatHistoryContracts.historyParams(
                json("{\"limit\":5,\"regex\":\"Going to:.*\",\"source\":\"client\",\"since_ms\":1000}"));
        check(hp.limit() == 5 && hp.regex() != null && "client".equals(hp.source())
                        && hp.sinceMs() == 1000L,
                "chat.history: limit/regex/source/since_ms parsed");
        check(ChatHistoryContracts.historyParams(json("{\"source\":\"server\"}")).source()
                        .equals(ChatHistoryContracts.SOURCE_SERVER),
                "chat.history: source server accepted");
        expectInvalid(() -> ChatHistoryContracts.historyParams(json("{\"limit\":0}")),
                "chat.history: limit 0 rejected");
        expectInvalid(() -> ChatHistoryContracts.historyParams(json("{\"limit\":201}")),
                "chat.history: limit 201 rejected (schema max 200)");
        expectInvalid(() -> ChatHistoryContracts.historyParams(json("{\"regex\":\"(unclosed\"}")),
                "chat.history: invalid regex rejected with a teaching message");
        expectInvalid(() -> ChatHistoryContracts.historyParams(json("{\"regex\":\"\"}")),
                "chat.history: empty regex rejected");
        expectInvalid(() -> ChatHistoryContracts.historyParams(json("{\"source\":\"lobby\"}")),
                "chat.history: unknown source rejected");
        expectInvalid(() -> ChatHistoryContracts.historyParams(json("{\"since_ms\":-1}")),
                "chat.history: negative since_ms rejected");
        expectInvalid(() -> ChatHistoryContracts.historyParams(json("{\"since_ms\":1.5}")),
                "chat.history: fractional since_ms rejected");

        // --- ring: capacity, eviction, order
        ChatHistoryContracts.ChatRing ring = new ChatHistoryContracts.ChatRing(3);
        for (int i = 1; i <= 5; i++) {
            ring.add(i, ChatHistoryContracts.SOURCE_CLIENT, "line" + i);
        }
        List<ChatHistoryContracts.ChatLine> lines = ring.lines();
        check(ring.size() == 3 && lines.get(0).text().equals("line3") && lines.get(2).text().equals("line5"),
                "chat ring: keeps the newest 3 lines, oldest first");

        // --- capture diff (the piece that must never duplicate or lose a line)
        check(ChatHistoryContracts.newSuffix(List.of("a", "b"), List.of("a", "b", "c")).equals(List.of("c")),
                "chat capture: appended line -> single new suffix");
        check(ChatHistoryContracts.newSuffix(List.of("a", "b", "c"), List.of("c", "d")).equals(List.of("d")),
                "chat capture: sliding window -> only the new tail");
        check(ChatHistoryContracts.newSuffix(List.of("a", "b"), List.of("a", "b")).isEmpty(),
                "chat capture: unchanged window -> nothing new");
        check(ChatHistoryContracts.newSuffix(List.of(), List.of("x", "y")).equals(List.of("x", "y")),
                "chat capture: first read -> everything is new");
        check(ChatHistoryContracts.newSuffix(List.of("a"), List.of()).isEmpty(),
                "chat capture: cleared chat -> nothing to add");
        check(ChatHistoryContracts.newSuffix(List.of("a", "b"), List.of("x", "y")).equals(List.of("x", "y")),
                "chat capture: no overlap (history cleared) -> whole window re-reported");
        check(ChatHistoryContracts.newSuffix(List.of("hi", "hi"), List.of("hi", "hi", "hi")).equals(List.of("hi")),
                "chat capture: repeated identical lines still count as new");

        // --- server/client attribution memory
        ChatHistoryContracts.ServerTextLog serverLog = new ChatHistoryContracts.ServerTextLog(2);
        serverLog.note("Steve: hi");
        serverLog.note("Steve: hi");
        check(serverLog.consume("Steve: hi") && serverLog.consume("Steve: hi") && !serverLog.consume("Steve: hi"),
                "chat source: each server line is consumed once (duplicates need duplicates)");
        serverLog.note("a");
        serverLog.note("b");
        serverLog.note("c");
        check(serverLog.size() == 2 && !serverLog.consume("a") && serverLog.consume("c"),
                "chat source: memory is bounded, oldest forgotten first");
        check(ChatHistoryContracts.SOURCE_SERVER.equals(ChatHistoryContracts.sourceOf(true))
                        && ChatHistoryContracts.SOURCE_CLIENT.equals(ChatHistoryContracts.sourceOf(false)),
                "chat source: unknown lines default to client (never guessed as server)");

        // --- filtering + result assembly
        List<ChatHistoryContracts.ChatLine> buffer = List.of(
                new ChatHistoryContracts.ChatLine(1000, ChatHistoryContracts.SOURCE_SERVER, "Steve: hi"),
                new ChatHistoryContracts.ChatLine(2000, ChatHistoryContracts.SOURCE_CLIENT, "Going to: 100 64 200"),
                new ChatHistoryContracts.ChatLine(3000, ChatHistoryContracts.SOURCE_CLIENT, "Arrived at goal"),
                new ChatHistoryContracts.ChatLine(4000, ChatHistoryContracts.SOURCE_CLIENT, "Steve: hi"));
        JsonObject all = ChatHistoryContracts.historyResult(buffer,
                new ChatHistoryContracts.HistoryParams(40, null, ChatHistoryContracts.SOURCE_ALL, null), true);
        check(all.get("count").getAsInt() == 4 && all.get("captured").getAsBoolean()
                        && all.getAsJsonArray("lines").get(0).getAsJsonObject().get("t").getAsLong() == 1000L
                        && "server".equals(all.getAsJsonArray("lines").get(0).getAsJsonObject()
                        .get("source").getAsString()),
                "chat.history: all lines carry t/source/text, captured echoed");
        JsonObject limited = ChatHistoryContracts.historyResult(buffer,
                new ChatHistoryContracts.HistoryParams(2, null, ChatHistoryContracts.SOURCE_ALL, null), true);
        check(limited.get("count").getAsInt() == 2
                        && "Arrived at goal".equals(limited.getAsJsonArray("lines").get(0)
                        .getAsJsonObject().get("text").getAsString()),
                "chat.history: limit keeps the NEWEST lines");
        JsonObject clientOnly = ChatHistoryContracts.historyResult(buffer,
                new ChatHistoryContracts.HistoryParams(40, null, ChatHistoryContracts.SOURCE_CLIENT, null), true);
        check(clientOnly.get("count").getAsInt() == 3,
                "chat.history: source=client hides server lines");
        JsonObject regexed = ChatHistoryContracts.historyResult(buffer,
                new ChatHistoryContracts.HistoryParams(40, java.util.regex.Pattern.compile("Going to:"),
                        ChatHistoryContracts.SOURCE_ALL, null), true);
        check(regexed.get("count").getAsInt() == 1
                        && "Going to: 100 64 200".equals(regexed.getAsJsonArray("lines").get(0)
                        .getAsJsonObject().get("text").getAsString()),
                "chat.history: regex filters per line");
        JsonObject since = ChatHistoryContracts.historyResult(buffer,
                new ChatHistoryContracts.HistoryParams(40, null, ChatHistoryContracts.SOURCE_ALL, 3000L), true);
        check(since.get("count").getAsInt() == 2,
                "chat.history: since_ms keeps t >= since_ms only");
        JsonObject empty = ChatHistoryContracts.historyResult(List.of(),
                new ChatHistoryContracts.HistoryParams(40, null, ChatHistoryContracts.SOURCE_ALL, null), false);
        check(empty.get("count").getAsInt() == 0 && empty.getAsJsonArray("lines").size() == 0
                        && !empty.get("captured").getAsBoolean(),
                "chat.history: empty buffer -> empty lines, captured false (not an error)");
    }

    // ------------------------------------------------------------------ M5.0 v1.6: baritone.status

    private static void baritoneApiContracts() throws Exception {
        BaritoneApiContracts.GoToParams go = BaritoneApiContracts.goToParams(json("{\"x\":10,\"z\":-5}"));
        check(go.x() == 10 && go.z() == -5 && go.y() == null && !go.allowBreak() && !go.allowPlace(),
                "baritone api: goto defaults to GoalXZ with safe settings");
        go = BaritoneApiContracts.goToParams(json(
                "{\"x\":1,\"y\":70,\"z\":2,\"allow_break\":true,\"allow_place\":true}"));
        check(go.y() == 70 && go.allowBreak() && go.allowPlace(),
                "baritone api: goto accepts a Y coordinate and explicit permissions");
        expectInvalid(() -> BaritoneApiContracts.goToParams(json("{\"x\":1}")),
                "baritone api: goto requires Z");
        expectInvalid(() -> BaritoneApiContracts.goToParams(json("{\"x\":1.5,\"z\":2}")),
                "baritone api: goto rejects fractional coordinates");
        expectInvalid(() -> BaritoneApiContracts.goToParams(json("{\"x\":1,\"z\":2,\"allow_break\":1}")),
                "baritone api: goto rejects non-boolean settings");

        BaritoneApiContracts.SettingsParams settings = BaritoneApiContracts.settingsParams(json("{}"));
        check(settings.allowBreak() == null && settings.allowPlace() == null && settings.chatControl() == null,
                "baritone api: empty settings leave every value unchanged");
        settings = BaritoneApiContracts.settingsParams(json(
                "{\"allow_break\":false,\"allow_place\":true,\"chat_control\":false}"));
        check(!settings.allowBreak() && settings.allowPlace() && !settings.chatControl(),
                "baritone api: settings parse all supported fields");

        BaritoneApiContracts.FollowParams follow = BaritoneApiContracts.followParams(
                json("{\"uuid\":\"123e4567-e89b-42d3-a456-426614174000\"}"));
        check(follow.uuid() != null && follow.name() == null && follow.followRadius() == 10
                        && !follow.allowBreak() && !follow.allowPlace(),
                "baritone api: follow defaults to radius 10 with safe settings");
        follow = BaritoneApiContracts.followParams(json(
                "{\"name\":\"Steve\",\"follow_radius\":24,\"allow_break\":true,\"allow_place\":true}"));
        check("Steve".equals(follow.name()) && follow.followRadius() == 24
                        && follow.allowBreak() && follow.allowPlace(),
                "baritone api: follow accepts a player name and custom radius");
        expectInvalid(() -> BaritoneApiContracts.followParams(json("{}")),
                "baritone api: follow requires a target selector");
        expectInvalid(() -> BaritoneApiContracts.followParams(json(
                "{\"uuid\":\"abc\",\"name\":\"Steve\"}")),
                "baritone api: follow rejects ambiguous uuid and name selectors");
        expectInvalid(() -> BaritoneApiContracts.followParams(json(
                "{\"name\":\"Steve\",\"follow_radius\":0}")),
                "baritone api: follow radius must be positive");
        expectInvalid(() -> BaritoneApiContracts.followParams(json(
                "{\"name\":\"Steve\",\"follow_radius\":65}")),
                "baritone api: follow radius is capped at 64");

        BaritoneApiContracts.TunnelParams tunnel = BaritoneApiContracts.tunnelParams(
                json("{\"height\":2,\"width\":1,\"depth\":3}"));
        check(tunnel.height() == 2 && tunnel.width() == 1 && tunnel.depth() == 3
                        && tunnel.timeoutMs() == 120000,
                "baritone api: tunnel parameters and default timeout");
        expectInvalid(() -> BaritoneApiContracts.tunnelParams(json("{\"height\":1,\"width\":1,\"depth\":3}")),
                "baritone api: tunnel requires height at least two");
        expectInvalid(() -> BaritoneApiContracts.tunnelParams(json("{\"height\":2,\"width\":1}")),
                "baritone api: tunnel requires depth");

        BaritoneApiContracts.Region south = BaritoneApiContracts.region(10, 64, 20, 2, 1, 3, "SOUTH");
        check(south.minX() == 10 && south.maxX() == 10 && south.minY() == 64 && south.maxY() == 65
                        && south.minZ() == 20 && south.maxZ() == 23 && south.volume() == 8,
                "baritone api: south tunnel geometry matches TunnelCommand");
        BaritoneApiContracts.Region north = BaritoneApiContracts.region(10, 64, 20, 3, 2, 2, "NORTH");
        check(north.minX() == 10 && north.maxX() == 11 && north.minZ() == 18 && north.maxZ() == 20
                        && north.volume() == 18,
                "baritone api: even-width north tunnel geometry matches TunnelCommand");
        BaritoneApiContracts.Region east = BaritoneApiContracts.region(10, 64, 20, 2, 1, 3, "EAST");
        check(east.minX() == 10 && east.maxX() == 13 && east.minZ() == 20 && east.maxZ() == 20,
                "baritone api: east tunnel depth follows the facing direction");
        BaritoneApiContracts.Region west = BaritoneApiContracts.region(10, 64, 20, 2, 1, 3, "WEST");
        check(west.minX() == 7 && west.maxX() == 10 && west.minZ() == 20 && west.maxZ() == 20,
                "baritone api: west tunnel depth follows the facing direction");
        expectInvalid(() -> BaritoneApiContracts.region(0, 0, 0, 2, 1, 1, "UP"),
                "baritone api: non-horizontal tunnel direction rejected");

        BaritoneApiContracts.emptyParams(json("{}"), "baritone.stop");
        check(true, "baritone api: stop accepts empty parameters");
        expectInvalid(() -> {
            BaritoneApiContracts.emptyParams(json("{\"force\":true}"), "baritone.stop");
            return null;
        }, "baritone api: stop rejects unknown parameters");

        check(PermissionContracts.allows(PermissionContracts.Tier.INPUT_GUI, false,
                        PermissionContracts.Action.BARITONE)
                        && PermissionContracts.allows(PermissionContracts.Tier.INPUT_WORLD, true,
                        PermissionContracts.Action.BARITONE)
                        && !PermissionContracts.allows(PermissionContracts.Tier.OBSERVE, false,
                        PermissionContracts.Action.BARITONE),
                "baritone api: actions are screen-independent but observe remains read-only");
    }

    // ------------------------------------------------------------------ M5.0 v1.6: useItem

    private static void useItemContracts() throws Exception {
        // --- params
        UseItemContracts.UseItemParams p = UseItemContracts.useItemParams(json("{}"));
        check(p.hand() == UseContracts.Hand.MAIN && p.times() == 1
                        && p.intervalMs() == UseItemContracts.DEFAULT_INTERVAL_MS
                        && p.timeoutMs() == UseItemContracts.DEFAULT_TIMEOUT_MS,
                "useItem: defaults hand main / times 1 / interval 300 / timeout 2000");
        p = UseItemContracts.useItemParams(
                json("{\"hand\":\"off\",\"times\":3,\"interval_ms\":0,\"timeout_ms\":5000}"));
        check(p.hand() == UseContracts.Hand.OFF && p.times() == 3 && p.intervalMs() == 0
                        && p.timeoutMs() == 5000,
                "useItem: hand/times/interval/timeout parsed (interval 0 allowed)");
        check(UseItemContracts.useItemParams(json("{\"timeout_ms\":null}")).timeoutMs()
                        == UseItemContracts.DEFAULT_TIMEOUT_MS,
                "useItem: explicit null timeout -> default");
        expectInvalid(() -> UseItemContracts.useItemParams(json("{\"hand\":\"left\"}")),
                "useItem: unknown hand rejected");
        expectInvalid(() -> UseItemContracts.useItemParams(json("{\"times\":0}")),
                "useItem: times 0 rejected");
        expectInvalid(() -> UseItemContracts.useItemParams(json("{\"times\":11}")),
                "useItem: times 11 rejected (schema max 10)");
        expectInvalid(() -> UseItemContracts.useItemParams(json("{\"interval_ms\":5001}")),
                "useItem: interval over cap rejected");
        expectInvalid(() -> UseItemContracts.useItemParams(json("{\"timeout_ms\":199}")),
                "useItem: timeout below 200 rejected");
        expectInvalid(() -> UseItemContracts.useItemParams(json("{\"timeout_ms\":10001}")),
                "useItem: timeout above 10000 rejected");

        // --- vocabulary sanity (the brain maps these to teaching phrases)
        List<String> vocabulary = Arrays.asList(UseItemContracts.RESULT_USED,
                UseItemContracts.RESULT_INCOMPLETE, UseItemContracts.RESULT_NO_EFFECT,
                UseItemContracts.RESULT_SCREEN_OPENED, UseItemContracts.RESULT_GUI_OPEN,
                UseItemContracts.RESULT_NOT_IN_GAME, UseItemContracts.RESULT_NOT_USING,
                UseItemContracts.RESULT_TIMEOUT);
        check(vocabulary.size() == 8 && new java.util.HashSet<>(vocabulary).size() == 8,
                "useItem: 8 distinct result strings (incomplete added in 1.6.2)");
        check(UseItemContracts.INCOMPLETE_REASON.contains("按住")
                        && UseItemContracts.INCOMPLETE_REASON.contains("饱食"),
                "useItem: incomplete carries a human reason (mentions 按住 + 饱食, not a bare word)");

        // --- monitor: a consumed press with NO evidence is no longer "used"
        // 2026-11-01 真机：面包 item_before=3 / item_after=3、饱食不变，却回报 used。
        UseItemContracts.UseItemParams one = UseItemContracts.useItemParams(json("{}"));
        UseItemContracts.UseItemMonitor m = new UseItemContracts.UseItemMonitor(one, 3);
        check(m.tick(itemView(true, false, 0, 3)).action() == UseItemContracts.Action.PRESS,
                "useItem monitor: presses on the first tick");
        m.notePress(UseContracts.INTERACTION_CONSUME, 0);
        UseItemContracts.Verdict done = m.tick(itemView(true, false, 100, 3));
        check(done.terminal() && UseItemContracts.RESULT_INCOMPLETE.equals(done.result())
                        && m.presses() == 1,
                "useItem monitor: consumed press but nothing changed -> incomplete (not the 1.6.1 fake used)");

        // --- 证据四组（纯函数层，spec 的验收用例）
        // 1) 数量 3→3、饱食 18→18、装备未变、interaction=consume -> incomplete
        UseItemContracts.UseItemMonitor fed = new UseItemContracts.UseItemMonitor(one,
                new UseItemContracts.UseSnapshot(3, 18, 5, "minecraft:golden_helmet"));
        fed.tick(fullView(0, 3, 18, 5, "minecraft:golden_helmet"));
        fed.notePress(UseContracts.INTERACTION_CONSUME, 0);
        check(UseItemContracts.RESULT_INCOMPLETE.equals(
                        fed.tick(fullView(100, 3, 18, 5, "minecraft:golden_helmet")).result()),
                "useItem evidence: 3->3 item, 18->18 food, same helmet, consume -> incomplete");
        // 2) 数量 3→2 -> used
        UseItemContracts.UseItemMonitor ate = new UseItemContracts.UseItemMonitor(one,
                new UseItemContracts.UseSnapshot(3, 18, 5, "minecraft:golden_helmet"));
        ate.tick(fullView(0, 3, 18, 5, "minecraft:golden_helmet"));
        ate.notePress(UseContracts.INTERACTION_CONSUME, 0);
        check(UseItemContracts.RESULT_USED.equals(
                        ate.tick(fullView(100, 2, 18, 5, "minecraft:golden_helmet")).result()),
                "useItem evidence: item 3->2 -> used");
        // 3) 数量不变但饱食 18→20 -> used
        UseItemContracts.UseItemMonitor hunger = new UseItemContracts.UseItemMonitor(one,
                new UseItemContracts.UseSnapshot(3, 18, 5, "minecraft:golden_helmet"));
        hunger.tick(fullView(0, 3, 18, 5, "minecraft:golden_helmet"));
        hunger.notePress(UseContracts.INTERACTION_CONSUME, 0);
        check(UseItemContracts.RESULT_USED.equals(
                        hunger.tick(fullView(100, 3, 20, 5, "minecraft:golden_helmet")).result()),
                "useItem evidence: food 18->20 -> used");
        // 4) 数量不变、饱食不变，金头盔 -> 铁头盔 -> used
        UseItemContracts.UseItemMonitor armour = new UseItemContracts.UseItemMonitor(one,
                new UseItemContracts.UseSnapshot(3, 18, 5, "minecraft:golden_helmet"));
        armour.tick(fullView(0, 3, 18, 5, "minecraft:golden_helmet"));
        armour.notePress(UseContracts.INTERACTION_SUCCESS, 0);
        check(UseItemContracts.RESULT_USED.equals(
                        armour.tick(fullView(100, 3, 18, 5, "minecraft:iron_helmet")).result()),
                "useItem evidence: helmet golden -> iron -> used");
        // 附带：空盔甲槽 -> 戴上头盔也是证据（空串不等于"未知"）
        UseItemContracts.UseItemMonitor worn = new UseItemContracts.UseItemMonitor(one,
                new UseItemContracts.UseSnapshot(1, 20, 5, ""));
        worn.tick(fullView(0, 1, 20, 5, ""));
        worn.notePress(UseContracts.INTERACTION_SUCCESS, 0);
        check(UseItemContracts.RESULT_USED.equals(
                        worn.tick(fullView(100, 1, 20, 5, "minecraft:iron_helmet")).result()),
                "useItem evidence: empty helmet slot -> iron helmet -> used");
        // 附带：饱和度上升也算（不涨饱食只涨饱和度的食物）
        UseItemContracts.UseItemMonitor sat = new UseItemContracts.UseItemMonitor(one,
                new UseItemContracts.UseSnapshot(3, 18, 1, ""));
        sat.tick(fullView(0, 3, 18, 1, ""));
        sat.notePress(UseContracts.INTERACTION_CONSUME, 0);
        check(UseItemContracts.RESULT_USED.equals(sat.tick(fullView(100, 3, 18, 4, "")).result()),
                "useItem evidence: saturation 1->4 -> used");

        // --- 按住时的有效截止时间（不能把假成功换成假超时）
        // 食物 getUseDuration()=32 tick → 预算 32+10=42 tick ≈ 2.1 s，已经超过默认
        // timeout_ms=2000；默认参数下吃个面包若还按 timeout_ms 判，就会被判成 timeout。
        check(UseItemContracts.holdAwareTimeoutMs(2000, 32 + UseItemContracts.HOLD_SLACK_TICKS) == 2600,
                "useItem: hold-aware deadline raises 2000 ms to hold budget + 500 ms (bread = 2600)");
        check(UseItemContracts.holdAwareTimeoutMs(5000, 42) == 5000,
                "useItem: a bigger explicit timeout_ms is never lowered by the hold deadline");
        check(UseItemContracts.holdAwareTimeoutMs(200, 42) == 2600,
                "useItem: the smallest legal timeout_ms still lets one full bread bar finish");
        // 真超时还是 timeout（只有证据能升成 used），不跟 incomplete 混。
        UseItemContracts.UseItemParams slowParams2 = UseItemContracts.useItemParams(
                json("{\"times\":3,\"interval_ms\":0,\"timeout_ms\":1000}"));
        UseItemContracts.UseItemMonitor timedOut = new UseItemContracts.UseItemMonitor(slowParams2, 3);
        timedOut.tick(itemView(true, false, 0, 3));
        timedOut.notePress(UseContracts.INTERACTION_CONSUME, 0);
        check(UseItemContracts.RESULT_TIMEOUT.equals(timedOut.tick(itemView(true, false, 1000, 3)).result()),
                "useItem monitor: a real timeout stays timeout (consumed but no evidence, sequence unfinished)");

        // --- silent PASS -> no_effect
        UseItemContracts.UseItemMonitor silent = new UseItemContracts.UseItemMonitor(one, 3);
        silent.tick(itemView(true, false, 0, 3));
        silent.notePress(UseContracts.INTERACTION_PASS, 0);
        check(UseItemContracts.RESULT_NO_EFFECT.equals(silent.tick(itemView(true, false, 80, 3)).result()),
                "useItem monitor: an unconsumed press -> no_effect");

        // --- item count evidence (eating: consume may come without a stack change tick)
        UseItemContracts.UseItemMonitor eaten = new UseItemContracts.UseItemMonitor(one, 3);
        eaten.tick(itemView(true, false, 0, 3));
        eaten.notePress(UseContracts.INTERACTION_PASS, 0);
        check(UseItemContracts.RESULT_USED.equals(eaten.tick(itemView(true, false, 80, 2)).result()),
                "useItem monitor: a stack size change counts as used even on a PASS");

        // --- screens
        check(UseItemContracts.RESULT_GUI_OPEN.equals(new UseItemContracts.UseItemMonitor(one, 1)
                        .tick(itemView(true, true, 0, 1)).result()),
                "useItem monitor: a screen before any press -> gui_open");
        UseItemContracts.UseItemMonitor screen = new UseItemContracts.UseItemMonitor(one, 1);
        screen.tick(itemView(true, false, 0, 1));
        screen.notePress(UseContracts.INTERACTION_CONSUME, 0);
        check(UseItemContracts.RESULT_SCREEN_OPENED.equals(screen.tick(itemView(true, true, 50, 1)).result()),
                "useItem monitor: a screen after the press -> screen_opened");

        // --- multi-press pacing: one press per interval, then the verdict
        UseItemContracts.UseItemParams three = UseItemContracts.useItemParams(
                json("{\"times\":3,\"interval_ms\":300,\"timeout_ms\":2000}"));
        UseItemContracts.UseItemMonitor paced = new UseItemContracts.UseItemMonitor(three, 5);
        int presses = 0;
        long clock = 0;
        UseItemContracts.Verdict v = paced.tick(itemView(true, false, clock, 5));
        while (v.action() == UseItemContracts.Action.PRESS && presses < 10) {
            presses++;
            paced.notePress(UseContracts.INTERACTION_CONSUME, clock);
            clock += 300;
            // 每按一次面包少一个：证据是"数量真的变了"，不是"游戏接受了 press"
            v = paced.tick(itemView(true, false, clock, 5 - presses));
        }
        check(presses == 3 && v.terminal() && UseItemContracts.RESULT_USED.equals(v.result()),
                "useItem monitor: times=3 -> exactly 3 presses then used (each press ate one)");
        UseItemContracts.UseItemMonitor waiting = new UseItemContracts.UseItemMonitor(three, 5);
        waiting.notePress(UseContracts.INTERACTION_CONSUME, 0);
        check(waiting.tick(itemView(true, false, 100, 5)).action() == UseItemContracts.Action.WAIT,
                "useItem monitor: waits out the interval between presses");

        // --- leaving the world / the press schedule outliving the budget
        check(UseItemContracts.RESULT_NOT_IN_GAME.equals(new UseItemContracts.UseItemMonitor(one, 1)
                        .tick(itemView(false, false, 0, 1)).result()),
                "useItem monitor: not in game -> not_in_game");
        // A completed press sequence resolves by EVIDENCE (no_effect), never by
        // the clock; timeout is the verdict for a schedule the budget cut short.
        UseItemContracts.UseItemParams slowParams = UseItemContracts.useItemParams(
                json("{\"times\":3,\"interval_ms\":5000,\"timeout_ms\":1000}"));
        UseItemContracts.UseItemMonitor slow = new UseItemContracts.UseItemMonitor(slowParams, 1);
        check(slow.tick(itemView(true, false, 0, 1)).action() == UseItemContracts.Action.PRESS,
                "useItem monitor: slow schedule still presses once immediately");
        slow.notePress(UseContracts.INTERACTION_PASS, 0);
        check(UseItemContracts.RESULT_TIMEOUT.equals(slow.tick(itemView(true, false, 1000, 1)).result()),
                "useItem monitor: budget exhausted mid-schedule with no evidence -> timeout");
        UseItemContracts.UseItemMonitor silentOne = new UseItemContracts.UseItemMonitor(one, 1);
        silentOne.tick(itemView(true, false, 0, 1));
        silentOne.notePress(UseContracts.INTERACTION_PASS, 0);
        check(UseItemContracts.RESULT_NO_EFFECT.equals(silentOne.tick(itemView(true, false, 2000, 1)).result()),
                "useItem monitor: a finished silent press sequence -> no_effect (not timeout)");

        // --- response shape
        JsonObject result = UseItemContracts.useItemResult(three, UseItemContracts.RESULT_USED, 3, 32,
                UseContracts.INTERACTION_CONSUME, null, 5, 2, 18, 20, 640L, null);
        check(result.get("in_game").getAsBoolean()
                        && "used".equals(result.get("result").getAsString())
                        && "main".equals(result.get("hand").getAsString())
                        && result.get("times").getAsInt() == 3
                        && result.get("pressed").getAsInt() == 3
                        && result.get("hold_ticks").getAsInt() == 32
                        && result.get("item_before").getAsInt() == 5
                        && result.get("item_after").getAsInt() == 2
                        && result.get("food_before").getAsInt() == 18
                        && result.get("food_after").getAsInt() == 20
                        && "consume".equals(result.get("interaction").getAsString())
                        && result.get("elapsed_ms").getAsLong() == 640L
                        && !result.has("screen_class") && !result.has("reason"),
                "useItem result: result/hand/times/pressed/hold_ticks/item_*/food_*/interaction");
        JsonObject opened = UseItemContracts.useItemResult(one, UseItemContracts.RESULT_SCREEN_OPENED, 1, 0,
                UseContracts.INTERACTION_SUCCESS, "BookScreen", 1, 1, 20, 20, 120L, null);
        check("BookScreen".equals(opened.get("screen_class").getAsString()),
                "useItem result: screen_opened carries screen_class");
    }

    /** A useItem TickView shorthand (inGame, screenOpen, elapsed, itemCount). */
    private static UseItemContracts.TickView itemView(boolean inGame, boolean screenOpen,
                                                      long elapsedMs, int itemCount) {
        return new UseItemContracts.TickView(inGame, screenOpen, elapsedMs, itemCount);
    }

    /** 带全部四项证据的 useItem TickView（数量 / 饱食 / 饱和度 / 盔甲槽物品 id）。 */
    private static UseItemContracts.TickView fullView(long elapsedMs, int itemCount, int food,
                                                      int saturation, String armor) {
        return new UseItemContracts.TickView(true, false, elapsedMs, itemCount, food, saturation, armor);
    }

    // ------------------------------------------------------------------ M5.0 v1.6: entity tools

    private static void entityContracts() throws Exception {
        // --- entity.use params
        EntityContracts.EntityUseParams use = EntityContracts.entityUseParams(
                json("{\"name\":\"Cow\"}"));
        check("Cow".equals(use.name()) && use.uuid() == null
                        && use.radius() == EntityContracts.DEFAULT_RADIUS
                        && use.hand() == UseContracts.Hand.MAIN && !use.atCenter()
                        && use.timeoutMs() == EntityContracts.DEFAULT_TIMEOUT_MS,
                "entity.use: name + defaults (radius 6, main hand, at auto, timeout 2000)");
        use = EntityContracts.entityUseParams(
                json("{\"uuid\":\"abc\",\"radius\":12.5,\"hand\":\"off\",\"at\":\"center\",\"timeout_ms\":900}"));
        check("abc".equals(use.uuid()) && use.radius() == 12.5 && use.hand() == UseContracts.Hand.OFF
                        && use.atCenter() && use.timeoutMs() == 900,
                "entity.use: uuid/radius/hand/at/timeout parsed");
        check(!EntityContracts.entityUseParams(json("{\"at\":\"auto\"}")).atCenter()
                        && !EntityContracts.entityUseParams(json("{\"at\":null}")).atCenter(),
                "entity.use: at auto (and explicit null) -> interact, not interactAt");
        expectInvalid(() -> EntityContracts.entityUseParams(json("{\"at\":\"middle\"}")),
                "entity.use: unknown at rejected");
        expectInvalid(() -> EntityContracts.entityUseParams(json("{\"hand\":\"left\"}")),
                "entity.use: unknown hand rejected");
        expectInvalid(() -> EntityContracts.entityUseParams(json("{\"radius\":0}")),
                "entity.use: radius 0 rejected");
        expectInvalid(() -> EntityContracts.entityUseParams(json("{\"radius\":65}")),
                "entity.use: radius over 64 rejected");
        expectInvalid(() -> EntityContracts.entityUseParams(json("{\"timeout_ms\":10001}")),
                "entity.use: timeout above 10000 rejected");
        expectInvalid(() -> EntityContracts.entityUseParams(json("{\"name\":42}")),
                "entity.use: non-string name rejected");
        check(EntityContracts.entityUseParams(json("{\"name\":\"  \"}")).name() == null,
                "entity.use: blank name treated as absent (falls back to nearest)");

        // --- entity.attack params
        EntityContracts.EntityAttackParams atk = EntityContracts.entityAttackParams(json("{}"));
        check(atk.count() == 1 && atk.intervalMs() == EntityContracts.DEFAULT_ATTACK_INTERVAL_MS
                        && atk.waitCooldown() && atk.radius() == EntityContracts.DEFAULT_RADIUS,
                "entity.attack: defaults count 1 / interval 600 / wait_cooldown true / radius 6");
        atk = EntityContracts.entityAttackParams(
                json("{\"uuid\":\"u1\",\"count\":4,\"interval_ms\":250,\"wait_cooldown\":false}"));
        check(atk.count() == 4 && atk.intervalMs() == 250 && !atk.waitCooldown(),
                "entity.attack: count/interval/wait_cooldown parsed");
        expectInvalid(() -> EntityContracts.entityAttackParams(json("{\"count\":0}")),
                "entity.attack: count 0 rejected");
        expectInvalid(() -> EntityContracts.entityAttackParams(json("{\"count\":11}")),
                "entity.attack: count 11 rejected");
        expectInvalid(() -> EntityContracts.entityAttackParams(json("{\"wait_cooldown\":\"yes\"}")),
                "entity.attack: non-boolean wait_cooldown rejected");
        expectInvalid(() -> EntityContracts.entityAttackParams(json("{\"interval_ms\":5001}")),
                "entity.attack: interval over cap rejected");

        // --- target resolution: uuid -> name (exact then contains) -> nearest
        List<EntityContracts.Candidate> candidates = List.of(
                new EntityContracts.Candidate("AAAA-1", "Cow", 4.0),
                new EntityContracts.Candidate("BBBB-2", "Cowtipper", 1.0),
                new EntityContracts.Candidate("CCCC-3", "Zombie", 2.0));
        check(EntityContracts.resolve(candidates, "aaaa-1", null, 6) == 0,
                "entity resolve: uuid wins, matched case-insensitively");
        check(EntityContracts.resolve(candidates, "nope", null, 6) == -1,
                "entity resolve: unknown uuid -> -1 (gone)");
        check(EntityContracts.resolve(candidates, null, "Cow", 6) == 0,
                "entity resolve: exact name beats a substring match (Cow, not Cowtipper)");
        check(EntityContracts.resolve(candidates, null, "owti", 6) == 1,
                "entity resolve: case-insensitive substring when no exact match");
        check(EntityContracts.resolve(candidates, null, null, 6) == 1,
                "entity resolve: no selector -> nearest entity");
        check(EntityContracts.resolve(candidates, null, null, 1.5) == 1
                        && EntityContracts.resolve(candidates, null, "Zombie", 1.5) == -1,
                "entity resolve: radius excludes farther candidates (even a name match outside it)");
        check(EntityContracts.resolve(candidates, null, "Cow", 3.0) == 1,
                "entity resolve: outside-radius exact name falls back to the in-radius substring match");
        check(EntityContracts.resolve(List.of(), null, null, 6) == -1,
                "entity resolve: empty world -> -1");

        // --- reach geometry (vanilla AABB distance)
        check(EntityContracts.distanceToBox(0, 0, 0, -0.5, -0.5, -0.5, 0.5, 0.5, 0.5) == 0.0,
                "entity reach: eye inside the box -> 0");
        check(Math.abs(EntityContracts.distanceToBox(0, 0, 0, 1.0, -0.5, -0.5, 2.0, 0.5, 0.5)
                - 1.0) < 1e-9,
                "entity reach: one block beside the box -> 1.0");
        check(Math.abs(EntityContracts.distanceToBox(0, 0, 0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0)
                - Math.sqrt(3.0)) < 1e-9,
                "entity reach: diagonal corner distance = sqrt(3)");
        check(EntityContracts.inReach(3.0) && EntityContracts.inReach(0.5) && !EntityContracts.inReach(3.01),
                "entity reach: 3.0 in, 3.01 out (vanilla interaction reach)");
        String tooFar = EntityContracts.outOfRangeMessage("\"Cow\" (minecraft:cow)", 5.25);
        check(tooFar.contains("5.25") && tooFar.contains("3.00") && tooFar.contains("2.25"),
                "entity reach: out-of-range text names the distance, the reach and the shortfall");

        // --- entity.use monitor
        EntityContracts.UseMonitor useMonitor = new EntityContracts.UseMonitor(2000);
        check(useMonitor.tick(useView(true, false, false, true, true, null, 0))
                        .action() == EntityContracts.Action.USE,
                "entity.use monitor: presses on the first tick");
        check(EntityContracts.RESULT_SCREEN_OPENED.equals(
                        new EntityContracts.UseMonitor(2000)
                                .tick(useView(true, true, true, true, true, "success", 60)).result()),
                "entity.use monitor: a screen -> screen_opened (trade/horse)");
        check(EntityContracts.RESULT_NO_EFFECT.equals(
                        new EntityContracts.UseMonitor(2000)
                                .tick(useView(true, false, true, true, true, "pass", 60)).result()),
                "entity.use monitor: PASS -> no_effect");
        check(EntityContracts.RESULT_GONE.equals(
                        new EntityContracts.UseMonitor(2000)
                                .tick(useView(true, false, true, false, false, null, 60)).result()),
                "entity.use monitor: the entity vanished -> gone");
        EntityContracts.UseMonitor consumedUse = new EntityContracts.UseMonitor(2000);
        check(consumedUse.tick(useView(true, false, true, true, true, "consume", 100))
                        .action() == EntityContracts.Action.WAIT,
                "entity.use monitor: consumed but inside the screen grace window -> wait");
        check(EntityContracts.RESULT_USED.equals(consumedUse.tick(
                        useView(true, false, true, true, true, "consume",
                                EntityContracts.SCREEN_GRACE_MS)).result()),
                "entity.use monitor: consumed + grace elapsed, no screen -> used");
        check(EntityContracts.RESULT_NOT_IN_GAME.equals(
                        new EntityContracts.UseMonitor(2000)
                                .tick(useView(false, false, false, true, true, null, 10)).result()),
                "entity.use monitor: not in game -> not_in_game");

        // --- entity.attack pacer
        EntityContracts.EntityAttackParams threeHits = EntityContracts.entityAttackParams(
                json("{\"count\":3,\"interval_ms\":600}"));
        EntityContracts.AttackMonitor pacer = new EntityContracts.AttackMonitor(threeHits);
        check(pacer.tick(attackView(true, true, true, true, 1.0f, 0))
                        .action() == EntityContracts.Action.ATTACK,
                "entity.attack: charged and in reach -> attack");
        pacer.noteAttack(0);
        check(pacer.tick(attackView(true, true, true, true, 1.0f, 100))
                        .action() == EntityContracts.Action.WAIT,
                "entity.attack: waits out interval_ms between hits");
        check(pacer.tick(attackView(true, true, true, true, 1.0f, 600))
                        .action() == EntityContracts.Action.ATTACK,
                "entity.attack: interval elapsed -> next hit");
        pacer.noteAttack(600);
        pacer.noteAttack(1200);
        EntityContracts.Verdict finished = pacer.tick(attackView(true, true, true, true, 1.0f, 1800));
        check(finished.terminal() && EntityContracts.RESULT_HIT.equals(finished.result())
                        && pacer.hits() == 3 && pacer.miss() == 0,
                "entity.attack: count reached -> hit, 3 hits / 0 miss");
        EntityContracts.AttackMonitor dead = new EntityContracts.AttackMonitor(threeHits);
        dead.noteAttack(0);
        EntityContracts.Verdict died = dead.tick(attackView(true, true, false, true, 1.0f, 700));
        check(EntityContracts.RESULT_DEAD.equals(died.result()) && dead.hits() == 1 && dead.miss() == 2,
                "entity.attack: entity died mid-combo -> dead, remaining hits reported as miss");
        check(EntityContracts.RESULT_GONE.equals(new EntityContracts.AttackMonitor(threeHits)
                        .tick(attackView(true, false, false, true, 1.0f, 10)).result()),
                "entity.attack: entity removed -> gone");
        check(EntityContracts.RESULT_OUT_OF_RANGE.equals(new EntityContracts.AttackMonitor(threeHits)
                        .tick(attackView(true, true, true, false, 1.0f, 10)).result()),
                "entity.attack: target walked out of reach -> out_of_range");
        EntityContracts.AttackMonitor cooldown = new EntityContracts.AttackMonitor(threeHits);
        check(cooldown.tick(attackView(true, true, true, true, 0.5f, 0))
                        .action() == EntityContracts.Action.WAIT
                        && cooldown.cooldownWaitedMs() == EntityContracts.TICK_MS,
                "entity.attack: wait_cooldown holds the hit below 90% charge and counts the wait");
        EntityContracts.AttackMonitor noWait = new EntityContracts.AttackMonitor(
                EntityContracts.entityAttackParams(json("{\"wait_cooldown\":false}")));
        check(noWait.tick(attackView(true, true, true, true, 0.1f, 0))
                        .action() == EntityContracts.Action.ATTACK,
                "entity.attack: wait_cooldown=false attacks regardless of charge");

        // --- response shapes
        EntityContracts.EntityFacts facts = new EntityContracts.EntityFacts(
                "Cow", "minecraft:cow", "AAAA-1", 2.5);
        JsonObject useResult = EntityContracts.entityUseResult(EntityContracts.RESULT_USED, facts,
                UseContracts.INTERACTION_CONSUME, null, 320L, null);
        check(useResult.get("in_game").getAsBoolean()
                        && "used".equals(useResult.get("result").getAsString())
                        && "Cow".equals(useResult.getAsJsonObject("entity").get("name").getAsString())
                        && "minecraft:cow".equals(useResult.getAsJsonObject("entity").get("type").getAsString())
                        && useResult.getAsJsonObject("entity").get("distance").getAsDouble() == 2.5
                        && "consume".equals(useResult.get("interaction").getAsString())
                        && useResult.get("elapsed_ms").getAsLong() == 320L
                        && !useResult.has("screen_class"),
                "entity.use result: result/entity{name,type,uuid,distance}/interaction/elapsed_ms");
        JsonObject gone = EntityContracts.entityUseResult(EntityContracts.RESULT_GONE, null, null, null,
                5L, "no entity matched");
        check(gone.get("entity").isJsonNull() && "no entity matched".equals(gone.get("reason").getAsString()),
                "entity.use result: gone -> entity null + reason");
        JsonObject attackResult = EntityContracts.entityAttackResult(EntityContracts.RESULT_HIT, facts,
                3, 0, 150L, null);
        check(attackResult.get("hits").getAsInt() == 3 && attackResult.get("miss").getAsInt() == 0
                        && attackResult.get("cooldown_waited_ms").getAsLong() == 150L
                        && !attackResult.has("reason"),
                "entity.attack result: hits/miss/cooldown_waited_ms");
    }

    /** An entity.use UseView shorthand. */
    private static EntityContracts.UseView useView(boolean inGame, boolean screenOpen, boolean pressed,
                                                   boolean present, boolean alive, String interaction,
                                                   long elapsedMs) {
        return new EntityContracts.UseView(inGame, screenOpen, pressed, present, alive, interaction, elapsedMs);
    }

    /** An entity.attack AttackView shorthand. */
    private static EntityContracts.AttackView attackView(boolean inGame, boolean present, boolean alive,
                                                         boolean inReach, float strength, long elapsedMs) {
        return new EntityContracts.AttackView(inGame, present, alive, inReach, strength, elapsedMs);
    }

    // ------------------------------------------------------------------ M5.0 v1.6: inventory.select

    private static void inventorySelectContracts() throws Exception {
        InventoryContracts.SelectParams p = InventoryContracts.selectParams(json("{\"slot\":0}"));
        check(p.slot() == 0, "inventory.select: slot 0 accepted");
        check(InventoryContracts.selectParams(json("{\"slot\":8}")).slot() == 8,
                "inventory.select: slot 8 (last hotbar) accepted");
        expectInvalid(() -> InventoryContracts.selectParams(json("{}")),
                "inventory.select: missing slot rejected");
        expectInvalid(() -> InventoryContracts.selectParams(json("{\"slot\":9}")),
                "inventory.select: slot 9 rejected (schema max 8)");
        expectInvalid(() -> InventoryContracts.selectParams(json("{\"slot\":-1}")),
                "inventory.select: negative slot rejected");
        expectInvalid(() -> InventoryContracts.selectParams(json("{\"slot\":2.5}")),
                "inventory.select: fractional slot rejected");
        expectInvalid(() -> InventoryContracts.selectParams(json("{\"slot\":\"3\"}")),
                "inventory.select: string slot rejected");

        JsonObject withItem = InventoryContracts.selectResult(4, 2, "minecraft:bread", 3);
        check(withItem.get("in_game").getAsBoolean()
                        && withItem.get("selected").getAsInt() == 4
                        && withItem.get("previous").getAsInt() == 2
                        && "minecraft:bread".equals(withItem.getAsJsonObject("item").get("name").getAsString())
                        && withItem.getAsJsonObject("item").get("count").getAsInt() == 3,
                "inventory.select result: selected/previous/item{name,count}");
        JsonObject empty = InventoryContracts.selectResult(0, 7, null, 0);
        check(empty.get("item").isJsonNull(),
                "inventory.select result: empty slot -> item null");
    }

    // ------------------------------------------------------------------ M5.0 v1.6: getStats environment

    private static void statsEnvironment() {
        check(ToolContracts.dayTimeOf(0) == 0 && ToolContracts.dayTimeOf(23_999) == 23_999
                        && ToolContracts.dayTimeOf(24_000) == 0 && ToolContracts.dayTimeOf(60_001) == 12_001,
                "stats: day_time wraps every 24000 ticks");
        check(ToolContracts.dayNumberOf(0) == 0 && ToolContracts.dayNumberOf(23_999) == 0
                        && ToolContracts.dayNumberOf(24_000) == 1 && ToolContracts.dayNumberOf(60_001) == 2,
                "stats: day counter advances every 24000 ticks");
        check(ToolContracts.isDayTime(0) && ToolContracts.isDayTime(12_999)
                        && !ToolContracts.isDayTime(13_000) && !ToolContracts.isDayTime(23_999),
                "stats: is_day cutoff is tick 13000");
        check(ToolContracts.dayTimeOf(-1) == 23_999 && ToolContracts.dayNumberOf(-1) == -1,
                "stats: negative world time reduces with floorMod/floorDiv (no impossible day_time)");
    }

    // ------------------------------------------------------------------ protocol 1.10

    private static void blockContracts() throws Exception {
        BlockContracts.Params p = validParams(() -> BlockContracts.params(json("{\"x\":1,\"y\":64,\"z\":-3}")));
        check(p.x() == 1 && p.y() == 64 && p.z() == -3, "block.get: coordinates parsed");
        expectInvalid(() -> BlockContracts.params(json("{\"x\":1,\"y\":64}")),
                "block.get: missing coordinate rejected");
        expectInvalid(() -> BlockContracts.params(json("{\"x\":1.5,\"y\":64,\"z\":0}")),
                "block.get: fractional coordinate rejected");
        expectInvalid(() -> BlockContracts.params(json("{\"x\":30000001,\"y\":64,\"z\":0}")),
                "block.get: coordinate cap enforced");
        JsonObject ok = BlockContracts.result("ok", 1, 64, -3, -64, 319,
                "minecraft:oak_door", java.util.Map.of("open", "true", "facing", "north"));
        check(ok.get("loaded").getAsBoolean() && "ok".equals(ok.get("status").getAsString()),
                "block.get: loaded block has ok status");
        check("minecraft:oak_door".equals(ok.get("block").getAsString())
                        && ok.getAsJsonObject("state").get("open").getAsBoolean()
                        && "north".equals(ok.getAsJsonObject("state").get("facing").getAsString()),
                "block.get: registry id and complete state preserved");
        JsonObject unloaded = BlockContracts.result("unloaded", 1, 64, -3, -64, 319, null, java.util.Map.of());
        check(!unloaded.get("loaded").getAsBoolean() && unloaded.get("block").isJsonNull()
                        && unloaded.getAsJsonObject("state").size() == 0,
                "block.get: unloaded is neither air nor a fabricated state");
        JsonObject outside = BlockContracts.result("out_of_world", 1, -65, -3, -64, 319, null, java.util.Map.of());
        check("out_of_world".equals(outside.get("status").getAsString())
                        && outside.get("min_y").getAsInt() == -64 && outside.get("max_y").getAsInt() == 319,
                "block.get: out-of-world carries inclusive build bounds");
    }

    private static void containerContracts() throws Exception {
        ContainerContracts.ClickParams click = validParams(() -> ContainerContracts.clickParams(json("{\"slot\":3}")));
        check(click.slot() == 3 && click.button() == 0 && "pickup".equals(click.mode()),
                "container.clickSlot: defaults");
        click = validParams(() -> ContainerContracts.clickParams(
                json("{\"slot\":4,\"button\":1,\"mode\":\"quick_move\"}")));
        check(click.button() == 1 && "quick_move".equals(click.mode()),
                "container.clickSlot: explicit button and mode");
        expectInvalid(() -> ContainerContracts.clickParams(json("{}")),
                "container.clickSlot: missing menu slot rejected");
        expectInvalid(() -> ContainerContracts.clickParams(json("{\"slot\":0,\"mode\":\"teleport\"}")),
                "container.clickSlot: unknown mode rejected");
        expectInvalid(() -> ContainerContracts.clickParams(json("{\"slot\":0,\"button\":41}")),
                "container.clickSlot: button cap enforced");
        ContainerContracts.TransferParams transfer = validParams(() -> ContainerContracts.transferParams(
                json("{\"from_slot\":1,\"to_slot\":2}")));
        check(transfer.fromSlot() == 1 && transfer.toSlot() == 2 && transfer.count() == 0,
                "container.transfer: omitted count means whole stack");
        transfer = validParams(() -> ContainerContracts.transferParams(
                json("{\"from_slot\":1,\"to_slot\":2,\"count\":7}")));
        check(transfer.count() == 7, "container.transfer: partial count parsed");
        expectInvalid(() -> ContainerContracts.transferParams(json("{\"from_slot\":1}")),
                "container.transfer: destination required");
        expectInvalid(() -> ContainerContracts.transferParams(
                json("{\"from_slot\":1,\"to_slot\":2,\"count\":129}")),
                "container.transfer: iteration cap enforced");
        check(ContainerContracts.transferRefusal(new ContainerContracts.TransferFacts(
                false, false, false, 12, true, true, true, 64), 5) == null,
                "container.transfer: valid full or partial transfer passes precheck");
        check("no_op".equals(ContainerContracts.transferRefusal(new ContainerContracts.TransferFacts(
                true, false, false, 12, true, true, true, 64), 5)),
                "container.transfer: same-slot no-op precedes actions");
        check("carried_not_empty".equals(ContainerContracts.transferRefusal(new ContainerContracts.TransferFacts(
                false, true, false, 12, true, true, true, 64), 5)),
                "container.transfer: existing carried stack rejected");
        check("empty_source".equals(ContainerContracts.transferRefusal(new ContainerContracts.TransferFacts(
                false, false, true, 0, true, true, true, 64), 0)),
                "container.transfer: empty source rejected");
        check("invalid_count".equals(ContainerContracts.transferRefusal(new ContainerContracts.TransferFacts(
                false, false, false, 4, true, true, true, 64), 5)),
                "container.transfer: count above source rejected");
        check("slot_forbidden".equals(ContainerContracts.transferRefusal(new ContainerContracts.TransferFacts(
                false, false, false, 12, false, true, true, 64), 5)),
                "container.transfer: menu pickup rule rejected");
        check("incompatible".equals(ContainerContracts.transferRefusal(new ContainerContracts.TransferFacts(
                false, false, false, 12, true, true, false, 64), 5)),
                "container.transfer: different item/components rejected");
        check("not_enough_space".equals(ContainerContracts.transferRefusal(new ContainerContracts.TransferFacts(
                false, false, false, 12, true, true, true, 4), 5)),
                "container.transfer: destination capacity enforced before clicking");
        ContainerContracts.closeParams(json("{}"));
        expectInvalid(() -> {
            ContainerContracts.closeParams(json("{\"force\":true}"));
            return null;
        }, "container.close: empty params enforced");

        List<GuiContracts.SlotFact> before = Arrays.asList(
                new GuiContracts.SlotFact(0, 0, 0, 0, "container", "minecraft:stone", 12, null),
                new GuiContracts.SlotFact(1, 1, 0, 0, "hotbar", null, 0, null));
        List<GuiContracts.SlotFact> after = Arrays.asList(
                new GuiContracts.SlotFact(0, 0, 0, 0, "container", "minecraft:stone", 7, null),
                new GuiContracts.SlotFact(1, 1, 0, 0, "hotbar", "minecraft:stone", 5, null));
        JsonArray changed = ContainerContracts.changedSlots(before, after);
        check(changed.size() == 2 && changed.get(0).getAsJsonObject().get("slot").getAsInt() == 0
                        && changed.get(1).getAsJsonObject().getAsJsonObject("after").get("count").getAsInt() == 5,
                "container: source and destination changes are diffed");
        JsonObject clickResult = ContainerContracts.clickResult("clicked", 1, "ChestScreen", click,
                before, after, ContainerContracts.StackFact.EMPTY, ContainerContracts.StackFact.EMPTY);
        check("client_snapshot".equals(clickResult.get("evidence").getAsString())
                        && clickResult.getAsJsonArray("changed_slots").size() == 2,
                "container.clickSlot: evidence does not claim server confirmation");
        JsonObject transferResult = ContainerContracts.transferResult("transferred", 1, "ChestScreen",
                transfer, 5, 5, before, after, ContainerContracts.StackFact.EMPTY,
                ContainerContracts.StackFact.EMPTY);
        check(transferResult.get("requested").getAsInt() == 5 && transferResult.get("moved").getAsInt() == 5
                        && "transferred".equals(transferResult.get("result").getAsString()),
                "container.transfer: requested and observed movement are separate");
        JsonObject noContainer = ContainerContracts.stateResult("no_container", false);
        check(!noContainer.get("in_game").getAsBoolean()
                        && "none".equals(noContainer.get("evidence").getAsString()),
                "container: no-container result has no fabricated evidence");
        check("closed".equals(ContainerContracts.closeResult("closed", "ChestScreen").get("result").getAsString()),
                "container.close: closed result assembled");
    }

    private static void coreEventContracts() {
        check(CoreEventContracts.EVENT_TYPES.size() == 14
                        && CoreEventContracts.EVENT_TYPES.containsAll(List.of("block_update", "entity_spawn",
                        "entity_gone", "entity_hurt", "entity_death", "item_pickup", "player_join", "player_leave",
                        "window_open", "window_close", "slot_update", "held_item_changed", "connection_state",
                        "baritone_state")),
                "core events: all 14 protocol-1.11 event names are declared");
        boolean levels = true;
        for (String type : CoreEventContracts.EVENT_TYPES) {
            EventsContracts.EventLevel expected = List.of("entity_hurt", "entity_death", "connection_state").contains(type)
                    ? EventsContracts.EventLevel.WARNING : EventsContracts.EventLevel.INFO;
            levels &= CoreEventContracts.levelOf(type) == expected;
        }
        check(levels, "core events: levels are stable and connection-side filtering remains available");
        JsonObject block = CoreEventContracts.blockState("minecraft:oak_door", java.util.Map.of("open", "true", "half", "lower"));
        check("minecraft:oak_door".equals(block.get("block").getAsString())
                        && block.getAsJsonObject("state").get("open").getAsBoolean(),
                "core events: block updates preserve complete state properties");
        CoreEventContracts.StackFact granite = new CoreEventContracts.StackFact("minecraft:granite", 16);
        check(!CoreEventContracts.heldChanged(3, 3, granite, granite, true)
                        && CoreEventContracts.heldChanged(3, 4, granite, granite, true)
                        && CoreEventContracts.heldChanged(3, 3, granite, granite, false)
                        && CoreEventContracts.heldChanged(3, 3, granite,
                        new CoreEventContracts.StackFact("minecraft:granite", 15), true),
                "core events: held-item slot/components/count changes are distinct");
        JsonObject held = CoreEventContracts.heldData(3, 4, granite, granite);
        check(held.get("previous_slot").getAsInt() == 3 && held.get("slot").getAsInt() == 4
                        && "client_tick".equals(held.get("evidence").getAsString()),
                "core events: held-item payload identifies its tick evidence");
        JsonObject slot = CoreEventContracts.slotData(4, 17, "set_slot", 103, granite);
        check(slot.get("container_id").getAsInt() == 4 && slot.get("state_id").getAsInt() == 17
                        && slot.get("slot").getAsInt() == 103
                        && "server_packet".equals(slot.get("evidence").getAsString())
                        && slot.getAsJsonObject("stack").get("count").getAsInt() == 16,
                "core events: slot updates expose server revision and stack without claiming action success");
        check("connection_side".equals(CoreEventContracts.subscriptionCatalog().get("filtering").getAsString()),
                "core events: flow-policy ownership is explicitly connection-side");
    }

    private static void capabilitiesCatalog() throws Exception {
        JsonObject index = readSchema("index.json");
        JsonObject tools = index.getAsJsonObject("categories").getAsJsonObject("tools");
        check(Capabilities.PROTOCOL_VERSION.equals(index.get("protocol_version").getAsString()),
                "capabilities: index.json advertises the server protocol version");
        List<String> expected = Arrays.asList("inventory.select", "chat.history", "baritone.status",
                "useItem", "entity.use", "entity.attack", "baritone.goto", "baritone.follow", "baritone.settings",
                "baritone.stop", "baritone.tunnel", "block.get", "container.clickSlot", "container.transfer",
                "container.close", "inventory.drop");
        boolean allPresent = true;
        for (String name : expected) {
            allPresent &= tools.has(name);
        }
        check(allPresent, "capabilities: v1.6 through v1.12 tool names are in the catalogue");
        check(tools.size() == 33,
                "capabilities: the catalogue lists 33 tools");
        check(!tools.has("events.watch"),
                "capabilities: unimplemented events.watch is not advertised");
        int loaded = 0;
        for (String name : tools.keySet()) {
            if (readSchema(tools.get(name).getAsString()) != null) {
                loaded++;
            }
        }
        check(loaded == tools.size(),
                "capabilities: every referenced tool schema loads (" + loaded + "/" + tools.size() + ")");
        java.util.Set<String> registered = java.util.Set.of(
                "screenshot", "look", "lookAt", "dig", "getGuiState", "world.query", "getStats", "block.get",
                "input.mouseMove", "input.click", "input.key", "input.text", "chat.send", "inventory",
                "players", "place", "use", "inventory.select", "chat.history", "baritone.status",
                "baritone.goto", "baritone.follow", "baritone.settings", "baritone.stop", "baritone.tunnel", "useItem",
                "entity.use", "entity.attack", "events.subscribe", "capabilities/list", "container.clickSlot",
                "container.transfer", "container.close", "inventory.drop");
        check(Capabilities.registryProblems(registered).isEmpty(),
                "capabilities: all 33 advertised tools match registered handlers");
        java.util.Set<String> mismatched = new java.util.HashSet<>(registered);
        mismatched.remove("dig");
        mismatched.add("events.watch");
        check(Capabilities.registryProblems(mismatched).size() == 2,
                "capabilities: registry check detects missing and undeclared handlers");
        JsonObject useItem = readSchema("tools/useItem.json");
        check(useItem != null && useItem.getAsJsonObject("properties").has("times")
                        && useItem.getAsJsonObject("properties").has("interval_ms"),
                "capabilities: schema/tools/useItem.json resolves on the same path Capabilities loads");
    }

    /** One schema resource from the test classpath, falling back to the repo copy. */
    private static JsonObject readSchema(String relative) {
        try (java.io.InputStream in = SmokeMain.class.getResourceAsStream("/schema/" + relative)) {
            if (in != null) {
                return JsonParser.parseReader(new java.io.InputStreamReader(in,
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (IOException e) {
            // fall through to the repo copy
        }
        Path repo = Path.of("schema", relative);
        try {
            return JsonParser.parseString(Files.readString(repo)).getAsJsonObject();
        } catch (IOException e) {
            return null;
        }
    }

    /** True when the int array holds {@code value}. */
    private static boolean contains(JsonArray array, int value) {
        for (JsonElement element : array) {
            if (element.getAsInt() == value) {
                return true;
            }
        }
        return false;
    }

    /** Named stand-in for an AbstractWidget subclass (typeName smoke checks). */
    private static class FakeWidget {
    }    // ------------------------------------------------------------------ helpers

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static <T> T validParams(Supplier_<T> parse) throws Exception {
        return parse.get();
    }

    private static void expectInvalid(Supplier_<?> parse, String name) {
        try {
            parse.get();
            check(false, name + " (no exception thrown)");
        } catch (ToolContracts.InvalidParams expected) {
            check(true, name);
        } catch (Exception other) {
            check(false, name + " (wrong exception: " + other + ")");
        }
    }

    @FunctionalInterface
    private interface Supplier_<T> {
        T get() throws Exception;
    }

    private static void check(boolean condition, String name) {
        if (condition) {
            passed++;
            System.out.println("  ok    " + name);
        } else {
            failures.add(name);
            System.out.println("  FAIL  " + name);
        }
    }
}
