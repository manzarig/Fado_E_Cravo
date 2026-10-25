package com.manzarig.fadoecravo.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** {@link BroadcastSchedule} on the three real station files; the synthetic cases are in {@link BroadcastScheduleEdgeCasesTest}. */
class BroadcastScheduleTest {
	private static final double TICK = 1.0 / 20;

	static List<String> stationIds() {
		return TestStations.IDS;
	}

	/** First day time of the phase instance with the given number. Dawn is numbered by the day it ends in. */
	static long phaseStart(Phase phase, long day) {
		return phase == Phase.DAWN ? 24000L * day - 1000 : 24000L * day + phase.startTick();
	}

	static List<Slot> walk(StationDefinition station, Phase phase, long day, int count) {
		return BroadcastSchedule.sequence(station, phase, day).limit(count).toList();
	}

	static List<String> ids(List<Slot> slots) {
		return slots.stream().map(Slot::clipId).toList();
	}

	/** Where each clip of a phase instance begins, in whole ticks, found without looking at the schedule's own arithmetic. */
	record Timeline(StationDefinition station, List<Slot> slots, List<Long> startTicks) {
		static Timeline of(StationDefinition station, Phase phase, long day) {
			List<Slot> slots = walk(station, phase, day, 80);
			List<Long> starts = new ArrayList<>();
			long tick = 0;
			for (Slot slot : slots) {
				starts.add(tick);
				double ticks = station.clips().get(slot.clipId()).duration() * 20;
				assertEquals(Math.round(ticks), ticks, 1e-6, "duration of " + slot.clipId() + " is not a whole number of ticks");
				tick += Math.round(ticks);
			}
			assertTrue(tick > phase.lengthTicks(), "80 slots do not cover the phase");
			return new Timeline(station, slots, starts);
		}

		/** The slot on the air {@code elapsedTicks} into the phase. */
		Slot at(int elapsedTicks) {
			int i = 0;
			while (i + 1 < slots.size() && startTicks.get(i + 1) <= elapsedTicks) {
				i++;
			}
			return slots.get(i).withOffset((elapsedTicks - startTicks.get(i)) / 20.0);
		}
	}

	private static List<Phase> onAirPhases(StationDefinition station) {
		return Arrays.stream(Phase.values()).filter(phase -> station.phases().get(phase).onAir()).toList();
	}

	// --- determinism ---

	@ParameterizedTest
	@MethodSource("stationIds")
	void theSameInputsGiveTheSameSlot(String id) {
		StationDefinition a = TestStations.load(id);
		StationDefinition b = TestStations.load(id); // a separate parse
		for (long dayTime = -30_000; dayTime < 200_000; dayTime += 97) {
			Optional<Slot> first = BroadcastSchedule.at(a, dayTime);
			assertEquals(first, BroadcastSchedule.at(a, dayTime), "dayTime " + dayTime);
			assertEquals(first, BroadcastSchedule.at(b, dayTime), "dayTime " + dayTime);
		}
	}

	@Test
	void differentDaysRotateTheProgramme() {
		StationDefinition tejo = TestStations.load("radio_tejo");
		PhaseDefinition day = tejo.phases().get(Phase.DAY);
		Set<String> firstProgrammeItems = new HashSet<>();
		for (long d = 0; d < 50; d++) {
			firstProgrammeItems.add(walk(tejo, Phase.DAY, d, 1 + day.opening().size()).get(day.opening().size()).clipId());
		}
		// Eight programme items in pairs: four starting points, and 50 days reach them all.
		assertEquals(4, firstProgrammeItems.size(), firstProgrammeItems.toString());
		for (String start : firstProgrammeItems) {
			assertEquals(0, day.programs().indexOf(start) % 2, start + " does not start a pair");
		}
	}

