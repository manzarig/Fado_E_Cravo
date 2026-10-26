package com.manzarig.fadoecravo.client.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A scenario for {@link CriticHarness}: the settings of the test world and the list of steps to run
 * in it. This class only parses and checks JSON, with no Minecraft code, so it is unit tested; the
 * harness decides what each step does.
 *
 * <p>A scenario file is either a JSON array of steps, or an object
 * {@code {"settings": {...}, "steps": [...]}}. Every step is an object with exactly one action key
 * (see {@link Kind}) and optionally the modifiers that action understands. Mistakes (unknown keys,
 * wrong types, values out of range) are errors at load time that name the step, so a scenario
 * fails before the game has spent a minute loading a world.
 *
 * <p>The step reference is in {@code tools/critic/README.md}.
 */
public final class CriticScenario {
	/** The lowest and highest dial setting, as in {@code RadioBlockEntity}; copied here to keep this class free of the mod. */
	static final int MIN_KHZ = 530;
	static final int MAX_KHZ = 1600;
	static final int TICKS_PER_SECOND = 20;

	/** A scenario that cannot be used; the message says why and where. */
	public static final class ScenarioException extends Exception {
		private static final long serialVersionUID = 1L;

		public ScenarioException(String message) {
			super(message);
		}
	}

	/** The kinds of step, each named after the JSON key that selects it. */
	public enum Kind {
		WAIT("wait"),
		WAIT_S("wait_s"),
		CMD("cmd"),
		TP("tp"),
		OPEN("open"),
		CLOSE("close"),
		SCROLL("scroll"),
		KEY("key"),
		CLICK_DIAL_KHZ("click_dial_khz"),
		DRAG_DIAL_KHZ("drag_dial_khz"),
		CLICK_POWER("click_power"),
		CLICK("click"),
		SHOT("shot"),
		HIDE_HUD("hide_hud"),
		SNAPSHOT("snapshot"),
		MARK("mark"),
		NOTE("note"),
		QUIT("quit");

		private final String key;

		Kind(String key) {
			this.key = key;
		}

		/** The JSON key. */
		public String key() {
			return key;
		}

		static Kind ofKey(String key) {
			for (Kind kind : values()) {
				if (kind.key.equals(key)) {
					return kind;
				}
			}
			return null;
		}
	}

	/** Names accepted by the {@code key} step. */
	public static final Set<String> KEY_NAMES = Set.of("left", "right", "up", "down", "escape", "enter", "tab", "space", "e");

	/** Modifier keys allowed next to each action. */
	private static final Map<Kind, Set<String>> MODIFIERS = Map.of(
		Kind.SCROLL, Set.of("shift"),
		Kind.KEY, Set.of("shift", "repeat"),
		Kind.MARK, Set.of("ticks"));

	/** Keys any step may carry, ignored: a place for the author's comments. */
	private static final Set<String> COMMENT_KEYS = Set.of("comment", "//");

	/**
	 * One step.
	 *
	 * @param index   position in the list, from 0
	 * @param kind    what to do
	 * @param text    the string argument ({@code cmd}, {@code key}, {@code shot}, {@code snapshot}, {@code mark}, {@code note}), else null
	 * @param numbers the numeric arguments ({@code tp}, {@code open}, {@code click}, {@code drag_dial_khz}) else empty
	 * @param amount  the integer argument: ticks for {@code wait}/{@code wait_s}, notches for {@code scroll}, kHz for {@code click_dial_khz}
	 * @param repeat  how often to repeat a {@code key} step, at least 1
	 * @param shift   whether Shift is held for {@code scroll} and {@code key}
	 * @param flag    the boolean argument of {@code hide_hud}
	 * @param ticks   how long a {@code mark} beep lasts
	 * @param json    the step as written, for the log
	 */
	public record Step(int index, Kind kind, String text, List<Double> numbers, int amount, int repeat, boolean shift,
		boolean flag, int ticks, JsonObject json) {
	}

