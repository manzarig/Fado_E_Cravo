package com.manzarig.fadoecravo.client.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.manzarig.fadoecravo.RadioClientBridge;
import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;
import com.manzarig.fadoecravo.client.audio.RadioAudioManager;
import com.manzarig.fadoecravo.client.audio.RadioDebugState;
import com.manzarig.fadoecravo.client.gui.DialGeometry;
import com.manzarig.fadoecravo.client.gui.RadioLayout;
import com.manzarig.fadoecravo.client.gui.RadioScreen;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SubtitleOverlay;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * A development-only robot that plays the game for a "critic": it creates a world, walks through the
 * steps of a scenario file and writes down what it did and what the radios were doing, so that an
 * outside agent can judge the mod from screenshots, a recording of the real audio mix and a log,
 * without a person at the keyboard.
 *
 * <p><b>Enabled only</b> in a development environment, and only when the system property
 * {@value #PROPERTY_SCENARIO} names a scenario JSON ({@link CriticScenario}). {@link #register()}
 * does nothing otherwise, so shipping builds are never affected. {@code tools/critic/run_critic.sh}
 * sets everything up; the step reference is in {@code tools/critic/README.md}.
 *
 * <p><b>Output</b> goes to the directory named by {@value #PROPERTY_OUT} (default
 * {@code build/critic/<scenario name>}): {@code playback.log} (one JSON object per line),
 * {@code shots/NN_name.png}, and a copy of the scenario. The audio recording is made by OpenAL
 * itself (its "Wave File Writer" backend, switched on by the script's environment), not by this class.
 *
 * <p><b>How it runs.</b> Everything happens on the client thread in the end-of-tick event. First it
 * waits on the title screen, creates a fresh world (superflat by default, creative, cheats on, fixed
 * seed, no mobs, no weather, no insomnia), waits until the player has been in the world for a
 * while, and then runs the steps in order. A step either finishes within the tick or makes the
 * runner wait for later ticks ({@code wait}, {@code shot}, {@code quit}). A screenshot is taken only
 * after two frames have been drawn since the step before it, so it shows the state that step caused.
 */
public final class CriticHarness {
	/** System property: absolute path of the scenario JSON. */
	public static final String PROPERTY_SCENARIO = "fadoecravo.critic";
	/** System property: absolute path of the output directory. */
	public static final String PROPERTY_OUT = "fadoecravo.critic.out";

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String WORLD_ID = "fadoecravo_critic_world";
	private static final ResourceLocation MARK_SOUND = ResourceLocation.fromNamespaceAndPath("fadoecravo", "test.seek_test");
	private static final float MARK_VOLUME = 0.6f;
	/** Frames that must be drawn after a step before a screenshot shows its effect. */
	private static final int FRAMES_BEFORE_SHOT = 2;
	/** Ticks after which a screenshot is taken even if no frame was drawn (a window that does not render). */
	private static final int SHOT_GIVE_UP_TICKS = 200;
	/** Ticks on the title screen before the world is created, for the resource reload to settle. */
	private static final int TITLE_SETTLE_TICKS = 30;
	/** Ticks the world may take to appear before the run is abandoned. */
	private static final int LOAD_GIVE_UP_TICKS = 20 * 120;
	/** Real seconds between asking the game to stop and halting the JVM if it is stuck. */
	private static final int HALT_AFTER_SECONDS = 90;

	private enum Phase {
		TITLE, LOADING, SETTLING, RUNNING, QUITTING, DONE
	}

	private final CriticScenario scenario;
	private final Path outDir;
	private final Path shotsDir;
	private final CriticLog log;
	private final long startMillis = System.currentTimeMillis();

	private Phase phase = Phase.TITLE;
	private long tick;
	private long phaseStartTick;
	private int stepIndex;
	private long waitUntilTick;
	private int shotCount;
	private int errorCount;
	private int stepsRun;
	private int titleTicks;
	private String pendingShot;
	private long pendingShotTick;
	private long pendingShotFrame;
	private long frames;
	private String lastScreen = "none";
	private SimpleSoundInstance markSound;
	private long markStopTick;
	private boolean quitRequested;
	private String lastCommand;

	private CriticHarness(CriticScenario scenario, Path outDir, CriticLog log) {
		this.scenario = scenario;
		this.outDir = outDir;
		this.shotsDir = outDir.resolve("shots");
		this.log = log;
	}

	// ------------------------------------------------------------------ registration

	/** Starts the harness if this is a development environment and a scenario was given; does nothing otherwise. */
	public static void register() {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return;
		}
		String scenarioPath = System.getProperty(PROPERTY_SCENARIO);
		if (scenarioPath == null || scenarioPath.isBlank()) {
			return;
		}
		try {
			Path scenarioFile = Paths.get(scenarioPath).toAbsolutePath();
			String outProperty = System.getProperty(PROPERTY_OUT);
			Path outDir = outProperty != null && !outProperty.isBlank()
				? Paths.get(outProperty).toAbsolutePath()
				: FabricLoader.getInstance().getGameDir().toAbsolutePath().resolveSibling("build").resolve("critic")
					.resolve(baseName(scenarioFile));
			Files.createDirectories(outDir.resolve("shots"));
			CriticLog log = new CriticLog(outDir.resolve("playback.log"));
			CriticHarness harness;
			try {
				String text = Files.readString(scenarioFile, StandardCharsets.UTF_8);
				Files.writeString(outDir.resolve("scenario.json"), text, StandardCharsets.UTF_8);
				CriticScenario scenario = CriticScenario.parse(text);
				harness = new CriticHarness(scenario, outDir, log);
			} catch (IOException | CriticScenario.ScenarioException e) {
				JsonObject error = new JsonObject();
				error.addProperty("type", "fatal");
				error.addProperty("wall_ms", System.currentTimeMillis());
				error.addProperty("message", "cannot load scenario " + scenarioFile + ": " + e.getMessage());
				log.write(error);
				log.close();
				LOGGER.error("[critic] cannot load scenario {}: {}", scenarioFile, e.getMessage());
				// Stop the game as soon as the client is up, instead of sitting on the title screen.
				ClientTickEvents.END_CLIENT_TICK.register(Minecraft::stop);
				return;
			}
			harness.start();
		} catch (IOException e) {
			LOGGER.error("[critic] cannot prepare the output directory", e);
		}
	}

	private static String baseName(Path file) {
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	private void start() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		WorldRenderEvents.START.register(context -> frames++);
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChat("game", message, overlay));
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> onChat("chat", message, false));
		JsonObject o = event("harness_start");
		o.addProperty("scenario_steps", scenario.steps().size());
		o.addProperty("out", outDir.toString());
		o.addProperty("flat", scenario.settings().flat());
		o.addProperty("daylight_cycle", scenario.settings().daylightCycle());
		emit(o);
		LOGGER.info("[critic] harness enabled: {} steps, output in {}", scenario.steps().size(), outDir);
	}

	// ------------------------------------------------------------------ logging

	/** A log line with the fields every line has. */
	private JsonObject event(String type) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		o.addProperty("wall_ms", System.currentTimeMillis());
		o.addProperty("tick", tick);
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null) {
			o.addProperty("game_time", level.getGameTime());
			o.addProperty("day_time", level.getDayTime());
		}
		return o;
	}

	private void emit(JsonObject line) {
		log.write(line);
	}

	private void note(String type, String message) {
		JsonObject o = event(type);
		o.addProperty("message", message);
		emit(o);
	}

	private void warn(String message) {
		errorCount++;
		LOGGER.warn("[critic] {}", message);
		note("warn", message);
	}

	private void onChat(String channel, Component message, boolean overlay) {
		JsonObject o = event("chat");
		o.addProperty("channel", channel);
		o.addProperty("overlay", overlay);
		o.addProperty("text", message.getString());
		// Vanilla paints a failed command red.
		TextColor color = message.getStyle().getColor();
		o.addProperty("red", color != null && color.getValue() == ChatFormatting.RED.getColor());
		if (lastCommand != null) {
			o.addProperty("after_command", lastCommand);
		}
		emit(o);
	}

	private JsonObject snapshotJson(Minecraft mc, String label) {
		JsonObject o = event("snapshot");
		o.addProperty("label", label);
		JsonArray radios = new JsonArray();
		for (RadioDebugState state : RadioAudioManager.snapshot()) {
			JsonObject r = new JsonObject();
			r.add("pos", intArray(state.pos().getX(), state.pos().getY(), state.pos().getZ()));
			r.addProperty("khz", state.frequencyKhz());
			r.addProperty("station", state.stationId());
			r.addProperty("signal", round(state.signal(), 3));
			r.addProperty("clip", state.clipId());
			r.addProperty("kind", state.kind());
			r.addProperty("offset_s", round(state.offsetSeconds(), 2));
			r.addProperty("programme_volume", round(state.programmeVolume(), 3));
			r.addProperty("static_volume", round(state.staticVolume(), 3));
			r.addProperty("breaking", state.breaking());
			r.addProperty("crowd", state.crowd());
			radios.add(r);
		}
		o.add("radios", radios);
		LocalPlayer player = mc.player;
		if (player != null) {
			JsonObject p = new JsonObject();
			p.addProperty("x", round(player.getX(), 2));
			p.addProperty("y", round(player.getY(), 2));
			p.addProperty("z", round(player.getZ(), 2));
			p.addProperty("yaw", round(player.getYRot(), 1));
			p.addProperty("pitch", round(player.getXRot(), 1));
			o.add("player", p);
		}
		o.addProperty("screen", screenName(mc));
		o.addProperty("hud_hidden", mc.options.hideGui);
		o.addProperty("fps", mc.getFps());
		JsonArray subtitles = new JsonArray();
		for (String text : subtitleTexts(mc)) {
			subtitles.add(text);
		}
		o.add("subtitles", subtitles);
		return o;
	}

	private static JsonArray intArray(int... values) {
		JsonArray a = new JsonArray();
		for (int v : values) {
			a.add(v);
		}
		return a;
	}

	private static double round(double value, int places) {
		double scale = Math.pow(10, places);
		return Math.round(value * scale) / scale;
	}

	private static String screenName(Minecraft mc) {
		return mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
	}

	// ------------------------------------------------------------------ subtitles (reflection)

	private static java.lang.reflect.Field subtitleOverlayField;
	private static java.lang.reflect.Field audibleSubtitlesField;
	private static java.lang.reflect.Method subtitleTextMethod;
	private static boolean subtitlesUnavailable;

	/**
	 * The subtitles the overlay drew in the last frame, read with reflection from vanilla's private
	 * {@code SubtitleOverlay.audibleSubtitles}; empty when none are shown, when the HUD is hidden
	 * or if the fields cannot be found.
	 */
	private static List<String> subtitleTexts(Minecraft mc) {
		if (subtitlesUnavailable) {
			return List.of();
		}
		try {
			if (subtitleOverlayField == null) {
				subtitleOverlayField = Gui.class.getDeclaredField("subtitleOverlay");
				subtitleOverlayField.setAccessible(true);
				audibleSubtitlesField = SubtitleOverlay.class.getDeclaredField("audibleSubtitles");
				audibleSubtitlesField.setAccessible(true);
				subtitleTextMethod = Class.forName("net.minecraft.client.gui.components.SubtitleOverlay$Subtitle")
					.getDeclaredMethod("getText");
				subtitleTextMethod.setAccessible(true);
			}
			Object overlay = subtitleOverlayField.get(mc.gui);
			List<?> audible = new ArrayList<>((List<?>) audibleSubtitlesField.get(overlay));
			List<String> texts = new ArrayList<>();
			for (Object subtitle : audible) {
				texts.add(((Component) subtitleTextMethod.invoke(subtitle)).getString());
			}
			return texts;
		} catch (ReflectiveOperationException | RuntimeException e) {
			subtitlesUnavailable = true;
			LOGGER.warn("[critic] cannot read the subtitle overlay: {}", e.toString());
			return List.of();
		}
	}

	// ------------------------------------------------------------------ the tick

	private void onTick(Minecraft mc) {
		tick++;
		try {
			trackScreen(mc);
			if (markSound != null && tick >= markStopTick) {
				mc.getSoundManager().stop(markSound);
				markSound = null;
			}
			if (phase != Phase.QUITTING && phase != Phase.DONE
				&& System.currentTimeMillis() - startMillis > scenario.settings().timeoutMinutes() * 60_000L) {
				warn("timeout of " + scenario.settings().timeoutMinutes() + " minutes reached; stopping");
				beginQuit(mc, "timeout");
			}
			switch (phase) {
				case TITLE -> tickTitle(mc);
				case LOADING -> tickLoading(mc);
				case SETTLING -> tickSettling(mc);
				case RUNNING -> tickRunning(mc);
				case QUITTING -> tickQuitting(mc);
				case DONE -> {
				}
			}
			int every = scenario.settings().snapshotEveryTicks();
			if (every > 0 && tick % every == 0 && mc.level != null && mc.player != null
				&& (phase == Phase.SETTLING || phase == Phase.RUNNING)) {
				emit(snapshotJson(mc, "periodic"));
			}
		} catch (RuntimeException | Error e) {
			LOGGER.error("[critic] harness failure", e);
			JsonObject o = event("fatal");
			o.addProperty("message", e.toString());
			emit(o);
			errorCount++;
			if (phase != Phase.QUITTING && phase != Phase.DONE) {
				try {
					beginQuit(mc, "exception");
				} catch (RuntimeException second) {
					mc.stop();
				}
			}
		}
	}

	private void enter(Phase next) {
		phase = next;
		phaseStartTick = tick;
		note("phase", next.name().toLowerCase(Locale.ROOT));
	}

	/** Logs a line whenever the open screen changes, so a screen that closes itself shows up in the log. */
	private void trackScreen(Minecraft mc) {
		String now = screenName(mc);
		if (!now.equals(lastScreen)) {
			JsonObject o = event("screen");
			o.addProperty("from", lastScreen);
			o.addProperty("to", now);
			emit(o);
			lastScreen = now;
		}
	}

	// ------------------------------------------------------------------ startup

	private void tickTitle(Minecraft mc) {
		if (mc.level != null) {
			// Somebody (a previous run's autosave, a quick-play argument) got us into a world already.
			enter(Phase.SETTLING);
			return;
		}
		if (mc.getOverlay() != null) {
			return; // still loading resources
		}
		Screen screen = mc.screen;
		if (screen instanceof AccessibilityOnboardingScreen) {
			note("dismiss", "AccessibilityOnboardingScreen");
			mc.setScreen(new TitleScreen());
			titleTicks = 0;
			return;
		}
		if (!(screen instanceof TitleScreen)) {
			if (screen != null && ++titleTicks > 60) {
				note("dismiss", screen.getClass().getName());
				mc.setScreen(null); // with no world open the game turns null into the title screen
				titleTicks = 0;
			}
			return;
		}
		if (++titleTicks < TITLE_SETTLE_TICKS) {
			return;
		}
		createWorld(mc);
		enter(Phase.LOADING);
	}

	private void createWorld(Minecraft mc) {
		CriticScenario.Settings s = scenario.settings();
		LevelStorageSource source = mc.getLevelSource();
		try {
			if (source.levelExists(WORLD_ID)) {
				try (LevelStorageSource.LevelStorageAccess access = source.createAccess(WORLD_ID)) {
					access.deleteLevel();
				}
			}
		} catch (IOException e) {
			warn("cannot delete the old world " + WORLD_ID + ": " + e);
		}
		GameRules rules = new GameRules();
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		rules.getRule(GameRules.RULE_DOINSOMNIA).set(false, null);
		rules.getRule(GameRules.RULE_DAYLIGHT).set(s.daylightCycle(), null);
		GameType gameType = switch (s.gameMode()) {
			case "survival" -> GameType.SURVIVAL;
			case "adventure" -> GameType.ADVENTURE;
			case "spectator" -> GameType.SPECTATOR;
			default -> GameType.CREATIVE;
		};
		LevelSettings settings = new LevelSettings(WORLD_ID, gameType, false, Difficulty.PEACEFUL, true, rules,
			WorldDataConfiguration.DEFAULT);
		WorldOptions options = new WorldOptions(s.seed(), false, false);
		Function<RegistryAccess, WorldDimensions> dimensions = s.flat()
			? access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value()
				.createWorldDimensions()
			: WorldPresets::createNormalWorldDimensions;
		JsonObject o = event("create_world");
		o.addProperty("id", WORLD_ID);
		o.addProperty("seed", s.seed());
		o.addProperty("flat", s.flat());
		o.addProperty("game_mode", s.gameMode());
		o.addProperty("daylight_cycle", s.daylightCycle());
		emit(o);
		mc.createWorldOpenFlows().createFreshLevel(WORLD_ID, settings, options, dimensions, mc.screen);
	}

	private void tickLoading(Minecraft mc) {
		if (mc.level != null && mc.player != null) {
			enter(Phase.SETTLING);
			return;
		}
		if (tick - phaseStartTick > LOAD_GIVE_UP_TICKS) {
			warn("the world did not load within " + LOAD_GIVE_UP_TICKS / 20 + " s");
			beginQuit(mc, "load timeout");
		}
	}

	private void tickSettling(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (mc.level == null || player == null) {
			if (tick - phaseStartTick > LOAD_GIVE_UP_TICKS) {
				warn("the player never appeared");
				beginQuit(mc, "load timeout");
			}
			return;
		}
		if (mc.screen != null && tick - phaseStartTick > 200) {
			note("dismiss", mc.screen.getClass().getName());
			mc.setScreen(null);
		}
		if (player.tickCount < scenario.settings().settleTicks() || mc.screen != null || mc.getConnection() == null) {
			return;
		}
		JsonObject o = event("world_ready");
		o.addProperty("player_ticks", player.tickCount);
		o.addProperty("x", round(player.getX(), 2));
		o.addProperty("y", round(player.getY(), 2));
		o.addProperty("z", round(player.getZ(), 2));
		o.addProperty("window", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
		o.addProperty("gui_scaled", mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight());
		o.addProperty("gui_scale", mc.getWindow().getGuiScale());
		o.addProperty("lang", mc.getLanguageManager().getSelected());
		o.addProperty("subtitles_option", mc.options.showSubtitles().get());
		if (mc.getSingleplayerServer() != null) {
			GameRules serverRules = mc.getSingleplayerServer().getGameRules();
			JsonObject r = new JsonObject();
			r.addProperty("doMobSpawning", serverRules.getBoolean(GameRules.RULE_DOMOBSPAWNING));
			r.addProperty("doWeatherCycle", serverRules.getBoolean(GameRules.RULE_WEATHER_CYCLE));
			r.addProperty("doDaylightCycle", serverRules.getBoolean(GameRules.RULE_DAYLIGHT));
			r.addProperty("doInsomnia", serverRules.getBoolean(GameRules.RULE_DOINSOMNIA));
			o.add("gamerules", r);
		}
		emit(o);
		scenario.settings().gameRules().forEach((name, value) -> mc.getConnection().sendCommand("gamerule " + name + " " + value));
		enter(Phase.RUNNING);
	}

	// ------------------------------------------------------------------ running the steps

	private void tickRunning(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			warn("the world went away in the middle of the scenario");
			beginQuit(mc, "world lost");
			return;
		}
		if (pendingShot != null) {
			boolean framesDrawn = frames >= pendingShotFrame + FRAMES_BEFORE_SHOT;
			boolean gaveUp = tick - pendingShotTick > SHOT_GIVE_UP_TICKS;
			if (!framesDrawn && !gaveUp) {
				return;
			}
			if (gaveUp && !framesDrawn) {
				warn("no frame was drawn for " + SHOT_GIVE_UP_TICKS + " ticks; taking the shot anyway");
			}
			takeShot(mc, pendingShot);
			pendingShot = null;
		}
		if (tick < waitUntilTick) {
			return;
		}
		List<CriticScenario.Step> steps = scenario.steps();
		while (stepIndex < steps.size()) {
			CriticScenario.Step step = steps.get(stepIndex++);
			JsonObject o = event("step");
			o.addProperty("index", step.index());
			o.add("step", step.json());
			emit(o);
			stepsRun++;
			boolean yield;
			try {
				yield = execute(mc, step);
			} catch (RuntimeException e) {
				warn("step " + step.index() + " (" + step.kind().key() + ") failed: " + e);
				LOGGER.warn("[critic] step failure", e);
				yield = false;
			}
			if (yield || phase != Phase.RUNNING) {
				return;
			}
		}
		note("end_of_steps", "the scenario ended without a quit step");
		beginQuit(mc, "end of steps");
	}

	/** Runs one step. Returns true when the runner must wait for a later tick before the next one. */
	private boolean execute(Minecraft mc, CriticScenario.Step step) {
		switch (step.kind()) {
			case WAIT, WAIT_S -> {
				if (step.amount() > 0) {
					waitUntilTick = tick + step.amount();
					return true;
				}
				return false;
			}
			case CMD -> {
				command(mc, step.text());
				return false;
			}
			case TP -> {
				List<Double> n = step.numbers();
				StringBuilder cmd = new StringBuilder("tp @s");
				for (double v : n) {
					cmd.append(' ').append(number(v));
				}
				command(mc, cmd.toString());
				return false;
			}
			case OPEN -> {
				open(mc, step);
				return false;
			}
			case CLOSE -> {
				if (mc.screen == null) {
					warn("close: no screen is open");
				} else {
					mc.screen.onClose();
				}
				return false;
			}
			case SCROLL -> {
				scroll(mc, step);
				return false;
			}
			case KEY -> {
				key(mc, step);
				return false;
			}
			case CLICK_DIAL_KHZ -> {
				clickDial(mc, step.amount());
				return false;
			}
			case DRAG_DIAL_KHZ -> {
				dragDial(mc, step.numbers().get(0).intValue(), step.numbers().get(1).intValue());
				return false;
			}
			case CLICK_POWER -> {
				clickPower(mc);
				return false;
			}
			case CLICK -> {
				Screen screen = mc.screen;
				if (screen == null) {
					warn("click: no screen is open");
				} else {
					click(screen, step.numbers().get(0), step.numbers().get(1));
				}
				return false;
			}
			case SHOT -> {
				if (!scenario.settings().chatInShots()) {
					mc.gui.getChat().clearMessages(false); // command feedback is in the log; keep it out of the picture
				}
				pendingShot = step.text();
				pendingShotTick = tick;
				pendingShotFrame = frames;
				return true;
			}
			case HIDE_HUD -> {
				mc.options.hideGui = step.flag();
				return false;
			}
			case SNAPSHOT -> {
				emit(snapshotJson(mc, step.text()));
				return false;
			}
			case MARK -> {
				mark(mc, step);
				return false;
			}
			case NOTE -> {
				note("note", step.text());
				return false;
			}
			case QUIT -> {
				beginQuit(mc, "quit step");
				return true;
			}
			default -> throw new IllegalStateException("unhandled step " + step.kind());
		}
	}

	private static String number(double v) {
		return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.3f", v);
	}

	private void command(Minecraft mc, String text) {
		JsonObject o = event("command");
		o.addProperty("command", text);
		emit(o);
		lastCommand = text;
		mc.getConnection().sendCommand(text);
	}

	private static String describeBlock(Minecraft mc, BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		return state.toString().replace("Block{", "").replace("}", "");
	}

	private void open(Minecraft mc, CriticScenario.Step step) {
		List<Double> n = step.numbers();
		BlockPos pos = new BlockPos(n.get(0).intValue(), n.get(1).intValue(), n.get(2).intValue());
		JsonObject o = event("open");
		o.add("pos", intArray(pos.getX(), pos.getY(), pos.getZ()));
		o.addProperty("block", describeBlock(mc, pos));
		if (mc.level.getBlockEntity(pos) instanceof RadioBlockEntity radio) {
			o.addProperty("block_entity_khz", radio.getFrequencyKhz());
		} else {
			warn("open: there is no radio block entity at " + pos.toShortString());
		}
		o.addProperty("distance", round(Math.sqrt(mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)), 2));
		RadioClientBridge.openScreen(pos);
		o.addProperty("screen", screenName(mc));
		emit(o);
	}

	/** The middle of the window in GUI coordinates, where the "mouse" rests for the wheel. */
	private static double[] centre(Screen screen) {
		return new double[] {screen.width / 2.0, screen.height / 2.0};
	}

	private RadioScreen radioScreen(Minecraft mc, String what) {
		if (mc.screen instanceof RadioScreen screen) {
			return screen;
		}
		warn(what + ": the radio screen is not open (screen is " + screenName(mc) + ")");
		return null;
	}

	private void scroll(Minecraft mc, CriticScenario.Step step) {
		RadioScreen screen = radioScreen(mc, "scroll");
		if (screen == null) {
			return;
		}
		double[] at = centre(screen);
		int notches = Math.abs(step.amount());
		double direction = Math.signum(step.amount());
		JsonObject o = event("scroll");
		o.addProperty("notches", step.amount());
		o.addProperty("shift", step.shift());
		if (step.shift()) {
			// Screen.hasShiftDown() reads the real keyboard through GLFW, which cannot be set from here.
			// A Shift-wheel notch is worth 10 plain ones.
			o.addProperty("shift_emulated_as_plain_notches", notches * 10);
			notches *= 10;
		}
		emit(o);
		for (int i = 0; i < notches; i++) {
			screen.mouseScrolled(at[0], at[1], 0.0, direction);
		}
	}

	private static int glfwKey(String name) {
		return switch (name) {
			case "left" -> GLFW.GLFW_KEY_LEFT;
			case "right" -> GLFW.GLFW_KEY_RIGHT;
			case "up" -> GLFW.GLFW_KEY_UP;
			case "down" -> GLFW.GLFW_KEY_DOWN;
			case "escape" -> GLFW.GLFW_KEY_ESCAPE;
			case "enter" -> GLFW.GLFW_KEY_ENTER;
			case "tab" -> GLFW.GLFW_KEY_TAB;
			case "space" -> GLFW.GLFW_KEY_SPACE;
			case "e" -> GLFW.GLFW_KEY_E;
			default -> throw new IllegalArgumentException("unknown key " + name);
		};
	}

	private void key(Minecraft mc, CriticScenario.Step step) {
		Screen screen = mc.screen;
		if (screen == null) {
			warn("key: no screen is open");
			return;
		}
		int code = glfwKey(step.text());
		int scancode = GLFW.glfwGetKeyScancode(code);
		int modifiers = step.shift() ? GLFW.GLFW_MOD_SHIFT : 0;
		JsonObject o = event("key");
		o.addProperty("key", step.text());
		o.addProperty("repeat", step.repeat());
		o.addProperty("shift", step.shift());
		emit(o);
		for (int i = 0; i < step.repeat() && mc.screen == screen; i++) {
			screen.keyPressed(code, scancode, modifiers);
			screen.keyReleased(code, scancode, modifiers);
		}
	}

	private void click(Screen screen, double x, double y) {
		JsonObject o = event("click");
		o.addProperty("x", round(x, 2));
		o.addProperty("y", round(y, 2));
		emit(o);
		screen.mouseMoved(x, y);
		screen.mouseClicked(x, y, 0);
		screen.mouseReleased(x, y, 0);
	}

	private static double dialX(Screen screen, int khz) {
		int panelX = Math.max(0, (screen.width - RadioLayout.PANEL_W) / 2);
		return panelX + new DialGeometry(RadioLayout.DIAL_LEFT, RadioLayout.DIAL_WIDTH).centerX(khz);
	}

	private static double dialY(Screen screen) {
		int panelY = Math.max(0, (screen.height - RadioLayout.PANEL_H) / 2);
		return panelY + RadioLayout.STRIP.y() + RadioLayout.STRIP.h() / 2.0;
	}

	private void clickDial(Minecraft mc, int khz) {
		RadioScreen screen = radioScreen(mc, "click_dial_khz");
		if (screen != null) {
			click(screen, dialX(screen, khz), dialY(screen));
		}
	}

	private void dragDial(Minecraft mc, int fromKhz, int toKhz) {
		RadioScreen screen = radioScreen(mc, "drag_dial_khz");
		if (screen == null) {
			return;
		}
		double y = dialY(screen);
		double fromX = dialX(screen, fromKhz);
		double toX = dialX(screen, toKhz);
		JsonObject o = event("drag");
		o.addProperty("from_khz", fromKhz);
		o.addProperty("to_khz", toKhz);
		emit(o);
		screen.mouseMoved(fromX, y);
		screen.mouseClicked(fromX, y, 0);
		int segments = 12;
		double last = fromX;
		for (int i = 1; i <= segments; i++) {
			double x = fromX + (toX - fromX) * i / segments;
			screen.mouseDragged(x, y, 0, x - last, 0.0);
			last = x;
		}
		screen.mouseReleased(toX, y, 0);
	}

	private void clickPower(Minecraft mc) {
		RadioScreen screen = radioScreen(mc, "click_power");
		if (screen == null) {
			return;
		}
		for (GuiEventListener child : screen.children()) {
			if (child instanceof Button button) {
				JsonObject o = event("click_power");
				o.addProperty("button", button.getMessage().getString());
				emit(o);
				click(screen, centreX(button), centreY(button));
				return;
			}
		}
		warn("click_power: the radio screen has no button");
	}

	private static double centreX(AbstractWidget widget) {
		return widget.getX() + widget.getWidth() / 2.0;
	}

	private static double centreY(AbstractWidget widget) {
		return widget.getY() + widget.getHeight() / 2.0;
	}

	private void mark(Minecraft mc, CriticScenario.Step step) {
		if (markSound != null) {
			mc.getSoundManager().stop(markSound);
		}
		markSound = SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(MARK_SOUND), 1.0f, MARK_VOLUME);
		markStopTick = tick + step.ticks();
		JsonObject o = event("mark");
		o.addProperty("label", step.text());
		o.addProperty("sound", MARK_SOUND.toString());
		o.addProperty("ticks", step.ticks());
		o.addProperty("note", "a 300 Hz tone, played non-positionally at volume " + MARK_VOLUME);
		emit(o);
		mc.getSoundManager().play(markSound);
	}

	private void takeShot(Minecraft mc, String name) {
		shotCount++;
		String file = String.format(Locale.ROOT, "%02d_%s.png", shotCount, name);
		Path target = shotsDir.resolve(file);
		JsonObject snapshot = snapshotJson(mc, "shot:" + name);
		try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
			image.writeToFile(target);
			snapshot.addProperty("width", image.getWidth());
			snapshot.addProperty("height", image.getHeight());
		} catch (IOException | RuntimeException e) {
			warn("screenshot " + name + " failed: " + e);
		}
		snapshot.addProperty("type", "shot");
		snapshot.addProperty("name", name);
		snapshot.addProperty("file", outDir.relativize(target).toString());
		snapshot.addProperty("frames", frames);
		emit(snapshot);
	}

	// ------------------------------------------------------------------ shutting down

	private void beginQuit(Minecraft mc, String reason) {
		if (quitRequested) {
			return;
		}
		quitRequested = true;
		JsonObject o = event("quit");
		o.addProperty("reason", reason);
		o.addProperty("steps_run", stepsRun);
		o.addProperty("shots", shotCount);
		o.addProperty("warnings", errorCount);
		o.addProperty("real_seconds", (System.currentTimeMillis() - startMillis) / 1000);
		emit(o);
		enter(Phase.QUITTING);
		// If leaving the world or stopping the game hangs, do not leave the run waiting for ever.
		Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(HALT_AFTER_SECONDS * 1000L);
			} catch (InterruptedException e) {
				return;
			}
			System.err.println("[fadoecravo critic] the game did not stop in " + HALT_AFTER_SECONDS + " s; halting");
			Runtime.getRuntime().halt(3);
		}, "critic-halt-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
		if (mc.level != null) {
			if (mc.screen != null) {
				mc.setScreen(null); // lets an open radio screen send its last frequency
			}
			boolean singleplayer = mc.isLocalServer();
			mc.level.disconnect();
			if (singleplayer) {
				mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
			} else {
				mc.disconnect();
			}
			mc.setScreen(new TitleScreen());
		}
	}

	private void tickQuitting(Minecraft mc) {
		if (mc.level == null || tick - phaseStartTick > 100) {
			finish(mc);
		}
	}

	private void finish(Minecraft mc) {
		enter(Phase.DONE);
		JsonObject o = event("end");
		o.addProperty("steps_run", stepsRun);
		o.addProperty("shots", shotCount);
		o.addProperty("warnings", errorCount);
		emit(o);
		log.close();
		copyScenarioIfMissing();
		mc.stop();
	}

	private void copyScenarioIfMissing() {
		try {
			Path copy = outDir.resolve("scenario.json");
			if (!Files.exists(copy)) {
				Files.copy(Paths.get(System.getProperty(PROPERTY_SCENARIO)), copy, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOGGER.warn("[critic] cannot copy the scenario", e);
		}
	}
}