	@Test
	void theHashIsStableAndDependsOnEveryInput() {
		// Values from an independent implementation (Python), so a change to the hash is noticed.
		assertEquals(-98646086506376533L, BroadcastSchedule.hash("radio_tejo", 0, Phase.DAY));
		assertEquals(4456557911056914753L, BroadcastSchedule.hash("radio_tejo", 7, Phase.DAWN));
		assertEquals(6427375330259006444L, BroadcastSchedule.hash("emissora_lusitana", 3, Phase.DUSK));
		assertEquals(3935861603196825760L, BroadcastSchedule.hash("radio_atlantico", 12, Phase.NIGHT));
		assertEquals(2600554210104889202L, BroadcastSchedule.hash("", 0, Phase.DAWN));
		assertEquals(1916063459343017074L, BroadcastSchedule.hash("ç", -5, Phase.NIGHT));

		Set<Long> seen = new HashSet<>();
		for (long day = -500; day < 500; day++) {
			for (Phase phase : Phase.values()) {
				assertTrue(seen.add(BroadcastSchedule.hash("radio_tejo", day, phase)), "collision at " + day + " " + phase);
			}
		}
		assertNotEquals(BroadcastSchedule.hash("radio_tejo", 1, Phase.DAY), BroadcastSchedule.hash("radio_tejo2", 1, Phase.DAY));
	}

	// --- openings ---

	@ParameterizedTest
	@MethodSource("stationIds")
	void theOpeningPlaysFirstAtTheStartOfEveryPhase(String id) {
		StationDefinition station = TestStations.load(id);
		for (Phase phase : onAirPhases(station)) {
			PhaseDefinition definition = station.phases().get(phase);
			for (long day : new long[] {0, 1, 17, -4}) {
				Slot first = BroadcastSchedule.at(station, phaseStart(phase, day)).orElseThrow();
				String where = id + " " + phase + " day " + day;
				assertEquals(phase, first.phase(), where);
				assertEquals(day, first.day(), where);
				assertEquals(0, first.sequenceIndex(), where);
				assertEquals(0.0, first.offsetSeconds(), where);
				if (!definition.opening().isEmpty()) {
					assertEquals(definition.opening().get(0), first.clipId(), where);
				}
				// The whole opening comes first, in the written order, before anything else.
				List<String> played = ids(walk(station, phase, day, definition.opening().size()));
				assertEquals(definition.opening(), played, where);
			}
		}
	}

	@Test
	void lusitanaOpensEachPhaseWithWhatItsFileSays() {
		StationDefinition lusitana = TestStations.load("emissora_lusitana");
		for (Phase phase : Phase.values()) {
			List<String> opening = lusitana.phases().get(phase).opening();
			Slot first = BroadcastSchedule.at(lusitana, phaseStart(phase, 3)).orElseThrow();
			assertEquals(opening.get(0), first.clipId(), phase.toString());
			assertEquals(lusitana.clips().get(opening.get(0)).sound(), first.sound());
			assertEquals(lusitana.clips().get(opening.get(0)).kind(), first.kind());
		}
	}

	@Test
	void theOpeningIsFollowedByTheProgrammeAtItsRotatedStart() {
		for (String id : TestStations.IDS) {
			StationDefinition station = TestStations.load(id);
			for (Phase phase : onAirPhases(station)) {
				PhaseDefinition definition = station.phases().get(phase);
				if (definition.programs().isEmpty()) {
					continue;
				}
				for (long day = 0; day < 20; day++) {
					int step = Math.max(1, definition.interstitialEvery());
					int groups = Math.max(1, definition.programs().size() / step);
					int start = (int) (Math.floorMod(BroadcastSchedule.hash(id, day, phase), (long) groups) * step);
					List<String> sequence = ids(walk(station, phase, day, definition.opening().size() + 1));
					assertEquals(definition.programs().get(start), sequence.get(definition.opening().size()), id + " " + phase + " " + day);
				}
			}
		}
	}

	// --- interstitials ---