	/**
	 * What the test world is like and how the harness behaves.
	 *
	 * @param daylightCycle      the {@code doDaylightCycle} game rule (default true; a scenario that measures audio usually turns it off and sets the time itself)
	 * @param flat               a superflat world instead of the default generator
	 * @param seed               world seed
	 * @param gameMode           {@code creative}, {@code survival}, {@code adventure} or {@code spectator}
	 * @param lang               language code {@code run_critic.sh} puts in options.txt, e.g. {@code pt_pt}; the game itself ignores it
	 * @param chatInShots        whether screenshots show the chat (command feedback); off by default, the feedback is in the log anyway
	 * @param guiScale           GUI scale for options.txt (1 to 4; the game lowers it until the GUI is at least 320x240, so 1280x720 allows 3 at most)
	 * @param windowWidth        width of the game window and of the virtual screen, set by {@code run_critic.sh} (setting {@code "window": "WxH"})
	 * @param windowHeight       height of the game window
	 * @param settleTicks        how long the player must have been in the world before the first step
	 * @param timeoutMinutes     real minutes after which the harness gives up and quits
	 * @param snapshotEveryTicks interval of the periodic {@code snapshot} log lines, 0 for none
	 * @param gameRules          extra game rules, applied with {@code /gamerule} before the first step
	 */
	public record Settings(boolean daylightCycle, boolean flat, long seed, String gameMode, String lang,
		boolean chatInShots, int guiScale, int windowWidth, int windowHeight, int settleTicks, int timeoutMinutes, int snapshotEveryTicks,
		Map<String, String> gameRules) {
		public static Settings defaults() {
			return new Settings(true, true, 1940L, "creative", "en_us", false, 3, 1280, 720, 40, 12, 20, Map.of());
		}
	}

	private static final Set<String> SETTINGS_KEYS = Set.of("daylight_cycle", "flat", "seed", "game_mode", "lang",
		"chat_in_shots", "gui_scale", "window", "settle_ticks", "timeout_minutes", "snapshot_every_ticks", "gamerules");
	private static final Set<String> GAME_MODES = Set.of("creative", "survival", "adventure", "spectator");

	private final Settings settings;
	private final List<Step> steps;

	private CriticScenario(Settings settings, List<Step> steps) {
		this.settings = settings;
		this.steps = Collections.unmodifiableList(steps);
	}

	public Settings settings() {
		return settings;
	}

	public List<Step> steps() {
		return steps;
	}

	/** Parses and checks a scenario. */
	public static CriticScenario parse(String text) throws ScenarioException {
		JsonElement root;
		try {
			root = JsonParser.parseString(text);
		} catch (JsonParseException e) {
			throw new ScenarioException("not valid JSON: " + e.getMessage());
		}
		if (root.isJsonArray()) {
			return new CriticScenario(Settings.defaults(), parseSteps(root.getAsJsonArray()));
		}
		if (!root.isJsonObject()) {
			throw new ScenarioException("a scenario is a JSON array of steps or an object with \"settings\" and \"steps\"");
		}
		JsonObject object = root.getAsJsonObject();
		for (String key : object.keySet()) {
			if (!key.equals("settings") && !key.equals("steps") && !COMMENT_KEYS.contains(key)) {
				throw new ScenarioException("unknown top-level key \"" + key + "\" (expected \"settings\" and \"steps\")");
			}
		}
		if (!object.has("steps") || !object.get("steps").isJsonArray()) {
			throw new ScenarioException("the scenario object needs a \"steps\" array");
		}
		Settings settings = object.has("settings") ? parseSettings(object.get("settings")) : Settings.defaults();
		return new CriticScenario(settings, parseSteps(object.getAsJsonArray("steps")));
	}

	// ------------------------------------------------------------------ settings

