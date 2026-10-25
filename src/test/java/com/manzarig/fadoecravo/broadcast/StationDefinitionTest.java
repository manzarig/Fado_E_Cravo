package com.manzarig.fadoecravo.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class StationDefinitionTest {
	static List<String> stationIds() {
		return TestStations.IDS;
	}

	private static void assertHasProblem(List<String> problems, String fragment) {
		assertTrue(problems.stream().anyMatch(problem -> problem.contains(fragment)),
			"expected a problem containing '" + fragment + "' in " + problems);
	}

	// --- the real files ---

	@ParameterizedTest
	@MethodSource("stationIds")
	void realStationFilesParseAndValidate(String id) {
		DataResult<StationDefinition> result = StationDefinition.CODEC.parse(JsonOps.INSTANCE, TestStations.json(id));
		StationDefinition station = result.getOrThrow(IllegalStateException::new);
		assertEquals(id, station.id());
		assertEquals(1, station.schema());
		assertEquals("station.fadoecravo." + id, station.name());
		assertEquals(List.of(), station.validate());
		for (Phase phase : Phase.values()) {
			assertTrue(station.phases().containsKey(phase), id + " lacks " + phase);
		}
		assertFalse(station.breaking().isEmpty());
		assertFalse(station.clips().isEmpty());
		// parse() = codec + validation, and gives the same station.
		assertEquals(station, StationDefinition.parse(TestStations.json(id)).getOrThrow(IllegalStateException::new));
	}

	@Test
	void realStationsHaveTheContractedDialPositionsAndStrengths() {
		StationDefinition lusitana = TestStations.load("emissora_lusitana");
		StationDefinition tejo = TestStations.load("radio_tejo");
		StationDefinition atlantico = TestStations.load("radio_atlantico");
		assertEquals(670, lusitana.frequencyKhz());
		assertEquals(1035, tejo.frequencyKhz());
		assertEquals(1440, atlantico.frequencyKhz());
		assertEquals(1.0, lusitana.signalStrength());
		assertEquals(1.0, tejo.signalStrength());
		assertEquals(0.55, atlantico.signalStrength());
	}

	@Test
	void realStationFieldsMatchTheJson() {
		StationDefinition tejo = TestStations.load("radio_tejo");
		JsonObject json = TestStations.json("radio_tejo").getAsJsonObject();
		JsonObject day = json.getAsJsonObject("phases").getAsJsonObject("day");
		PhaseDefinition definition = tejo.phases().get(Phase.DAY);
		assertTrue(definition.onAir());
		assertEquals(strings(day.getAsJsonArray("opening")), definition.opening());
		assertEquals(strings(day.getAsJsonArray("programs")), definition.programs());
		assertEquals(strings(day.getAsJsonArray("interstitials")), definition.interstitials());
		assertEquals(day.get("interstitial_every").getAsInt(), definition.interstitialEvery());
		assertEquals(strings(json.getAsJsonArray("breaking")), tejo.breaking());
		ClipInfo marcha = tejo.clips().get("marcha_1");
		assertEquals("fadoecravo:radio.music.marcha_1", marcha.sound().toString());
		assertEquals("music", marcha.kind());
		assertEquals(json.getAsJsonObject("clips").getAsJsonObject("marcha_1").get("duration").getAsDouble(), marcha.duration());
		assertEquals(json.getAsJsonObject("clips").size(), tejo.clips().size());
	}

	private static List<String> strings(com.google.gson.JsonArray array) {
		return array.asList().stream().map(JsonElement::getAsString).toList();
	}

	@Test
	void offAirPhasesParseAsOffAir() {
		StationDefinition atlantico = TestStations.load("radio_atlantico");
		for (Phase phase : List.of(Phase.DAWN, Phase.DAY, Phase.DUSK)) {
			assertFalse(atlantico.phases().get(phase).onAir(), phase.toString());
		}
		assertTrue(atlantico.phases().get(Phase.NIGHT).onAir());
	}

	@ParameterizedTest
	@MethodSource("stationIds")
	void parsingTwiceGivesEqualStations(String id) {
		StationDefinition first = TestStations.load(id);
		StationDefinition second = TestStations.load(id);
		assertNotSame(first, second);
		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
	}

	@ParameterizedTest
	@MethodSource("stationIds")
	void encodingAndDecodingAgainGivesTheSameStation(String id) {
		StationDefinition station = TestStations.load(id);
		JsonElement encoded = StationDefinition.CODEC.encodeStart(JsonOps.INSTANCE, station).getOrThrow(IllegalStateException::new);
		JsonObject object = encoded.getAsJsonObject();
		assertEquals(TestStations.json(id).getAsJsonObject().keySet(), object.keySet());
		assertEquals(TestStations.json(id).getAsJsonObject().getAsJsonObject("phases").keySet(), object.getAsJsonObject("phases").keySet());
		assertEquals(station, StationDefinition.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow(IllegalStateException::new));
	}

	@ParameterizedTest
	@MethodSource("stationIds")
	void everyClipSoundIsInSoundsJson(String id) {
		JsonObject sounds = TestStations.resourceJson("/assets/fadoecravo/sounds.json").getAsJsonObject();
		for (Map.Entry<String, ClipInfo> entry : TestStations.load(id).clips().entrySet()) {
			ClipInfo clip = entry.getValue();
			assertEquals("fadoecravo", clip.sound().getNamespace(), entry.getKey());
			assertTrue(sounds.has(clip.sound().getPath()), id + "/" + entry.getKey() + " -> " + clip.sound());
		}
	}

	// --- bad files ---

	private static JsonObject copyOfTejo() {
		return TestStations.json("radio_tejo").deepCopy().getAsJsonObject();
	}

	@Test
	void parseRejectsAFileThatIsMissingAKey() {
		for (String key : List.of("schema", "id", "name", "frequency_khz", "signal_strength", "phases", "breaking", "clips")) {
			JsonObject json = copyOfTejo();
			json.remove(key);
			assertTrue(StationDefinition.parse(json).error().isPresent(), "without " + key);
			assertTrue(StationDefinition.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent(), "without " + key);
		}
		JsonObject json = copyOfTejo();
		json.getAsJsonObject("phases").getAsJsonObject("day").remove("interstitial_every");
		assertTrue(StationDefinition.parse(json).error().isPresent());
		json = copyOfTejo();
		json.getAsJsonObject("clips").getAsJsonObject("ad_1").remove("duration");
		assertTrue(StationDefinition.parse(json).error().isPresent());
	}

	@Test
	void parseRejectsAnUnknownPhaseAndWrongTypes() {
		JsonObject json = copyOfTejo();
		json.getAsJsonObject("phases").add("noon", json.getAsJsonObject("phases").get("day"));
		assertTrue(StationDefinition.parse(json).error().isPresent());

		json = copyOfTejo();
		json.getAsJsonObject("clips").getAsJsonObject("ad_1").add("duration", new JsonPrimitive("long"));
		assertTrue(StationDefinition.parse(json).error().isPresent());

		json = copyOfTejo();
		json.getAsJsonObject("clips").getAsJsonObject("ad_1").add("sound", new JsonPrimitive("Not A Valid Id"));
		assertTrue(StationDefinition.parse(json).error().isPresent());

		json = copyOfTejo();
		json.add("frequency_khz", new JsonPrimitive("1035 kHz"));
		assertTrue(StationDefinition.parse(json).error().isPresent());
	}

	@Test
	void parseReportsTheProblemsOfAFileThatReadsFine() {
		JsonObject json = copyOfTejo();
		json.add("schema", new JsonPrimitive(2));
		json.getAsJsonObject("phases").getAsJsonObject("day").getAsJsonArray("programs").add("ghost");
		// The codec alone accepts it ...
		StationDefinition station = StationDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(IllegalStateException::new);
		assertHasProblem(station.validate(), "schema");
		// ... parse() does not, and says why.
		String message = StationDefinition.parse(json).error().orElseThrow().message();
		assertTrue(message.contains("schema") && message.contains("ghost"), message);
	}

	// --- validate() ---

	@Test
	void aCorrectStationHasNoProblems() {
		assertEquals(List.of(), TestStations.valid().build().validate());
	}

	@Test
	void validateFlagsAnUnsupportedSchema() {
		assertHasProblem(TestStations.valid().schema(2).build().validate(), "schema is 2");
		assertHasProblem(TestStations.valid().schema(0).build().validate(), "schema is 0");
	}

	@Test
	void validateFlagsABadIdentityFrequencyOrStrength() {
		assertHasProblem(TestStations.station(" ").build().validate(), "id is blank");
		assertHasProblem(TestStations.valid().frequency(529).build().validate(), "frequency_khz 529");
		assertHasProblem(TestStations.valid().frequency(1601).build().validate(), "frequency_khz 1601");
		assertEquals(List.of(), TestStations.valid().frequency(530).build().validate());
		assertEquals(List.of(), TestStations.valid().frequency(1600).build().validate());
		assertHasProblem(TestStations.valid().strength(0.0).build().validate(), "signal_strength");
		assertHasProblem(TestStations.valid().strength(1.5).build().validate(), "signal_strength");
		assertHasProblem(TestStations.valid().strength(-0.2).build().validate(), "signal_strength");
		assertHasProblem(TestStations.valid().strength(Double.NaN).build().validate(), "signal_strength");
		assertEquals(List.of(), TestStations.valid().strength(0.55).build().validate());
	}

	@Test
	void validateFlagsAMissingPhase() {
		StationDefinition station = TestStations.station("gap")
			.clip("song", "music", 60.0)
			.phase(Phase.DAWN, List.of(), List.of("song"), List.of(), 0)
			.phase(Phase.DAY, List.of(), List.of("song"), List.of(), 0)
			.phase(Phase.NIGHT, PhaseDefinition.OFF_AIR)
			.build();
		List<String> problems = station.validate();
		assertHasProblem(problems, "phase dusk is missing");
		assertEquals(1, problems.size(), problems.toString());
	}

	@Test
	void validateFlagsClipIdsThatAreNotInClips() {
		assertHasProblem(TestStations.valid().phase(Phase.DAY, List.of("nope"), List.of("song"), List.of("ad"), 1).build().validate(),
			"phase day: opening refers to unknown clip 'nope'");
		assertHasProblem(TestStations.valid().phase(Phase.DUSK, List.of(), List.of("nope"), List.of("ad"), 1).build().validate(),
			"phase dusk: programs refers to unknown clip 'nope'");
		assertHasProblem(TestStations.valid().phase(Phase.NIGHT, List.of(), List.of("song"), List.of("nope"), 1).build().validate(),
			"phase night: interstitials refers to unknown clip 'nope'");
		assertHasProblem(TestStations.valid().breaking("nope").build().validate(), "breaking refers to unknown clip 'nope'");
		// Off-air phases are checked as well; a typo there would bite when the phase is switched on.
		assertHasProblem(TestStations.valid()
			.phase(Phase.DAWN, new PhaseDefinition(false, List.of(), List.of("nope"), List.of(), 0)).build().validate(),
			"phase dawn: programs refers to unknown clip 'nope'");
	}

	@Test
	void validateFlagsDurationsThatAreNotPositive() {
		assertHasProblem(TestStations.valid().clip("zero", "music", 0.0).build().validate(), "clip 'zero'");
		assertHasProblem(TestStations.valid().clip("neg", "music", -3.0).build().validate(), "clip 'neg'");
		assertHasProblem(TestStations.valid().clip("nan", "music", Double.NaN).build().validate(), "clip 'nan'");
		assertHasProblem(TestStations.valid().clip("inf", "music", Double.POSITIVE_INFINITY).build().validate(), "clip 'inf'");
		assertEquals(List.of(), TestStations.valid().clip("tiny", "music", 0.05).build().validate());
	}

	@Test
	void validateFlagsAnUnknownKindAndABreakingClipOfTheWrongKind() {
		assertHasProblem(TestStations.valid().clip("odd", "jingle", 5.0).build().validate(), "unknown kind 'jingle'");
		for (String kind : ClipInfo.KINDS) {
			assertEquals(List.of(), TestStations.valid().clip("extra", kind, 5.0).build().validate(), kind);
		}
		assertHasProblem(TestStations.valid().clip("flash", "news", 5.0).build().validate(),
			"breaking clip 'flash' has kind 'news'");
	}

	@Test
	void validateFlagsPhaseSettingsThatDoNothingUseful() {
		assertHasProblem(TestStations.valid().phase(Phase.DAY, List.of(), List.of("song"), List.of("ad"), -1).build().validate(),
			"interstitial_every is -1");
		assertHasProblem(TestStations.valid().phase(Phase.DAY, List.of(), List.of("song"), List.of("ad"), 0).build().validate(),
			"never played");
		assertHasProblem(TestStations.valid().phase(Phase.DAY, List.of("ident"), List.of(), List.of(), 0).build().validate(),
			"no programs or interstitials");
		assertHasProblem(TestStations.valid().phase(Phase.DAY, List.of(), List.of(), List.of(), 0).build().validate(),
			"no programs or interstitials");
		// Nothing to flag for an off-air phase, or for a loop of interstitials alone.
		assertEquals(List.of(), TestStations.valid().phase(Phase.DAY, PhaseDefinition.OFF_AIR).build().validate());
		assertEquals(List.of(), TestStations.valid().phase(Phase.DAY, List.of(), List.of(), List.of("ad"), 3).build().validate());
	}

	@Test
	void recordsCopyTheirCollections() {
		java.util.ArrayList<String> programs = new java.util.ArrayList<>(List.of("song"));
		PhaseDefinition definition = new PhaseDefinition(true, List.of(), programs, List.of(), 0);
		programs.add("other");
		assertEquals(List.of("song"), definition.programs());
		assertThrows(UnsupportedOperationException.class, () -> definition.programs().add("x"));
		StationDefinition station = TestStations.valid().build();
		assertThrows(UnsupportedOperationException.class, () -> station.phases().put(Phase.DAY, PhaseDefinition.OFF_AIR));
		assertThrows(UnsupportedOperationException.class, () -> station.clips().clear());
		assertThrows(UnsupportedOperationException.class, () -> station.breaking().clear());
	}
}