	@Test
	void tejoDayHasAnAdAfterExactlyEveryTwoProgrammeItems() {
		StationDefinition tejo = TestStations.load("radio_tejo");
		PhaseDefinition day = tejo.phases().get(Phase.DAY);
		assertEquals(2, day.interstitialEvery());
		for (long d = 0; d < 50; d++) {
			List<Slot> slots = walk(tejo, Phase.DAY, d, 30);
			int sinceInterstitial = 0;
			int interstitials = 0;
			String previousInterstitial = null;
			for (Slot slot : slots.subList(day.opening().size(), slots.size())) {
				if (day.interstitials().contains(slot.clipId())) {
					assertEquals(2, sinceInterstitial, "day " + d + " slot " + slot.sequenceIndex());
					assertEquals("ad", slot.kind());
					sinceInterstitial = 0;
					interstitials++;
					if (previousInterstitial != null) {
						int expected = (day.interstitials().indexOf(previousInterstitial) + 1) % day.interstitials().size();
						assertEquals(day.interstitials().get(expected), slot.clipId(), "interstitials rotate in order");
					}
					previousInterstitial = slot.clipId();
				} else {
					assertTrue(day.programs().contains(slot.clipId()), slot.clipId());
					sinceInterstitial++;
					assertTrue(sinceInterstitial <= 2, "day " + d + ": more than two programme items in a row");
				}
			}
			assertTrue(interstitials >= 8, "only " + interstitials + " interstitials in 30 slots");
		}
	}

	@Test
	void theProgrammeIsPlayedInTheWrittenOrderAndNeverShuffled() {
		for (String id : TestStations.IDS) {
			StationDefinition station = TestStations.load(id);
			for (Phase phase : onAirPhases(station)) {
				PhaseDefinition definition = station.phases().get(phase);
				if (definition.programs().isEmpty()) {
					continue;
				}
				for (long day = 0; day < 10; day++) {
					List<Slot> slots = walk(station, phase, day, 60);
					List<String> programme = slots.subList(definition.opening().size(), slots.size()).stream()
						.map(Slot::clipId)
						.filter(clipId -> definition.programs().contains(clipId))
						.toList();
					int at = definition.programs().indexOf(programme.get(0));
					for (int i = 0; i < programme.size(); i++) {
						assertEquals(definition.programs().get((at + i) % definition.programs().size()), programme.get(i),
							id + " " + phase + " day " + day + " item " + i);
					}
				}
			}
		}
	}

	@Test
	void lusitanaDuskPutsAnInterstitialAfterEveryProgrammeItem() {
		StationDefinition lusitana = TestStations.load("emissora_lusitana");
		PhaseDefinition dusk = lusitana.phases().get(Phase.DUSK);
		assertEquals(1, dusk.interstitialEvery());
		for (long d = 0; d < 10; d++) {
			List<Slot> slots = walk(lusitana, Phase.DUSK, d, 20);
			for (int i = dusk.opening().size(); i < slots.size(); i++) {
				boolean programme = (i - dusk.opening().size()) % 2 == 0;
				assertTrue((programme ? dusk.programs() : dusk.interstitials()).contains(slots.get(i).clipId()),
					"day " + d + " slot " + i + " " + slots.get(i).clipId());
			}
		}
	}

	@Test
	void lusitanaNightLoopsTheIntervalSignalAfterTheSignOff() {
		StationDefinition lusitana = TestStations.load("emissora_lusitana");
		PhaseDefinition night = lusitana.phases().get(Phase.NIGHT);
		assertTrue(night.interstitials().isEmpty());
		List<Slot> slots = walk(lusitana, Phase.NIGHT, 2, 40);
		assertEquals(night.opening(), ids(slots.subList(0, night.opening().size())));
		for (Slot slot : slots.subList(night.opening().size(), slots.size())) {
			assertEquals(night.programs().get(0), slot.clipId());
		}
		Slot late = BroadcastSchedule.at(lusitana, phaseStart(Phase.NIGHT, 2) + Phase.NIGHT.lengthTicks() - 1).orElseThrow();
		assertEquals(night.programs().get(0), late.clipId());
	}

	// --- Rádio Tejo pairs ---

