package com.manzarig.fadoecravo.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The carry-over of a clip across a phase boundary, see {@link BroadcastSchedule#carryIn}. */
class BroadcastScheduleCarryOverTest {
	private static final long DAY = 5;

	/**
	 * Every phase: a 10 s ident, then 65 s songs. Dawn (100 s) ends 25 s into its second song, so the
	 * song takes 40 s of the day; the day (550 s) then ends 45 s into a song that takes 20 s of dusk.
	 */
	private static StationDefinition steady() {
		return TestStations.station("steady")
			.clip("ident", "ident", 10.0)
			.clip("song", "music", 65.0)
			.allPhases(List.of("ident"), List.of("song"), List.of(), 0)
			.build();
	}

	private static long start(Phase phase) {
		return BroadcastScheduleTest.phaseStart(phase, DAY);
	}

	private static Slot at(StationDefinition station, long dayTime) {
		return BroadcastSchedule.at(station, dayTime).orElseThrow();
	}

	@Test
	void dawnNeverHasACarryIn() {
		StationDefinition station = steady();
		assertEquals(0.0, BroadcastSchedule.carryIn(station, new PhaseInstance(Phase.DAWN, DAY)));
		Slot first = at(station, start(Phase.DAWN));
		assertEquals("ident", first.clipId());
		assertEquals(Phase.DAWN, first.phase());
		assertEquals(0.0, first.offsetSeconds());
	}

	@Test
	void theLastSongOfDawnFinishesInTheDay() {
		StationDefinition station = steady();
		assertEquals(40.0, BroadcastSchedule.carryIn(station, new PhaseInstance(Phase.DAY, DAY)), 1e-9);

		Slot carried = at(station, start(Phase.DAY));
		assertEquals("song", carried.clipId());
		assertEquals(Phase.DAWN, carried.phase());
		assertEquals(DAY, carried.day());
		assertEquals(25.0, carried.offsetSeconds(), 1e-9, "dawn's second song began at 75 s of 100");
		assertEquals(new PhaseInstance(Phase.DAWN, DAY), BroadcastSchedule.ownerAt(station, start(Phase.DAY)));

		long dayOpening = start(Phase.DAY) + 40 * 20;
		Slot opening = at(station, dayOpening);
		assertEquals("ident", opening.clipId());
		assertEquals(Phase.DAY, opening.phase());
		assertEquals(0, opening.sequenceIndex());
		assertEquals(0.0, opening.offsetSeconds());
		assertEquals(new PhaseInstance(Phase.DAY, DAY), BroadcastSchedule.ownerAt(station, dayOpening));
	}

	@Test
	void aCarryInIsCappedAtHalfThePhase() {
		// Day: 40 s carry-in, ident to 50 s, songs from 50 s: the eighth ends at 570 s, 20 s past the day's end.
		StationDefinition station = steady();
		assertEquals(20.0, BroadcastSchedule.carryIn(station, new PhaseInstance(Phase.DUSK, DAY)), 1e-9);

		StationDefinition longSong = TestStations.station("long")
			.clip("ident", "ident", 10.0)
			.clip("long", "music", 200.0)
			.phase(Phase.DAY, List.of("ident"), List.of("long"), List.of(), 0)
			.phase(Phase.DUSK, List.of("ident"), List.of("long"), List.of(), 0)
			.build();
		// Day: ident to 10 s, then songs at 10, 210 and 410 s; the last ends 60 s after the day.
		assertEquals(45.0, BroadcastSchedule.carryIn(longSong, new PhaseInstance(Phase.DUSK, DAY)), 1e-9);
		Slot cutShort = at(longSong, start(Phase.DUSK) + 44 * 20);
		assertEquals(Phase.DAY, cutShort.phase());
		Slot duskOpening = at(longSong, start(Phase.DUSK) + 45 * 20);
		assertEquals(Phase.DUSK, duskOpening.phase());
		assertEquals("ident", duskOpening.clipId());
	}

	@Test
	void noCarryInAfterAPhaseThatIsOffTheAir() {
		StationDefinition station = TestStations.station("night_only")
			.clip("ident", "ident", 10.0)
			.clip("song", "music", 65.0)
			.phase(Phase.NIGHT, List.of("ident"), List.of("song"), List.of(), 0)
			.build();
		assertEquals(0.0, BroadcastSchedule.carryIn(station, new PhaseInstance(Phase.NIGHT, DAY)));
		assertEquals(0.0, BroadcastSchedule.carryIn(station, new PhaseInstance(Phase.DAY, DAY)));
	}

	@Test
	void aSongFinishesBeforeTheStationGoesQuiet() {
		StationDefinition station = TestStations.station("day_only")
			.clip("ident", "ident", 10.0)
			.clip("song", "music", 65.0)
			.phase(Phase.DAY, List.of("ident"), List.of("song"), List.of(), 0)
			.phase(Phase.DUSK, new PhaseDefinition(false, List.of(), List.of(), List.of(), 0))
			.build();
		// Day: ident to 10 s, songs from 10 s; the ninth ends at 595 s, 45 s into dusk (exactly the cap).
		assertEquals(45.0, BroadcastSchedule.carryIn(station, new PhaseInstance(Phase.DUSK, DAY)), 1e-9);
		assertTrue(BroadcastSchedule.at(station, start(Phase.DUSK) + 20).isPresent());
		assertEquals(Optional.empty(), BroadcastSchedule.at(station, start(Phase.DUSK) + 46 * 20));
	}

	static List<String> stationIds() {
		return TestStations.IDS;
	}

	/**
	 * Across a whole day of a real station, tick by tick, the programme only ever goes on: the same
	 * clip one tick further, or a new clip from its start. A phase boundary never cuts a clip short
	 * or restarts one, except at dawn and where a carry-in hits its cap.
	 */
	@ParameterizedTest
	@MethodSource("stationIds")
	void theProgrammeFlowsAcrossPhaseBoundaries(String id) {
		StationDefinition station = TestStations.load(id);
		long from = start(Phase.DAWN);
		Optional<Slot> previous = BroadcastSchedule.at(station, from);
		for (long dayTime = from + 1; dayTime < from + 24000; dayTime++) {
			Optional<Slot> now = BroadcastSchedule.at(station, dayTime);
			boolean boundary = Phase.of(dayTime) != Phase.of(dayTime - 1);
			if (previous.isPresent() && now.isPresent() && !(boundary && Phase.of(dayTime) == Phase.DAWN)) {
				Slot a = previous.get();
				Slot b = now.get();
				boolean same = a.phase() == b.phase() && a.day() == b.day() && a.sequenceIndex() == b.sequenceIndex();
				if (same) {
					assertEquals(a.offsetSeconds() + 0.05, b.offsetSeconds(), 1e-6, id + " at " + dayTime);
				} else {
					assertEquals(0.0, b.offsetSeconds(), 1e-6, id + " jumps into " + b.clipId() + " at " + dayTime);
					double end = station.clips().get(a.clipId()).duration();
					double capped = BroadcastSchedule.carryIn(station, PhaseInstance.of(dayTime))
						>= PhaseInstance.of(dayTime).lengthSeconds() * BroadcastSchedule.MAX_CARRY_FRACTION - 1e-9 ? 1 : 0;
					if (capped == 0) {
						assertEquals(end, a.offsetSeconds() + 0.05, 0.051, id + " cuts " + a.clipId() + " at " + dayTime);
					}
				}
			}
			previous = now;
		}
	}
}
