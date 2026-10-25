package com.manzarig.fadoecravo.broadcast;

import static com.manzarig.fadoecravo.broadcast.BroadcastScheduleTest.ids;
import static com.manzarig.fadoecravo.broadcast.BroadcastScheduleTest.phaseStart;
import static com.manzarig.fadoecravo.broadcast.BroadcastScheduleTest.walk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** {@link BroadcastSchedule} on small stations built in code: exact sequences and the odd inputs. */
class BroadcastScheduleEdgeCasesTest {
	private static final double TICK = 1.0 / 20;

	/** A station whose phases cover every shape of sequence. */
	private static StationDefinition golden() {
		return TestStations.station("golden")
			.clip("o0", "ident", 5.0).clip("o1", "ident", 6.0)
			.clip("p0", "music", 30.0).clip("p1", "music", 31.0).clip("p2", "music", 32.0)
			.clip("p3", "music", 33.0).clip("p4", "music", 34.0).clip("p5", "music", 35.0)
			.clip("i0", "ad", 10.0).clip("i1", "ad", 11.0).clip("i2", "ad", 12.0)
			.phase(Phase.DAY, List.of("o0", "o1"), List.of("p0", "p1", "p2", "p3", "p4", "p5"), List.of("i0", "i1", "i2"), 2)
			.phase(Phase.DUSK, List.of(), List.of("p0", "p1", "p2"), List.of("i0", "i1"), 1)
			.phase(Phase.DAWN, List.of("o0"), List.of(), List.of("i0", "i1", "i2"), 2)
			.phase(Phase.NIGHT, List.of(), List.of("p0", "p1", "p2", "p3"), List.of("i0"), 3)
			.build();
	}

	/** Phase, day, expected hash and first 14 clips, produced by an independent Python implementation of the documented rules. */
	static Stream<Arguments> goldenSequences() {
		return Stream.of(
			Arguments.of(Phase.DAY, 0L, 7458419982402006206L, "o0 o1 p4 p5 i0 p0 p1 i1 p2 p3 i2 p4 p5 i0"),
			Arguments.of(Phase.DAY, 5L, -5065420741532295366L, "o0 o1 p0 p1 i2 p2 p3 i0 p4 p5 i1 p0 p1 i2"),
			Arguments.of(Phase.DAY, -3L, -2383815246122436356L, "o0 o1 p2 p3 i1 p4 p5 i2 p0 p1 i0 p2 p3 i1"),
			Arguments.of(Phase.DAY, 100L, -6935586793023233232L, "o0 o1 p0 p1 i0 p2 p3 i1 p4 p5 i2 p0 p1 i0"),
			Arguments.of(Phase.DUSK, 0L, 6589676086371258070L, "p1 i0 p2 i1 p0 i0 p1 i1 p2 i0 p0 i1 p1 i0"),
			Arguments.of(Phase.DUSK, 5L, -5326556916982969673L, "p1 i0 p2 i1 p0 i0 p1 i1 p2 i0 p0 i1 p1 i0"),
			Arguments.of(Phase.DUSK, -3L, -6225306621577866515L, "p1 i0 p2 i1 p0 i0 p1 i1 p2 i0 p0 i1 p1 i0"),
			Arguments.of(Phase.DUSK, 100L, -6074626216936647092L, "p1 i1 p2 i0 p0 i1 p1 i0 p2 i1 p0 i0 p1 i1"),
			Arguments.of(Phase.DAWN, 0L, 8143967569271666909L, "o0 i1 i2 i0 i1 i2 i0 i1 i2 i0 i1 i2 i0 i1"),
			Arguments.of(Phase.DAWN, 5L, 947371002166107083L, "o0 i2 i0 i1 i2 i0 i1 i2 i0 i1 i2 i0 i1 i2"),
			Arguments.of(Phase.DAWN, -3L, -8475673562665773267L, "o0 i2 i0 i1 i2 i0 i1 i2 i0 i1 i2 i0 i1 i2"),
			Arguments.of(Phase.DAWN, 100L, 5616642443998872975L, "o0 i0 i1 i2 i0 i1 i2 i0 i1 i2 i0 i1 i2 i0"),
			Arguments.of(Phase.NIGHT, 0L, 4801807309562466536L, "p0 p1 p2 i0 p3 p0 p1 i0 p2 p3 p0 i0 p1 p2"),
			Arguments.of(Phase.NIGHT, 5L, -2810572949034367979L, "p0 p1 p2 i0 p3 p0 p1 i0 p2 p3 p0 i0 p1 p2"),
			Arguments.of(Phase.NIGHT, -3L, -8027090618888021960L, "p0 p1 p2 i0 p3 p0 p1 i0 p2 p3 p0 i0 p1 p2"),
			Arguments.of(Phase.NIGHT, 100L, -1387168083822191105L, "p0 p1 p2 i0 p3 p0 p1 i0 p2 p3 p0 i0 p1 p2"));
	}