	@Test
	void everyTejoAnnouncementIsFollowedByTheSongItIntroduces() {
		StationDefinition tejo = TestStations.load("radio_tejo");
		Set<String> startsSeen = new HashSet<>();
		int pairs = 0;
		for (long day = 0; day < 50; day++) {
			for (Phase phase : Phase.values()) {
				PhaseDefinition definition = tejo.phases().get(phase);
				List<String> programs = definition.programs();
				List<Slot> slots = walk(tejo, phase, day, 60);
				startsSeen.add(phase + ":" + slots.get(definition.opening().size()).clipId());
				for (int i = 0; i < slots.size() - 1; i++) {
					Slot slot = slots.get(i);
					if (!slot.clipId().startsWith("announce_") && !slot.clipId().equals("request_1")) {
						continue;
					}
					String where = phase + " day " + day + " slot " + i + " " + slot.clipId();
					Slot following = slots.get(i + 1);
					String song = programs.get((programs.indexOf(slot.clipId()) + 1) % programs.size());
					assertEquals(song, following.clipId(), where);
					assertEquals("music", following.kind(), where);
					// And next() agrees, which is what the radio uses to carry on.
					assertEquals(following, BroadcastSchedule.next(tejo, slot).orElseThrow(), where);
					pairs++;
				}
			}
		}
		assertTrue(pairs > 600, "only " + pairs + " pairs checked");
		// Every rotation was exercised, not just one.
		for (Phase phase : Phase.values()) {
			int groups = tejo.phases().get(phase).programs().size() / 2;
			long distinct = startsSeen.stream().filter(start -> start.startsWith(phase + ":")).count();
			assertEquals(groups, distinct, phase.toString());
		}
	}

	// --- offsets ---

	@Test
	void theOffsetIsCorrectMidClip() {
		StationDefinition tejo = TestStations.load("radio_tejo");
		PhaseDefinition day = tejo.phases().get(Phase.DAY);
		List<Slot> slots = walk(tejo, Phase.DAY, 0, 3);
		double firstDuration = tejo.clips().get(slots.get(0).clipId()).duration();
		// 100 ticks into the day phase: 5 seconds into the opening clip.
		Slot five = BroadcastSchedule.at(tejo, 1000 + 100).orElseThrow();
		assertEquals(day.opening().get(0), five.clipId());
		assertEquals(5.0, five.offsetSeconds(), TICK);
		// Half a second into the second clip.
		long secondStart = Math.round(firstDuration * 20);
		Slot second = BroadcastSchedule.at(tejo, 1000 + secondStart + 10).orElseThrow();
		assertEquals(slots.get(1).clipId(), second.clipId());
		assertEquals(1, second.sequenceIndex());
		assertEquals(0.5, second.offsetSeconds(), TICK);
	}

	@ParameterizedTest
	@MethodSource("stationIds")
	void everyTickOfEveryPhaseMatchesAnIndependentWalk(String id) {
		StationDefinition station = TestStations.load(id);
		for (Phase phase : onAirPhases(station)) {
			for (long day : new long[] {0, 3, -2}) {
				Timeline timeline = Timeline.of(station, phase, day);
				for (int tick = 0; tick < phase.lengthTicks(); tick++) {
					String where = id + " " + phase + " day " + day + " tick " + tick;
					Slot actual = BroadcastSchedule.at(station, phaseStart(phase, day) + tick).orElseThrow();
					Slot expected = timeline.at(tick);
					assertEquals(expected.clipId(), actual.clipId(), where);
					assertEquals(expected.sequenceIndex(), actual.sequenceIndex(), where);
					assertEquals(expected.offsetSeconds(), actual.offsetSeconds(), TICK / 100, where);
					assertEquals(phase, actual.phase(), where);
					assertEquals(day, actual.day(), where);
				}
			}
		}
	}

	@ParameterizedTest
	@MethodSource("stationIds")
	void theOffsetGrowsWithinAClipAndRestartsAtTheNext(String id) {
		StationDefinition station = TestStations.load(id);
		for (Phase phase : onAirPhases(station)) {
			Slot previous = null;
			for (int tick = 0; tick < phase.lengthTicks(); tick++) {
				Slot slot = BroadcastSchedule.at(station, phaseStart(phase, 5) + tick).orElseThrow();
				double duration = station.clips().get(slot.clipId()).duration();
				assertTrue(slot.offsetSeconds() >= 0 && slot.offsetSeconds() < duration, id + " " + phase + " tick " + tick);
				if (previous != null) {
					if (previous.sequenceIndex() == slot.sequenceIndex()) {
						assertEquals(previous.offsetSeconds() + TICK, slot.offsetSeconds(), 1e-9, id + " " + phase + " tick " + tick);
					} else {
						assertEquals(previous.sequenceIndex() + 1, slot.sequenceIndex(), id + " " + phase + " tick " + tick);
						assertTrue(slot.offsetSeconds() < TICK, "a new clip starts at its beginning, not at " + slot.offsetSeconds());
					}
				}
				previous = slot;
			}
		}
	}

