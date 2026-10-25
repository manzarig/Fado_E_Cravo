package com.manzarig.fadoecravo.broadcast;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Test helpers: the real station files from the resources and small stations built in code. */
final class TestStations {
	static final List<String> IDS = List.of("emissora_lusitana", "radio_tejo", "radio_atlantico");

	private TestStations() {
	}

	/** A file from {@code src/main/resources}, as it is on the classpath. */
	static JsonElement resourceJson(String path) {
		try (InputStream in = TestStations.class.getResourceAsStream(path)) {
			assertNotNull(in, "missing resource " + path);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The raw JSON of {@code assets/fadoecravo/stations/<id>.json}. */
	static JsonElement json(String id) {
		return resourceJson("/assets/fadoecravo/stations/" + id + ".json");
	}

	/** One of the real stations, read with {@link StationDefinition#CODEC} only (no validation). */
	static StationDefinition load(String id) {
		return StationDefinition.CODEC.parse(JsonOps.INSTANCE, json(id)).getOrThrow(IllegalStateException::new);
	}

	static ClipInfo clip(String id, String kind, double seconds) {
		return new ClipInfo(ResourceLocation.fromNamespaceAndPath("fadoecravo", "radio.test." + id), kind, seconds);
	}

	/** Starts a station built in code. Phases that are not added are missing. */
	static Builder station(String id) {
		return new Builder(id);
	}

	static final class Builder {
		private final String id;
		private int schema = StationDefinition.SCHEMA;
		private int frequencyKhz = 1000;
		private double signalStrength = 1.0;
		private final Map<Phase, PhaseDefinition> phases = new EnumMap<>(Phase.class);
		private List<String> breaking = List.of();
		private final Map<String, ClipInfo> clips = new LinkedHashMap<>();

		private Builder(String id) {
			this.id = id;
		}

		Builder schema(int schema) {
			this.schema = schema;
			return this;
		}

		Builder frequency(int frequencyKhz) {
			this.frequencyKhz = frequencyKhz;
			return this;
		}

		Builder strength(double signalStrength) {
			this.signalStrength = signalStrength;
			return this;
		}

		Builder clip(String clipId, String kind, double seconds) {
			clips.put(clipId, TestStations.clip(clipId, kind, seconds));
			return this;
		}

		/** Adds an on-air phase. */
		Builder phase(Phase phase, List<String> opening, List<String> programs, List<String> interstitials, int every) {
			phases.put(phase, new PhaseDefinition(true, opening, programs, interstitials, every));
			return this;
		}

		Builder phase(Phase phase, PhaseDefinition definition) {
			phases.put(phase, definition);
			return this;
		}

		/** Gives every phase the same on-air definition. */
		Builder allPhases(List<String> opening, List<String> programs, List<String> interstitials, int every) {
			for (Phase phase : Phase.values()) {
				phase(phase, opening, programs, interstitials, every);
			}
			return this;
		}

		Builder breaking(String... ids) {
			this.breaking = List.of(ids);
			return this;
		}

		StationDefinition build() {
			return new StationDefinition(schema, id, "station.fadoecravo." + id, frequencyKhz, signalStrength, phases, breaking, clips);
		}
	}

	/** A base for the validation tests: a correct station with one clip of each of two kinds. */
	static Builder valid() {
		return station("valid")
			.clip("ident", "ident", 10.0)
			.clip("song", "music", 60.0)
			.clip("ad", "ad", 20.0)
			.clip("flash", "breaking", 15.0)
			.allPhases(List.of("ident"), List.of("song"), List.of("ad"), 1)
			.breaking("flash");
	}
}