	@ParameterizedTest
	@MethodSource("goldenSequences")
	void sequencesFollowTheDocumentedRulesExactly(Phase phase, long day, long hash, String expected) {
		StationDefinition station = golden();
		assertEquals(hash, BroadcastSchedule.hash("golden", day, phase));
		List<Slot> slots = walk(station, phase, day, 14);
		assertEquals(List.of(expected.split(" ")), ids(slots));
		for (int i = 0; i < slots.size(); i++) {
			assertEquals(i, slots.get(i).sequenceIndex());
			assertEquals(phase, slots.get(i).phase());
			assertEquals(day, slots.get(i).day());
			assertEquals(0.0, slots.get(i).offsetSeconds());
		}
	}

	@Test
	void offsetsFollowTheClipDurations() {
		// Clips of 10.0 s and 4.5 s alternate: 200 and 90 ticks.
		StationDefinition station = TestStations.station("mini")
			.clip("a", "music", 10.0).clip("b", "ad", 4.5)
			.phase(Phase.DAY, List.of(), List.of("a"), List.of("b"), 1)
			.build();
		long start = 1000;
		assertSlot(station, start, "a", 0, 0.0);
		assertSlot(station, start + 199, "a", 0, 9.95);
		assertSlot(station, start + 200, "b", 1, 0.0);
		assertSlot(station, start + 250, "b", 1, 2.5);
		assertSlot(station, start + 289, "b", 1, 4.45);
		assertSlot(station, start + 290, "a", 2, 0.0);
		assertSlot(station, start + 490, "b", 3, 0.0);
		// A whole number of loops later, the same minute, in the next game day.
		assertSlot(station, 24000 + start + 290, "a", 2, 0.0);
	}

	private static void assertSlot(StationDefinition station, long dayTime, String clipId, int index, double offset) {
		Slot slot = BroadcastSchedule.at(station, dayTime).orElseThrow();
		assertEquals(clipId, slot.clipId(), "dayTime " + dayTime);
		assertEquals(index, slot.sequenceIndex(), "dayTime " + dayTime);
		assertEquals(offset, slot.offsetSeconds(), TICK / 100, "dayTime " + dayTime);
	}

	@Test
	void clipsThatAreNotWholeTicksStillTileTheTime() {
		// 12.48 s = 249.6 ticks: boundaries fall between ticks and the walk must not drift.
		StationDefinition station = TestStations.station("odd")
			.clip("a", "music", 12.48).clip("b", "music", 7.77)
			.phase(Phase.DAY, List.of(), List.of("a", "b"), List.of(), 0)
			.build();
		boolean startsWithA = BroadcastSchedule.at(station, 1000).orElseThrow().clipId().equals("a");
		// The check is done in exact integer hundredths of a second; one tick is 5.
		long[] hundredths = startsWithA ? new long[] {1248, 777} : new long[] {777, 1248};
		String[] names = startsWithA ? new String[] {"a", "b"} : new String[] {"b", "a"};
		for (int tick = 0; tick < 11000; tick++) {
			long elapsed = tick * 5L;
			int index = 0;
			long begin = 0;
			while (elapsed >= begin + hundredths[index % 2]) {
				begin += hundredths[index % 2];
				index++;
			}
			Slot slot = BroadcastSchedule.at(station, 1000 + tick).orElseThrow();
			assertEquals(index, slot.sequenceIndex(), "tick " + tick);
			assertEquals(names[index % 2], slot.clipId(), "tick " + tick);
			assertEquals((elapsed - begin) / 100.0, slot.offsetSeconds(), 1e-9, "tick " + tick);
		}
	}