	@Test
	void aDawnPlaysStraightThroughMidnight() {
		StationDefinition tejo = TestStations.load("radio_tejo");
		for (long day : new long[] {1, 2, 9, 10_000}) {
			Slot before = BroadcastSchedule.at(tejo, 24000 * day - 1).orElseThrow();
			Slot after = BroadcastSchedule.at(tejo, 24000 * day).orElseThrow();
			assertEquals(Phase.DAWN, before.phase());
			assertEquals(Phase.DAWN, after.phase());
			assertEquals(before.day(), after.day());
			assertEquals(day, after.day());
			// One tick later in the same clip, or the next clip from its start.
			if (before.sequenceIndex() == after.sequenceIndex()) {
				assertEquals(before.offsetSeconds() + TICK, after.offsetSeconds(), 1e-9);
			} else {
				assertEquals(before.sequenceIndex() + 1, after.sequenceIndex());
			}
			// 1000 ticks (50 s) into the dawn is midnight itself.
			assertEquals(Timeline.of(tejo, Phase.DAWN, day).at(1000).clipId(), after.clipId());
		}
	}

	// --- clock jumps ---

	@Test
	void jumpingFromDayToNightGivesANightSlot() {
		StationDefinition tejo = TestStations.load("radio_tejo");
		for (long days : new long[] {0, 1, 5, 400}) {
			Slot day = BroadcastSchedule.at(tejo, 24000 * days + 6000).orElseThrow();
			assertEquals(Phase.DAY, day.phase());
			assertEquals(days, day.day());
			// /time set 18000, whether or not the world keeps counting days
			for (long night : new long[] {18000, 24000 * days + 18000}) {
				Slot jumped = BroadcastSchedule.at(tejo, night).orElseThrow();
				assertEquals(Phase.NIGHT, jumped.phase());
				assertEquals(night / 24000, jumped.day());
				assertEquals(Timeline.of(tejo, Phase.NIGHT, jumped.day()).at(18000 - 13800).clipId(), jumped.clipId());
				assertEquals(Timeline.of(tejo, Phase.NIGHT, jumped.day()).at(18000 - 13800).offsetSeconds(), jumped.offsetSeconds(), 1e-9);
				assertTrue(tejo.phases().get(Phase.NIGHT).clipIds().contains(jumped.clipId()));
			}
			// There is no memory: going back gives the day slot again.
			assertEquals(day, BroadcastSchedule.at(tejo, 24000 * days + 6000).orElseThrow());
		}
	}

	@Test
	void atlanticoIsOnAirAtNightAndOffAirOtherwise() {
		StationDefinition atlantico = TestStations.load("radio_atlantico");
		for (long dayTime = -24000; dayTime < 3 * 24000; dayTime++) {
			boolean night = Phase.of(dayTime) == Phase.NIGHT;
			assertEquals(night, BroadcastSchedule.at(atlantico, dayTime).isPresent(), "dayTime " + dayTime);
		}
		assertTrue(BroadcastSchedule.at(atlantico, 13799).isEmpty());
		assertEquals("interval_atlantico", BroadcastSchedule.at(atlantico, 13800).orElseThrow().clipId());
		assertTrue(BroadcastSchedule.at(atlantico, 22999).isPresent());
		assertTrue(BroadcastSchedule.at(atlantico, 23000).isEmpty());
		assertTrue(BroadcastSchedule.at(atlantico, 6000).isEmpty());
		assertTrue(BroadcastSchedule.at(atlantico, 18000).isPresent());
		assertTrue(BroadcastSchedule.at(atlantico, 24000 * 7 + 6000).isEmpty());
		assertTrue(BroadcastSchedule.at(atlantico, 24000 * 7 + 18000).isPresent());
	}

