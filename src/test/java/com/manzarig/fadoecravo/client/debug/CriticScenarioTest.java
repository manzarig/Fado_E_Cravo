package com.manzarig.fadoecravo.client.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.manzarig.fadoecravo.client.debug.CriticScenario.Kind;
import com.manzarig.fadoecravo.client.debug.CriticScenario.ScenarioException;
import com.manzarig.fadoecravo.client.debug.CriticScenario.Step;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CriticScenarioTest {
	private static CriticScenario parse(String json) throws ScenarioException {
		return CriticScenario.parse(json);
	}

	private static Step only(String stepJson) throws ScenarioException {
		List<Step> steps = parse("[" + stepJson + "]").steps();
		assertEquals(1, steps.size());
		return steps.get(0);
	}

	private static String errorOf(String json) {
		return assertThrows(ScenarioException.class, () -> parse(json)).getMessage();
	}

	// ------------------------------------------------------------------ shape

	@Test
	void anArrayIsASceneWithDefaultSettings() throws Exception {
		CriticScenario scenario = parse("[{\"wait\": 5}, {\"quit\": true}]");
		assertEquals(2, scenario.steps().size());
		assertEquals(CriticScenario.Settings.defaults(), scenario.settings());
		assertTrue(scenario.settings().daylightCycle());
		assertTrue(scenario.settings().flat());
		assertEquals("creative", scenario.settings().gameMode());
		assertEquals(3, scenario.settings().guiScale());
	}

	@Test
	void anObjectCarriesSettingsAndSteps() throws Exception {
		CriticScenario scenario = parse("""
			{"comment": "hello",
			 "settings": {"daylight_cycle": false, "flat": false, "seed": 7, "game_mode": "Survival", "lang": "PT_PT",
			              "chat_in_shots": true, "gui_scale": 4, "settle_ticks": 10, "timeout_minutes": 3,
			              "snapshot_every_ticks": 0, "gamerules": {"doFireTick": false, "randomTickSpeed": 0}},
			 "steps": [{"quit": true}]}""");
		CriticScenario.Settings s = scenario.settings();
		assertFalse(s.daylightCycle());
		assertFalse(s.flat());
		assertEquals(7L, s.seed());
		assertEquals("survival", s.gameMode());
		assertEquals("pt_pt", s.lang());
		assertTrue(s.chatInShots());
		assertEquals(4, s.guiScale());
		assertEquals(10, s.settleTicks());
		assertEquals(3, s.timeoutMinutes());
		assertEquals(0, s.snapshotEveryTicks());
		assertEquals("false", s.gameRules().get("doFireTick"));
		assertEquals("0", s.gameRules().get("randomTickSpeed"));
	}

	@Test
	void theWindowSizeDefaultsTo720pAndCanBeSet() throws Exception {
		assertEquals(1280, parse("[]").settings().windowWidth());
		assertEquals(720, parse("[]").settings().windowHeight());
		CriticScenario.Settings s = parse("{\"settings\": {\"window\": \"640x480\"}, \"steps\": []}").settings();
		assertEquals(640, s.windowWidth());
		assertEquals(480, s.windowHeight());
		assertTrue(errorOf("{\"settings\": {\"window\": \"big\"}, \"steps\": []}").contains("settings.window"));
		assertTrue(errorOf("{\"settings\": {\"window\": \"100x100\"}, \"steps\": []}").contains("320x240"));
		assertTrue(errorOf("{\"settings\": {\"window\": 640}, \"steps\": []}").contains("string"));
	}

	@Test
	void anEmptyScenarioIsAllowed() throws Exception {
		assertTrue(parse("[]").steps().isEmpty());
	}

	@Test
	void stepsKeepTheirOrderAndIndex() throws Exception {
		List<Step> steps = parse("[{\"wait\": 1}, {\"cmd\": \"say hi\"}, {\"quit\": true}]").steps();
		assertEquals(List.of(Kind.WAIT, Kind.CMD, Kind.QUIT), steps.stream().map(Step::kind).toList());
		assertEquals(List.of(0, 1, 2), steps.stream().map(Step::index).toList());
	}

	@Test
	void rejectsJsonThatIsNotAScenario() {
		assertTrue(errorOf("{not json").startsWith("not valid JSON"));
		assertTrue(errorOf("42").contains("array of steps"));
		assertTrue(errorOf("{\"steps\": {}}").contains("\"steps\" array"));
		assertTrue(errorOf("{\"settings\": {}}").contains("\"steps\" array"));
		assertTrue(errorOf("{\"steps\": [], \"stepz\": []}").contains("stepz"));
		assertTrue(errorOf("[7]").startsWith("step 0"));
	}

	// ------------------------------------------------------------------ settings

	@Test
	void rejectsBadSettings() {
		assertTrue(errorOf("{\"settings\": {\"daylight\": false}, \"steps\": []}").contains("unknown key \"daylight\""));
		assertTrue(errorOf("{\"settings\": {\"daylight_cycle\": \"no\"}, \"steps\": []}").contains("true or false"));
		assertTrue(errorOf("{\"settings\": {\"game_mode\": \"god\"}, \"steps\": []}").contains("game_mode"));
		assertTrue(errorOf("{\"settings\": {\"lang\": \"portuguese\"}, \"steps\": []}").contains("settings.lang"));
		assertTrue(errorOf("{\"settings\": {\"gui_scale\": 9}, \"steps\": []}").contains("gui_scale"));
		assertTrue(errorOf("{\"settings\": {\"seed\": 1.5}, \"steps\": []}").contains("seed"));
		assertTrue(errorOf("{\"settings\": {\"gamerules\": {\"do fire\": true}}, \"steps\": []}").contains("gamerules"));
		assertTrue(errorOf("{\"settings\": [], \"steps\": []}").contains("settings"));
	}

	// ------------------------------------------------------------------ waits and commands

	@Test
	void waitIsInTicksAndWaitSInSeconds() throws Exception {
		assertEquals(40, only("{\"wait\": 40}").amount());
		assertEquals(0, only("{\"wait\": 0}").amount());
		Step seconds = only("{\"wait_s\": 2.5}");
		assertEquals(Kind.WAIT_S, seconds.kind());
		assertEquals(50, seconds.amount());
	}

	@Test
	void rejectsBadWaits() {
		assertTrue(errorOf("[{\"wait\": -1}]").contains("whole number"));
		assertTrue(errorOf("[{\"wait\": 1.5}]").contains("whole number"));
		assertTrue(errorOf("[{\"wait\": \"5\"}]").contains("whole number"));
		assertTrue(errorOf("[{\"wait_s\": -2}]").contains("seconds"));
		assertTrue(errorOf("[{\"wait_s\": \"x\"}]").contains("number"));
	}

	@Test
	void aCommandLosesItsSlash() throws Exception {
		assertEquals("time set 18000", only("{\"cmd\": \"/time set 18000\"}").text());
		assertEquals("time set 18000", only("{\"cmd\": \"  time set 18000 \"}").text());
		assertTrue(errorOf("[{\"cmd\": \"/\"}]").contains("empty"));
		assertTrue(errorOf("[{\"cmd\": 5}]").contains("string"));
	}

	@Test
	void tpTakesPositionAndOptionalRotation() throws Exception {
		assertEquals(List.of(1.0, -60.0, 2.5), only("{\"tp\": [1, -60, 2.5]}").numbers());
		assertEquals(5, only("{\"tp\": [0.5, -60, 0.5, 180, 15]}").numbers().size());
		assertTrue(errorOf("[{\"tp\": [1, 2]}]").contains("numbers"));
		assertTrue(errorOf("[{\"tp\": [1, 2, 3, 4]}]").contains("[x, y, z, yaw, pitch]"));
		assertTrue(errorOf("[{\"tp\": [1, 2, \"3\"]}]").contains("number"));
	}

	// ------------------------------------------------------------------ radio screen

	@Test
	void openNeedsABlockPosition() throws Exception {
		assertEquals(List.of(0.0, -60.0, 3.0), only("{\"open\": [0, -60, 3]}").numbers());
		assertTrue(errorOf("[{\"open\": [0.5, -60, 3]}]").contains("whole numbers"));
		assertTrue(errorOf("[{\"open\": [0, 1]}]").contains("3 numbers"));
	}

	@Test
	void closeQuitAndClickPowerTakeTrue() throws Exception {
		assertEquals(Kind.CLOSE, only("{\"close\": true}").kind());
		assertEquals(Kind.QUIT, only("{\"quit\": true}").kind());
		assertEquals(Kind.CLICK_POWER, only("{\"click_power\": true}").kind());
		assertTrue(errorOf("[{\"close\": false}]").contains("write true"));
		assertTrue(errorOf("[{\"quit\": 1}]").contains("write true"));
	}

	@Test
	void scrollTakesNotchesAndShift() throws Exception {
		Step plain = only("{\"scroll\": 3}");
		assertEquals(3, plain.amount());
		assertFalse(plain.shift());
		Step shifted = only("{\"scroll\": -2, \"shift\": true}");
		assertEquals(-2, shifted.amount());
		assertTrue(shifted.shift());
		assertTrue(errorOf("[{\"scroll\": 0}]").contains("does nothing"));
		assertTrue(errorOf("[{\"scroll\": 1, \"shift\": \"yes\"}]").contains("true or false"));
		assertTrue(errorOf("[{\"scroll\": 1.5}]").contains("whole number"));
	}

	@Test
	void keyTakesNameRepeatAndShift() throws Exception {
		Step step = only("{\"key\": \"Left\", \"repeat\": 12, \"shift\": true}");
		assertEquals("left", step.text());
		assertEquals(12, step.repeat());
		assertTrue(step.shift());
		assertEquals(1, only("{\"key\": \"escape\"}").repeat());
		assertTrue(errorOf("[{\"key\": \"f3\"}]").contains("unknown key"));
		assertTrue(errorOf("[{\"key\": \"left\", \"repeat\": 0}]").contains("repeat"));
		for (String name : CriticScenario.KEY_NAMES) {
			assertEquals(name, only("{\"key\": \"" + name + "\"}").text());
		}
	}

	@Test
	void dialStepsTakeFrequenciesInTheBand() throws Exception {
		assertEquals(670, only("{\"click_dial_khz\": 670}").amount());
		assertEquals(530, only("{\"click_dial_khz\": 530}").amount());
		assertEquals(1600, only("{\"click_dial_khz\": 1600}").amount());
		assertTrue(errorOf("[{\"click_dial_khz\": 529}]").contains("530 to 1600"));
		assertTrue(errorOf("[{\"click_dial_khz\": 1601}]").contains("530 to 1600"));
		assertEquals(List.of(850.0, 1035.0), only("{\"drag_dial_khz\": [850, 1035]}").numbers());
		assertTrue(errorOf("[{\"drag_dial_khz\": [850]}]").contains("2 numbers"));
		assertTrue(errorOf("[{\"drag_dial_khz\": [850, 2000]}]").contains("kHz"));
	}

	@Test
	void clickTakesGuiCoordinates() throws Exception {
		assertEquals(List.of(100.5, 40.0), only("{\"click\": [100.5, 40]}").numbers());
		assertTrue(errorOf("[{\"click\": [1]}]").contains("2 numbers"));
	}

	// ------------------------------------------------------------------ output

	@Test
	void shotNamesBecomeFileNames() throws Exception {
		assertEquals("screen_670-lusitana", only("{\"shot\": \"screen_670-lusitana\"}").text());
		assertTrue(errorOf("[{\"shot\": \"a/b\"}]").contains("file name"));
		assertTrue(errorOf("[{\"shot\": \"a b\"}]").contains("file name"));
		assertTrue(errorOf("[{\"shot\": \"\"}]").contains("file name"));
		assertTrue(errorOf("[{\"shot\": \"..\"}]").contains("file name"));
	}

	@Test
	void hideHudTakesABoolean() throws Exception {
		assertTrue(only("{\"hide_hud\": true}").flag());
		assertFalse(only("{\"hide_hud\": false}").flag());
		assertTrue(errorOf("[{\"hide_hud\": \"on\"}]").contains("true or false"));
	}

	@Test
	void snapshotMarkAndNoteTakeText() throws Exception {
		assertEquals("after_pulse", only("{\"snapshot\": \"after_pulse\"}").text());
		assertEquals("m", only("{\"mark\": \"m\"}").text());
		assertEquals(10, only("{\"mark\": \"m\"}").ticks());
		assertEquals(25, only("{\"mark\": \"m\", \"ticks\": 25}").ticks());
		assertEquals("STAGE 1", only("{\"note\": \"STAGE 1\"}").text());
		assertNull(only("{\"wait\": 1}").text());
		assertTrue(errorOf("[{\"mark\": \"m\", \"ticks\": 0}]").contains("ticks"));
		assertTrue(errorOf("[{\"snapshot\": 1}]").contains("string"));
	}

	// ------------------------------------------------------------------ mistakes

	@Test
	void anUnknownActionListsTheKnownOnes() {
		String message = errorOf("[{\"wait\": 1}, {\"jump\": true}]");
		assertTrue(message.startsWith("step 1"), message);
		assertTrue(message.contains("no known action"), message);
		assertTrue(message.contains("click_dial_khz"), message);
	}

	@Test
	void twoActionsInOneStepAreAnError() {
		assertTrue(errorOf("[{\"wait\": 1, \"cmd\": \"say hi\"}]").contains("two actions"));
	}

	@Test
	void aModifierMustBelongToTheAction() {
		assertTrue(errorOf("[{\"wait\": 1, \"shift\": true}]").contains("\"shift\" is not allowed with \"wait\""));
		assertTrue(errorOf("[{\"scroll\": 1, \"repeat\": 3}]").contains("\"repeat\" is not allowed with \"scroll\""));
		assertTrue(errorOf("[{\"cmd\": \"say x\", \"waiit\": 3}]").contains("\"waiit\""));
	}

	@Test
	void aCommentKeyIsIgnored() throws Exception {
		assertEquals(Kind.WAIT, only("{\"wait\": 1, \"comment\": \"let the chunks load\"}").kind());
	}

	@Test
	void theStepIsKeptAsWrittenForTheLog() throws Exception {
		Step step = only("{\"scroll\": -2, \"shift\": true}");
		assertEquals(-2, step.json().get("scroll").getAsInt());
		assertTrue(step.json().get("shift").getAsBoolean());
	}

	// ------------------------------------------------------------------ the shipped scenarios

	@Test
	void everyScenarioInTheRepositoryParsesAndEndsWithQuit() throws IOException, ScenarioException {
		Path dir = Paths.get(System.getProperty("user.dir")).resolve("../../tools/critic/scenarios").normalize();
		if (!Files.isDirectory(dir)) {
			// The test runs in build/test-work; fall back to the project root when started from there.
			dir = Paths.get("tools/critic/scenarios");
		}
		if (!Files.isDirectory(dir)) {
			return; // not run from a checkout; nothing to check
		}
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
				CriticScenario scenario = parse(Files.readString(file, StandardCharsets.UTF_8));
				assertFalse(scenario.steps().isEmpty(), file + " has no steps");
				assertEquals(Kind.QUIT, scenario.steps().get(scenario.steps().size() - 1).kind(), file + " must end with quit");
			}
		}
	}
}
