package com.manzarig.fadoecravo.client.audio;

import com.manzarig.fadoecravo.broadcast.ClipInfo;
import com.manzarig.fadoecravo.broadcast.Phase;
import com.manzarig.fadoecravo.broadcast.PhaseDefinition;
import com.manzarig.fadoecravo.broadcast.StationDefinition;
import com.manzarig.fadoecravo.net.BreakingNewsPayload;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/** Test helpers for the radio audio tests: small stations, a sound engine that records what it is asked, and a clock. */
final class RadioTestKit {
	static final SoundEvent STATIC_EVENT = SoundEvent.createVariableRangeEvent(id("sfx.static_loop"));
	static final SoundEvent CARRIER_EVENT = SoundEvent.createVariableRangeEvent(id("sfx.carrier_hiss"));
	/**
	 * A day time 12 s into the day phase of day 3 (3 * 24000 + 1000 + 240). The 10 s ident of a
	 * {@link #station} is over, so the radio joins the first song or ad 2 s in, with at least 18 s to go.
	 */
	static final long EARLY_DAY = 73_240L;

	private RadioTestKit() {
	}

	static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("fadoecravo", path);
	}

	/**
	 * A station at {@code khz} whose every phase opens with a 10 s ident, then cycles two songs (60 s
	 * and 40 s) with a 20 s ad after each. It has a 15 s bulletin.
	 */
	static StationDefinition station(String stationId, int khz, double strength) {
		return builder(stationId, khz, strength)
			.allPhases(new PhaseDefinition(true, List.of("ident"), List.of("song_a", "song_b"), List.of("ad"), 1))
			.build();
	}

	/** The same as {@link #station} but on the air only in {@code phase}. */
	static StationDefinition stationOnlyIn(String stationId, int khz, Phase phase) {
		return builder(stationId, khz, 1.0)
			.phase(phase, new PhaseDefinition(true, List.of("ident"), List.of("song_a", "song_b"), List.of("ad"), 1))
			.build();
	}

	/** A station that plays one 10 s chime again and again, like a station's night interval signal. */
	static StationDefinition chimeStation(String stationId, int khz) {
		return builder(stationId, khz, 1.0)
			.allPhases(new PhaseDefinition(true, List.of(), List.of("chime"), List.of(), 0))
			.build();
	}

	/** A station whose phases only have an opening, so it falls silent when that is over. */
	static StationDefinition openingOnlyStation(String stationId, int khz) {
		return builder(stationId, khz, 1.0)
			.allPhases(new PhaseDefinition(true, List.of("ident"), List.of(), List.of(), 0))
			.build();
	}

	static Builder builder(String stationId, int khz, double strength) {
		return new Builder(stationId, khz, strength);
	}

	static final class Builder {
		private final String stationId;
		private final int khz;
		private final double strength;
		private final Map<Phase, PhaseDefinition> phases = new EnumMap<>(Phase.class);

		private Builder(String stationId, int khz, double strength) {
			this.stationId = stationId;
			this.khz = khz;
			this.strength = strength;
		}

		Builder allPhases(PhaseDefinition definition) {
			for (Phase phase : Phase.values()) {
				phases.put(phase, definition);
			}
			return this;
		}

		Builder phase(Phase phase, PhaseDefinition definition) {
			phases.put(phase, definition);
			return this;
		}

		StationDefinition build() {
			Map<String, ClipInfo> clips = new LinkedHashMap<>();
			clips.put("ident", clip("ident", "ident", 10.0));
			clips.put("song_a", clip("song_a", "music", 60.0));
			clips.put("song_b", clip("song_b", "music", 40.0));
			clips.put("ad", clip("ad", "ad", 20.0));
			clips.put("chime", clip("chime", "interval", 10.0));
			clips.put("flash", clip("flash", "breaking", 15.0));
			Map<Phase, PhaseDefinition> all = new EnumMap<>(Phase.class);
			for (Phase phase : Phase.values()) {
				all.put(phase, phases.getOrDefault(phase, PhaseDefinition.OFF_AIR));
			}
			return new StationDefinition(StationDefinition.SCHEMA, stationId, "station.fadoecravo." + stationId, khz, strength,
				all, List.of("flash"), clips);
		}

		private ClipInfo clip(String clipId, String kind, double seconds) {
			return new ClipInfo(id("radio." + stationId + "." + clipId), kind, seconds);
		}
	}

	/** A sound engine that remembers every sound it was given and lets a test decide which are still going. */
	static final class FakeOutput implements RadioChannel.Output {
		final List<SoundInstance> played = new ArrayList<>();
		final List<SoundInstance> stopped = new ArrayList<>();
		private final Set<SoundInstance> active = new HashSet<>();
		/** When true, {@link #play} behaves like an engine that skipped the sound. */
		boolean refuseStarts;

		@Override
		public void play(SoundInstance sound) {
			played.add(sound);
			if (!refuseStarts) {
				active.add(sound);
			}
		}

		@Override
		public boolean isActive(SoundInstance sound) {
			return active.contains(sound);
		}

		@Override
		public void stop(SoundInstance sound) {
			stopped.add(sound);
			active.remove(sound);
		}

		/** The sound ran out by itself. */
		void end(SoundInstance sound) {
			active.remove(sound);
		}

		List<RadioSoundInstance> programmes() {
			return played.stream().filter(RadioSoundInstance.class::isInstance).map(RadioSoundInstance.class::cast).toList();
		}

		RadioSoundInstance lastProgramme() {
			List<RadioSoundInstance> all = programmes();
			return all.get(all.size() - 1);
		}

		/** The static loops handed over, oldest first. */
		List<StaticSoundInstance> loops(SoundEvent event) {
			return played.stream().filter(StaticSoundInstance.class::isInstance).map(StaticSoundInstance.class::cast)
				.filter(sound -> sound.getLocation().equals(event.getLocation())).toList();
		}
	}

	/** A world clock for the tests. */
	static final class Clock {
		long tick = 1;
		long dayTime;
		long gameTime = 1000;
		boolean frozen;

		Clock(long dayTime) {
			this.dayTime = dayTime;
		}

		/** One client tick: the game time always moves, the day time unless it is frozen. */
		void advance(int ticks) {
			tick += ticks;
			gameTime += ticks;
			if (!frozen) {
				dayTime += ticks;
			}
		}

		RadioChannel.Frame frame(Collection<StationDefinition> stations, BreakingNewsPayload news) {
			return new RadioChannel.Frame(tick, dayTime, gameTime, frozen, stations, news);
		}
	}
}