	@ParameterizedTest
	@MethodSource("stationIds")
	void extremeDayTimesDoNotThrow(String id) {
		StationDefinition station = TestStations.load(id);
		for (long dayTime : new long[] {Long.MIN_VALUE, Long.MIN_VALUE + 1, -1, Long.MAX_VALUE - 1, Long.MAX_VALUE}) {
			Optional<Slot> slot = BroadcastSchedule.at(station, dayTime);
			slot.ifPresent(s -> assertEquals(Phase.of(dayTime), s.phase()));
		}
	}

	// --- next() ---

	@ParameterizedTest
	@MethodSource("stationIds")
	void nextEqualsAtEvaluatedAtTheEndOfTheClip(String id) {
		StationDefinition station = TestStations.load(id);
		int checked = 0;
		for (Phase phase : onAirPhases(station)) {
			for (long day : new long[] {0, 6}) {
				long start = phaseStart(phase, day);
				for (int tick = 0; tick < phase.lengthTicks(); tick++) {
					Slot slot = BroadcastSchedule.at(station, start + tick).orElseThrow();
					double remainingTicks = (station.clips().get(slot.clipId()).duration() - slot.offsetSeconds()) * 20;
					long end = tick + Math.round(remainingTicks);
					assertEquals(Math.round(remainingTicks), remainingTicks, 1e-6, "clips end on a tick");
					if (end >= phase.lengthTicks()) {
						// The next clip begins after the phase is over; next() still names it.
						assertEquals(slot.sequenceIndex() + 1, BroadcastSchedule.next(station, slot).orElseThrow().sequenceIndex());
						continue;
					}
					Slot expected = BroadcastSchedule.at(station, start + end).orElseThrow();
					Optional<Slot> next = BroadcastSchedule.next(station, slot);
					assertEquals(Optional.of(expected), next, id + " " + phase + " day " + day + " tick " + tick);
					assertEquals(0.0, next.orElseThrow().offsetSeconds());
					assertEquals(slot.phase(), next.orElseThrow().phase());
					assertEquals(slot.day(), next.orElseThrow().day());
					checked++;
				}
			}
		}
		assertTrue(checked > 1000, "only " + checked + " boundaries checked");
	}

	@ParameterizedTest
	@MethodSource("stationIds")
	void chainingNextWalksTheSequenceWithoutTheClock(String id) {
		StationDefinition station = TestStations.load(id);
		for (Phase phase : onAirPhases(station)) {
			List<Slot> expected = walk(station, phase, 4, 50);
			Slot slot = BroadcastSchedule.at(station, phaseStart(phase, 4)).orElseThrow();
			for (int i = 0; i < expected.size(); i++) {
				assertEquals(expected.get(i), slot, id + " " + phase + " item " + i);
				slot = BroadcastSchedule.next(station, slot).orElseThrow();
			}
		}
	}

	@Test
	void nextIsEmptyForAnOffAirPhaseAndForBreakingNews() {
		StationDefinition atlantico = TestStations.load("radio_atlantico");
		Slot night = BroadcastSchedule.at(atlantico, 18000).orElseThrow();
		assertTrue(BroadcastSchedule.next(atlantico, night).isPresent());
		assertTrue(BroadcastSchedule.next(atlantico, night.withOffset(3.0)).isPresent());
		Slot daySlot = new Slot(night.clipId(), night.sound(), night.kind(), 0, Phase.DAY, 0, 0);
		assertTrue(BroadcastSchedule.next(atlantico, daySlot).isEmpty());

		Slot bulletin = BroadcastSchedule.breaking(atlantico, 1000, 0, 1000).orElseThrow();
		assertTrue(BroadcastSchedule.next(atlantico, bulletin).isEmpty());
	}

	// --- breaking() ---