	private static Settings parseSettings(JsonElement element) throws ScenarioException {
		if (!element.isJsonObject()) {
			throw new ScenarioException("\"settings\" must be an object");
		}
		JsonObject object = element.getAsJsonObject();
		Settings d = Settings.defaults();
		for (String key : object.keySet()) {
			if (!SETTINGS_KEYS.contains(key) && !COMMENT_KEYS.contains(key)) {
				throw new ScenarioException("settings: unknown key \"" + key + "\"; known: " + new java.util.TreeSet<>(SETTINGS_KEYS));
			}
		}
		String gameMode = settingString(object, "game_mode", d.gameMode()).toLowerCase(Locale.ROOT);
		if (!GAME_MODES.contains(gameMode)) {
			throw new ScenarioException("settings.game_mode must be one of " + new java.util.TreeSet<>(GAME_MODES) + ": " + gameMode);
		}
		String lang = settingString(object, "lang", d.lang()).toLowerCase(Locale.ROOT);
		if (!lang.matches("[a-z]{2,3}_[a-z]{2,3}")) {
			throw new ScenarioException("settings.lang must look like en_us or pt_pt: " + lang);
		}
		int windowWidth = d.windowWidth();
		int windowHeight = d.windowHeight();
		if (object.has("window")) {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,4})x(\\d{3,4})")
				.matcher(settingString(object, "window", ""));
			if (!m.matches()) {
				throw new ScenarioException("settings.window must look like 1280x720 (width x height)");
			}
			windowWidth = Integer.parseInt(m.group(1));
			windowHeight = Integer.parseInt(m.group(2));
			if (windowWidth < 320 || windowWidth > 3840 || windowHeight < 240 || windowHeight > 2160) {
				throw new ScenarioException("settings.window must be from 320x240 to 3840x2160");
			}
		}
		Map<String, String> rules = new LinkedHashMap<>();
		if (object.has("gamerules")) {
			if (!object.get("gamerules").isJsonObject()) {
				throw new ScenarioException("settings.gamerules must be an object such as {\"doFireTick\": false}");
			}
			for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("gamerules").entrySet()) {
				if (!entry.getKey().matches("[A-Za-z]+") || !entry.getValue().isJsonPrimitive()) {
					throw new ScenarioException("settings.gamerules: bad entry \"" + entry.getKey() + "\"");
				}
				rules.put(entry.getKey(), entry.getValue().getAsString());
			}
		}
		return new Settings(
			settingBoolean(object, "daylight_cycle", d.daylightCycle()),
			settingBoolean(object, "flat", d.flat()),
			object.has("seed") ? settingLong(object, "seed") : d.seed(),
			gameMode,
			lang,
			settingBoolean(object, "chat_in_shots", d.chatInShots()),
			settingInt(object, "gui_scale", d.guiScale(), 1, 4),
			windowWidth,
			windowHeight,
			settingInt(object, "settle_ticks", d.settleTicks(), 0, 20 * 60),
			settingInt(object, "timeout_minutes", d.timeoutMinutes(), 1, 240),
			settingInt(object, "snapshot_every_ticks", d.snapshotEveryTicks(), 0, 20 * 60),
			Collections.unmodifiableMap(rules));
	}

	private static String settingString(JsonObject object, String key, String fallback) throws ScenarioException {
		if (!object.has(key)) {
			return fallback;
		}
		JsonElement e = object.get(key);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			throw new ScenarioException("settings." + key + " must be a string");
		}
		return e.getAsString();
	}

	private static boolean settingBoolean(JsonObject object, String key, boolean fallback) throws ScenarioException {
		if (!object.has(key)) {
			return fallback;
		}
		JsonElement e = object.get(key);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
			throw new ScenarioException("settings." + key + " must be true or false");
		}
		return e.getAsBoolean();
	}

	private static long settingLong(JsonObject object, String key) throws ScenarioException {
		Long value = asLong(object.get(key));
		if (value == null) {
			throw new ScenarioException("settings." + key + " must be a whole number");
		}
		return value;
	}

	private static int settingInt(JsonObject object, String key, int fallback, int min, int max) throws ScenarioException {
		if (!object.has(key)) {
			return fallback;
		}
		Long value = asLong(object.get(key));
		if (value == null || value < min || value > max) {
			throw new ScenarioException("settings." + key + " must be a whole number from " + min + " to " + max);
		}
		return (int) (long) value;
	}

	// ------------------------------------------------------------------ steps

	private static List<Step> parseSteps(JsonArray array) throws ScenarioException {
		List<Step> steps = new ArrayList<>();
		for (int i = 0; i < array.size(); i++) {
			steps.add(parseStep(i, array.get(i)));
		}
		return steps;
	}

	private static Step parseStep(int index, JsonElement element) throws ScenarioException {
		String where = "step " + index + ": ";
		if (!element.isJsonObject()) {
			throw new ScenarioException(where + "a step must be an object such as {\"wait\": 20}, got " + element);
		}
		JsonObject json = element.getAsJsonObject();
		Kind kind = null;
		for (String key : json.keySet()) {
			Kind candidate = Kind.ofKey(key);
			if (candidate == null) {
				continue;
			}
			if (kind != null) {
				throw new ScenarioException(where + "two actions (\"" + kind.key() + "\" and \"" + key + "\") in one step: " + json);
			}
			kind = candidate;
		}
		if (kind == null) {
			throw new ScenarioException(where + "no known action in " + json + "; actions: " + actionNames());
		}
		Set<String> allowed = MODIFIERS.getOrDefault(kind, Set.of());
		for (String key : json.keySet()) {
			if (!key.equals(kind.key()) && !allowed.contains(key) && !COMMENT_KEYS.contains(key)) {
				throw new ScenarioException(where + "\"" + key + "\" is not allowed with \"" + kind.key() + "\""
					+ (allowed.isEmpty() ? "" : " (modifiers: " + new java.util.TreeSet<>(allowed) + ")"));
			}
		}
		JsonElement value = json.get(kind.key());
		where = where + "\"" + kind.key() + "\": ";

		String text = null;
		List<Double> numbers = List.of();
		int amount = 0;
		int repeat = 1;
		boolean flag = false;
		int ticks = 10;
		boolean shift = modifierBoolean(json, "shift", where);

		switch (kind) {
			case WAIT -> amount = ranged(value, 0, 20 * 60 * 60, where + "ticks");
			case WAIT_S -> {
				double seconds = number(value, where);
				if (seconds < 0 || seconds > 3600) {
					throw new ScenarioException(where + "seconds must be from 0 to 3600");
				}
				amount = (int) Math.round(seconds * TICKS_PER_SECOND);
			}
			case CMD -> {
				text = string(value, where).strip();
				if (text.startsWith("/")) {
					text = text.substring(1);
				}
				if (text.isEmpty()) {
					throw new ScenarioException(where + "the command is empty");
				}
			}
			case TP -> {
				numbers = numbers(value, 3, 5, where);
				if (numbers.size() == 4) {
					throw new ScenarioException(where + "give [x, y, z] or [x, y, z, yaw, pitch]");
				}
			}
			case OPEN -> {
				numbers = numbers(value, 3, 3, where);
				for (double n : numbers) {
					if (n != Math.rint(n)) {
						throw new ScenarioException(where + "a block position takes whole numbers: " + numbers);
					}
				}
			}
			case CLOSE, CLICK_POWER, QUIT -> {
				if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean() || !value.getAsBoolean()) {
					throw new ScenarioException(where + "write true");
				}
			}
			case SCROLL -> {
				amount = ranged(value, -2000, 2000, where + "notches");
				if (amount == 0) {
					throw new ScenarioException(where + "0 notches does nothing");
				}
			}
			case KEY -> {
				text = string(value, where).toLowerCase(Locale.ROOT);
				if (!KEY_NAMES.contains(text)) {
					throw new ScenarioException(where + "unknown key \"" + text + "\"; keys: " + new java.util.TreeSet<>(KEY_NAMES));
				}
				if (json.has("repeat")) {
					repeat = ranged(json.get("repeat"), 1, 2000, where + "repeat");
				}
			}
			case CLICK_DIAL_KHZ -> amount = ranged(value, MIN_KHZ, MAX_KHZ, where + "kHz");
			case DRAG_DIAL_KHZ -> {
				numbers = numbers(value, 2, 2, where);
				for (double n : numbers) {
					if (n != Math.rint(n) || n < MIN_KHZ || n > MAX_KHZ) {
						throw new ScenarioException(where + "[from, to] in whole kHz from " + MIN_KHZ + " to " + MAX_KHZ + ": " + numbers);
					}
				}
			}
			case CLICK -> numbers = numbers(value, 2, 2, where);
			case SHOT -> {
				text = string(value, where);
				if (!text.matches("[A-Za-z0-9_-]{1,60}")) {
					throw new ScenarioException(where + "the shot name is letters, digits, _ and - only (it becomes a file name): " + text);
				}
			}
			case HIDE_HUD -> {
				if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
					throw new ScenarioException(where + "write true or false");
				}
				flag = value.getAsBoolean();
			}
			case SNAPSHOT, MARK, NOTE -> {
				text = string(value, where);
				if (kind == Kind.MARK && json.has("ticks")) {
					ticks = ranged(json.get("ticks"), 1, 200, where + "ticks");
				}
			}
			default -> throw new IllegalStateException("unhandled kind " + kind);
		}
		return new Step(index, kind, text, List.copyOf(numbers), amount, repeat, shift, flag, ticks, json);
	}

	private static String actionNames() {
		List<String> names = new ArrayList<>();
		for (Kind kind : Kind.values()) {
			names.add(kind.key());
		}
		return String.join(", ", names);
	}

	private static boolean modifierBoolean(JsonObject json, String key, String where) throws ScenarioException {
		if (!json.has(key)) {
			return false;
		}
		JsonElement e = json.get(key);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
			throw new ScenarioException(where + "\"" + key + "\" must be true or false");
		}
		return e.getAsBoolean();
	}

	private static String string(JsonElement e, String where) throws ScenarioException {
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			throw new ScenarioException(where + "expected a string");
		}
		return e.getAsString();
	}

	private static double number(JsonElement e, String where) throws ScenarioException {
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw new ScenarioException(where + "expected a number");
		}
		return e.getAsDouble();
	}

	private static int ranged(JsonElement e, int min, int max, String what) throws ScenarioException {
		Long value = asLong(e);
		if (value == null || value < min || value > max) {
			throw new ScenarioException(what + " must be a whole number from " + min + " to " + max + ", got " + e);
		}
		return (int) (long) value;
	}

	private static List<Double> numbers(JsonElement e, int min, int max, String where) throws ScenarioException {
		if (!e.isJsonArray() || e.getAsJsonArray().size() < min || e.getAsJsonArray().size() > max) {
			throw new ScenarioException(where + "expected an array of " + (min == max ? Integer.toString(min) : min + " to " + max) + " numbers");
		}
		List<Double> list = new ArrayList<>();
		for (JsonElement item : e.getAsJsonArray()) {
			list.add(number(item, where));
		}
		return list;
	}

	/** The element as a whole number, or null when it is not one. */
	private static Long asLong(JsonElement e) {
		if (e == null || !e.isJsonPrimitive()) {
			return null;
		}
		JsonPrimitive p = e.getAsJsonPrimitive();
		if (!p.isNumber()) {
			return null;
		}
		double d = p.getAsDouble();
		if (d != Math.rint(d) || Double.isInfinite(d)) {
			return null;
		}
		return p.getAsLong();
	}
}