	// --- the shape of the lists ---

	@Test
	void interstitialsWithoutProgrammesLoopOnTheirOwn() {
		StationDefinition station = golden();
		List<String> loop = ids(walk(station, Phase.DAWN, 0, 40)).subList(1, 40);
		for (int i = 0; i < loop.size(); i++) {
			assertEquals("i" + (1 + i) % 3, loop.get(i));
		}
		// ... and at() reaches them: 5 s of o0, then 11 s of i1, then i2.
		assertSlot(station, phaseStart(Phase.DAWN, 0) + 99, "o0", 0, 4.95);
		assertSlot(station, phaseStart(Phase.DAWN, 0) + 100, "i1", 1, 0.0);
		assertSlot(station, phaseStart(Phase.DAWN, 0) + 319, "i1", 1, 10.95);
		assertSlot(station, phaseStart(Phase.DAWN, 0) + 320, "i2", 2, 0.0);
	}

	@Test
	void anOpeningWithNothingToLoopEndsTheBroadcast() {
		StationDefinition station = TestStations.station("brief")
			.clip("a", "ident", 10.0).clip("b", "ident", 4.0)
			.phase(Phase.DAY, List.of("a", "b"), List.of(), List.of(), 0)
			.build();
		long start = phaseStart(Phase.DAY, 0);
		assertEquals(List.of("a", "b"), ids(walk(station, Phase.DAY, 0, 50)));
		assertSlot(station, start, "a", 0, 0.0);
		assertSlot(station, start + 199, "a", 0, 9.95);
		assertSlot(station, start + 200, "b", 1, 0.0);
		assertSlot(station, start + 279, "b", 1, 3.95);
		assertTrue(BroadcastSchedule.at(station, start + 280).isEmpty(), "off air once the opening is over");
		assertTrue(BroadcastSchedule.at(station, start + 5000).isEmpty());
		Slot last = BroadcastSchedule.at(station, start + 250).orElseThrow();
		assertTrue(BroadcastSchedule.next(station, last).isEmpty());
		assertEquals("b", BroadcastSchedule.next(station, BroadcastSchedule.at(station, start).orElseThrow()).orElseThrow().clipId());
	}

	@Test
	void anOnAirPhaseWithNothingAtAllIsOffAir() {
		StationDefinition station = TestStations.station("blank")
			.clip("a", "music", 10.0)
			.phase(Phase.DAY, List.of(), List.of(), List.of(), 2)
			.build();
		assertTrue(BroadcastSchedule.at(station, 5000).isEmpty());
		assertEquals(0, BroadcastSchedule.sequence(station, Phase.DAY, 0).count());
	}

	@Test
	void offAirMissingAndUnlistedPhasesAreOffAir() {
		StationDefinition station = TestStations.station("sparse")
			.clip("a", "music", 10.0)
			.phase(Phase.DAY, new PhaseDefinition(false, List.of("a"), List.of("a"), List.of("a"), 1))
			.phase(Phase.NIGHT, List.of(), List.of("a"), List.of(), 0)
			.build();
		assertTrue(BroadcastSchedule.at(station, 5000).isEmpty(), "on_air false wins over the lists");
		assertTrue(BroadcastSchedule.at(station, 12500).isEmpty(), "dusk is missing");
		assertTrue(BroadcastSchedule.at(station, 0).isEmpty(), "dawn is missing");
		assertTrue(BroadcastSchedule.at(station, 18000).isPresent());
		assertEquals(0, BroadcastSchedule.sequence(station, Phase.DAY, 0).count());
	}

	@Test
	void fewerProgrammesThanTheSpacingStillWorks() {
		// One programme item but an interstitial every two: the item repeats, with no division by zero.
		StationDefinition station = TestStations.station("short")
			.clip("a", "music", 10.0).clip("b", "ad", 5.0)
			.phase(Phase.DAY, List.of(), List.of("a"), List.of("b"), 2)
			.build();
		for (long day = 0; day < 20; day++) {
			assertEquals(List.of("a", "a", "b", "a", "a", "b", "a"), ids(walk(station, Phase.DAY, day, 7)));
		}
	}