	@Test
	void breakingNewsRunsFromItsStartUntilTheClipEnds() {
		StationDefinition tejo = TestStations.load("radio_tejo");
		ClipInfo clip = tejo.clips().get(tejo.breaking().get(0));
		long ticks = Math.round(clip.duration() * 20);
		assertEquals(Math.round(clip.duration() * 20), clip.duration() * 20, 1e-6);
		long start = 123_456;

		Optional<Slot> atStart = BroadcastSchedule.breaking(tejo, start, 7, start);
		assertEquals(tejo.breaking().get(0), atStart.orElseThrow().clipId());
		assertEquals(clip.sound(), atStart.orElseThrow().sound());
		assertEquals("breaking", atStart.orElseThrow().kind());
		assertEquals(0.0, atStart.orElseThrow().offsetSeconds());
		assertEquals(Slot.BREAKING_INDEX, atStart.orElseThrow().sequenceIndex());

		Slot middle = BroadcastSchedule.breaking(tejo, start, 7, start + ticks / 2).orElseThrow();
		assertEquals(clip.duration() / 2, middle.offsetSeconds(), TICK);

		Slot last = BroadcastSchedule.breaking(tejo, start, 7, start + ticks - 1).orElseThrow();
		assertEquals(clip.duration() - TICK, last.offsetSeconds(), 1e-9);

		assertTrue(BroadcastSchedule.breaking(tejo, start, 7, start + ticks).isEmpty(), "empty once the offset reaches the duration");
		assertTrue(BroadcastSchedule.breaking(tejo, start, 7, start + ticks + 1).isEmpty());
		assertTrue(BroadcastSchedule.breaking(tejo, start, 7, start + 10 * ticks).isEmpty());
		assertTrue(BroadcastSchedule.breaking(tejo, start, 7, start - 1).isEmpty());
		assertTrue(BroadcastSchedule.breaking(tejo, start, 7, Long.MIN_VALUE).isEmpty());
	}

	@ParameterizedTest
	@MethodSource("stationIds")
	void everyStationHasWorkingBreakingNews(String id) {
		StationDefinition station = TestStations.load(id);
		for (long seed : new long[] {0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, 123456789L}) {
			Slot slot = BroadcastSchedule.breaking(station, 5000, seed, 5000).orElseThrow();
			assertTrue(station.breaking().contains(slot.clipId()));
			assertEquals(slot, BroadcastSchedule.breaking(station, 5000, seed, 5000).orElseThrow(), "deterministic by seed");
			assertEquals(slot.withOffset(10.0), BroadcastSchedule.breaking(station, 5000, seed, 5200).orElseThrow());
		}
	}

	@Test
	void breakingNewsPicksTheClipBySeed() {
		StationDefinition station = TestStations.station("news")
			.clip("b0", "breaking", 10.0).clip("b1", "breaking", 20.0).clip("b2", "breaking", 30.0)
			.breaking("b0", "b1", "b2")
			.build();
		String[] expected = {"b0", "b1", "b2", "b0", "b1"};
		for (int seed = 0; seed < expected.length; seed++) {
			assertEquals(expected[seed], BroadcastSchedule.breaking(station, 0, seed, 0).orElseThrow().clipId(), "seed " + seed);
		}
		assertEquals("b2", BroadcastSchedule.breaking(station, 0, -1, 0).orElseThrow().clipId());
		assertEquals("b1", BroadcastSchedule.breaking(station, 0, -2, 0).orElseThrow().clipId());
		// Each clip ends on its own duration.
		assertTrue(BroadcastSchedule.breaking(station, 0, 0, 199).isPresent());
		assertTrue(BroadcastSchedule.breaking(station, 0, 0, 200).isEmpty());
		assertTrue(BroadcastSchedule.breaking(station, 0, 2, 599).isPresent());
		assertTrue(BroadcastSchedule.breaking(station, 0, 2, 600).isEmpty());
		// The same inputs, the same answer, however often it is asked.
		for (int i = 0; i < 3; i++) {
			assertEquals(BroadcastSchedule.breaking(station, 40, 12345, 90), BroadcastSchedule.breaking(station, 40, 12345, 90));
		}
	}

	@Test
	void breakingNewsIsEmptyWithoutAUsableClip() {
		StationDefinition none = TestStations.station("quiet").clip("song", "music", 10.0).build();
		assertTrue(BroadcastSchedule.breaking(none, 0, 0, 0).isEmpty());
		StationDefinition ghost = TestStations.station("ghost").clip("song", "music", 10.0).breaking("missing").build();
		assertTrue(BroadcastSchedule.breaking(ghost, 0, 0, 0).isEmpty());
		assertFalse(BroadcastSchedule.breaking(TestStations.load("radio_tejo"), 0, 0, 0).isEmpty());
	}
}