	@Test
	void aProgrammeThatIsNotAMultipleOfTheSpacingStartsAtZero() {
		StationDefinition station = TestStations.station("odd")
			.clip("p0", "music", 10.0).clip("p1", "music", 10.0).clip("p2", "music", 10.0).clip("i", "ad", 5.0)
			.phase(Phase.DAY, List.of(), List.of("p0", "p1", "p2"), List.of("i"), 2)
			.build();
		for (long day = 0; day < 20; day++) {
			assertEquals(List.of("p0", "p1", "i", "p2", "p0", "i", "p1", "p2", "i", "p0"), ids(walk(station, Phase.DAY, day, 10)));
		}
	}

	@Test
	void interstitialsAreIgnoredWhenTheSpacingIsZero() {
		StationDefinition station = TestStations.station("plain")
			.clip("p0", "music", 10.0).clip("p1", "music", 10.0).clip("i", "ad", 5.0)
			.phase(Phase.DAY, List.of(), List.of("p0", "p1"), List.of("i"), 0)
			.build();
		List<String> sequence = ids(walk(station, Phase.DAY, 0, 9));
		assertTrue(sequence.stream().noneMatch("i"::equals), sequence.toString());
	}

	// --- bad data must not hang or throw ---

	@Test
	void zeroAndNegativeDurationsStillAdvanceTheWalk() {
		StationDefinition station = TestStations.station("broken")
			.clip("z", "music", 0.0).clip("n", "music", -5.0).clip("x", "music", Double.NaN)
			.phase(Phase.DAY, List.of(), List.of("z", "n", "x"), List.of(), 0)
			.build();
		assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
			for (int tick = 0; tick < 11000; tick += 13) {
				Slot slot = BroadcastSchedule.at(station, 1000 + tick).orElseThrow();
				// Every clip counts for one tick, so the clip index is the tick.
				assertEquals(tick, slot.sequenceIndex());
				assertEquals(0.0, slot.offsetSeconds(), 1e-9);
			}
		});
	}

	@Test
	void aMixOfZeroLengthAndNormalClipsStaysConsistent() {
		StationDefinition station = TestStations.station("mixed")
			.clip("z", "music", 0.0).clip("a", "music", 10.0)
			.phase(Phase.DAY, List.of(), List.of("a", "z"), List.of(), 0)
			.build();
		assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
			Slot slot = BroadcastSchedule.at(station, 1000).orElseThrow();
			for (int guard = 0; guard < 100; guard++) {
				Slot next = BroadcastSchedule.next(station, slot).orElseThrow();
				assertEquals(slot.sequenceIndex() + 1, next.sequenceIndex());
				slot = next;
			}
		});
	}

	@Test
	void unknownClipIdsAreSkipped() {
		StationDefinition station = TestStations.station("typos")
			.clip("a", "music", 10.0)
			.phase(Phase.DAY, List.of("ghost"), List.of("ghost", "a", "ghost2"), List.of(), 0)
			.build();
		List<Slot> slots = walk(station, Phase.DAY, 0, 4);
		assertEquals(List.of("a", "a", "a", "a"), ids(slots));
		// 'a' is one of three programme items, so it comes round every three positions.
		Slot first = BroadcastSchedule.at(station, 1000).orElseThrow();
		assertEquals("a", first.clipId());
		assertEquals(first.sequenceIndex() + 3, BroadcastSchedule.next(station, first).orElseThrow().sequenceIndex());
	}

	@Test
	void aPhaseOfOnlyUnknownClipIdsIsOffAirAndDoesNotHang() {
		StationDefinition station = TestStations.station("lost")
			.clip("a", "music", 10.0)
			.phase(Phase.DAY, List.of("ghost"), List.of("ghost"), List.of("ghost"), 1)
			.build();
		assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
			assertTrue(BroadcastSchedule.at(station, 5000).isEmpty());
			assertEquals(0, BroadcastSchedule.sequence(station, Phase.DAY, 0).count());
		});
	}

	@Test
	void sequenceIsLazy() {
		StationDefinition station = golden();
		// Takes only what it needs from an endless sequence.
		assertEquals(3, BroadcastSchedule.sequence(station, Phase.DAY, 1).limit(3).count());
		assertEquals(5000, BroadcastSchedule.sequence(station, Phase.DAY, 1).skip(5000).findFirst().orElseThrow().sequenceIndex());
	}
}
