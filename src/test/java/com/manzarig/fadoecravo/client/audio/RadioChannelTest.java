package com.manzarig.fadoecravo.client.audio;

import static com.manzarig.fadoecravo.client.audio.RadioTestKit.CARRIER_EVENT;
import static com.manzarig.fadoecravo.client.audio.RadioTestKit.EARLY_DAY;
import static com.manzarig.fadoecravo.client.audio.RadioTestKit.STATIC_EVENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.manzarig.fadoecravo.broadcast.BroadcastSchedule;
import com.manzarig.fadoecravo.broadcast.Phase;
import com.manzarig.fadoecravo.broadcast.Slot;
import com.manzarig.fadoecravo.broadcast.StationDefinition;
import com.manzarig.fadoecravo.client.audio.RadioTestKit.Clock;
import com.manzarig.fadoecravo.client.audio.RadioTestKit.FakeOutput;
import com.manzarig.fadoecravo.net.BreakingNewsPayload;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** The state machine of one radio, driven tick by tick against a recording sound engine and a hand-moved clock. */
class RadioChannelTest {
	private static final BlockPos POS = new BlockPos(5, 64, 5);
	private static final StationDefinition TEJO = RadioTestKit.station("tejo", 1000, 1.0);
	private static final StationDefinition LISBOA = RadioTestKit.station("lisboa", 1100, 1.0);

	private final FakeOutput output = new FakeOutput();
	private final RadioChannel channel = new RadioChannel(POS, output, STATIC_EVENT, CARRIER_EVENT);
	private final Clock clock = new Clock(EARLY_DAY);
	private List<StationDefinition> stations = List.of(TEJO, LISBOA);
	private BreakingNewsPayload news = BreakingNewsPayload.CLEARED;
	private int dial = 1000;
	/** Ticks the day time stands still while the frame still says it runs; for the drift check. */
	private boolean dayTimeStands;

	/** One client tick without moving the clock. */
	private void tick() {
		channel.tick(clock.frame(stations, news), dial);
	}

	/** Moves the clock by one tick and ticks the channel, {@code count} times. */
	private void step(int count) {
		for (int i = 0; i < count; i++) {
			long keep = clock.dayTime;
			clock.advance(1);
			if (dayTimeStands) {
				clock.dayTime = keep;
			}
			tick();
		}
	}

	private static Slot scheduled(StationDefinition station, long dayTime) {
		return BroadcastSchedule.at(station, dayTime).orElseThrow();
	}

	private static double durationOf(StationDefinition station, Slot slot) {
		return station.clips().get(slot.clipId()).duration();
	}

	/** Runs the engine's ticks for a sound until it is silent, as the sound engine would. */
	private static void fadeCompletely(RadioSoundInstance sound) {
		for (int i = 0; i < 15; i++) {
			sound.tick();
		}
	}

	private static void fadeCompletely(StaticSoundInstance sound) {
		for (int i = 0; i < 15; i++) {
			sound.tick();
		}
	}

	// ------------------------------------------------------------------ starting

	@Test
	void startsPartWayIntoTheClipTheScheduleSaysAndFadesIn() {
		tick();
		Slot expected = scheduled(TEJO, clock.dayTime);
		assertTrue(expected.offsetSeconds() > 0, "the test time must fall inside a clip");

		assertEquals(1, output.programmes().size());
		RadioSoundInstance sound = output.lastProgramme();
		assertEquals(expected.sound(), sound.getLocation());
		assertEquals(expected.offsetSeconds(), sound.getOffsetSeconds(), 1e-9);
		assertEquals(0.0f, sound.getCurrentVolume(), "joined part-way, so it starts silent");
		assertEquals(1.0f, sound.getTargetVolume(), "and glides to the signal strength");

		RadioDebugState state = channel.debugState();
		assertEquals(POS, state.pos());
		assertEquals(1000, state.frequencyKhz());
		assertEquals("tejo", state.stationId());
		assertEquals(1.0, state.signal());
		assertEquals(expected.clipId(), state.clipId());
		assertEquals(expected.kind(), state.kind());
		assertEquals(expected.offsetSeconds(), state.offsetSeconds(), 1e-9);
		assertEquals(1.0f, state.programmeVolume());
		assertEquals(0.0f, state.staticVolume());
		assertFalse(state.breaking());
	}

	@Test
	void theOffsetInTheSnapshotAdvancesWithTheTicks() {
		tick();
		double start = channel.debugState().offsetSeconds();
		step(40);
		assertEquals(start + 2.0, channel.debugState().offsetSeconds(), 1e-9);
	}

	@Test
	void aClipThatBeginsExactlyAtItsStartIsNotFadedIn() {
		clock.dayTime = 72_000L + 1000L; // the very start of the day phase: the ident, from 0 s
		tick();
		RadioSoundInstance sound = output.lastProgramme();
		assertEquals(0.0, sound.getOffsetSeconds());
		assertEquals(1.0f, sound.getCurrentVolume());
	}

	@Test
	void staticFillsWhatTheSignalLeavesOpen() {
		stations = List.of(RadioTestKit.station("weak", 1000, 0.5));
		tick();
		assertEquals(0.5f, output.lastProgramme().getTargetVolume(), 1e-6f);
		assertEquals(1, output.loops(STATIC_EVENT).size());
		assertEquals(0.3f, output.loops(STATIC_EVENT).get(0).getTargetVolume(), 1e-6f);
		assertTrue(output.loops(CARRIER_EVENT).isEmpty(), "no hiss while a programme is on");
		assertEquals(0.3f, channel.debugState().staticVolume(), 1e-6f);
	}

	@Test
	void theStaticLoopIsStartedSilentAndWantedEvenAtVolumeZero() {
		tick();
		StaticSoundInstance staticLoop = output.loops(STATIC_EVENT).get(0);
		assertEquals(0.0f, staticLoop.getCurrentVolume());
		assertEquals(0.0f, staticLoop.getTargetVolume(), "a clear signal leaves no static");
		for (int i = 0; i < 30; i++) {
			step(1);
			staticLoop.tick();
		}
		assertFalse(staticLoop.isStopped());
		assertEquals(1, output.loops(STATIC_EVENT).size(), "it is not restarted");
	}

	// ------------------------------------------------------------------ tuning

	@Test
	void turningTheDialWithinOneStationOnlyMovesTheVolumes() {
		tick();
		RadioSoundInstance sound = output.lastProgramme();
		dial = 1010; // 10 kHz off: (15 - 10) / 11
		step(1);
		assertEquals(1, output.programmes().size(), "no restart");
		assertEquals(5.0f / 11.0f, sound.getTargetVolume(), 1e-5f);
		assertEquals((1.0f - 5.0f / 11.0f) * 0.6f, output.loops(STATIC_EVENT).get(0).getTargetVolume(), 1e-5f);
		assertEquals(1010, channel.debugState().frequencyKhz());
		assertFalse(output.stopped.contains(sound));
	}

	@Test
	void changingStationFadesTheOldProgrammeOutAndStartsTheNewOne() {
		tick();
		RadioSoundInstance first = output.lastProgramme();
		for (int i = 0; i < 10; i++) {
			first.tick(); // the fade-in finishes
		}
		dial = 1100;
		step(1);

		assertEquals(2, output.programmes().size());
		assertEquals(0.0f, first.getTargetVolume(), "the old one is fading out");
		assertFalse(first.isStopped());
		fadeCompletely(first);
		assertTrue(first.isStopped());
		RadioSoundInstance second = output.lastProgramme();
		assertEquals(scheduled(LISBOA, clock.dayTime).sound(), second.getLocation());
		assertEquals("lisboa", channel.debugState().stationId());
		assertEquals(1, output.loops(STATIC_EVENT).size(), "the static carries on");
	}

	@Test
	void noStationGivesStaticAndTheCarrierHiss() {
		dial = 1300;
		tick();
		assertTrue(output.programmes().isEmpty());
		assertEquals(0.6f, output.loops(STATIC_EVENT).get(0).getTargetVolume());
		assertEquals(0.5f, output.loops(CARRIER_EVENT).get(0).getTargetVolume());
		RadioDebugState state = channel.debugState();
		assertNull(state.stationId());
		assertEquals(0.0, state.signal());
		assertNull(state.clipId());
		assertNull(state.kind());
		assertEquals(0.0f, state.programmeVolume());
		assertEquals(0.6f, state.staticVolume());
		assertFalse(state.breaking());
	}

	@Test
	void tuningAwayFromAStationFadesItOutAndBringsInTheHiss() {
		tick();
		RadioSoundInstance sound = output.lastProgramme();
		dial = 1300;
		step(1);
		assertEquals(0.0f, sound.getTargetVolume());
		assertEquals(1, output.loops(CARRIER_EVENT).size());
		assertEquals(0.5f, output.loops(CARRIER_EVENT).get(0).getTargetVolume());
		assertEquals(0.6f, output.loops(STATIC_EVENT).get(0).getTargetVolume());
		assertNull(channel.debugState().stationId());
	}

	@Test
	void aStationReloadStartsAgainFromTheSchedule() {
		tick();
		stations = List.of(RadioTestKit.station("tejo", 1000, 1.0), LISBOA); // equal content, new objects
		step(1);
		assertEquals(2, output.programmes().size());
	}

	// ------------------------------------------------------------------ off the air

	@Test
	void aStationThatIsOffTheAirGivesTheHissAndComesOnWhenItsPhaseBegins() {
		StationDefinition nightOnly = RadioTestKit.stationOnlyIn("nightly", 1000, Phase.NIGHT);
		stations = List.of(nightOnly);
		tick();
		assertTrue(output.programmes().isEmpty());
		assertEquals("nightly", channel.debugState().stationId(), "the station is tuned, just silent");
		assertNull(channel.debugState().clipId());
		StaticSoundInstance hiss = output.loops(CARRIER_EVENT).get(0);
		assertEquals(0.5f, hiss.getTargetVolume());
		assertEquals(0.6f, output.loops(STATIC_EVENT).get(0).getTargetVolume());

		clock.dayTime = 72_000L + 13_795L; // five ticks before night
		step(10);
		assertEquals(1, output.programmes().size());
		RadioSoundInstance sound = output.lastProgramme();
		assertEquals(RadioTestKit.id("radio.nightly.ident"), sound.getLocation());
		assertEquals(0.0, sound.getOffsetSeconds(), "the programme begins with the phase");
		assertEquals(0.0f, hiss.getTargetVolume(), "the hiss goes");
		fadeCompletely(hiss);
		assertTrue(hiss.isStopped());
		assertEquals(0.0f, output.loops(STATIC_EVENT).get(0).getTargetVolume());
	}

	@Test
	void whenTheSequenceEndsTheRadioFallsSilent() {
		stations = List.of(RadioTestKit.openingOnlyStation("short", 1000));
		clock.dayTime = 72_000L + 1000L; // the ident is on
		clock.frozen = true;
		tick();
		RadioSoundInstance ident = output.lastProgramme();
		output.end(ident);
		step(1);
		assertEquals(1, output.programmes().size(), "nothing follows the opening");
		assertNull(channel.debugState().clipId());
		assertEquals(1, output.loops(CARRIER_EVENT).size());
	}

	// ------------------------------------------------------------------ continuing

	@Test
	void whenAClipEndsTheNextOneBeginsFromItsStart() {
		clock.frozen = true;
		tick();
		Slot first = scheduled(TEJO, clock.dayTime);
		output.end(output.lastProgramme());
		step(1);

		assertEquals(2, output.programmes().size());
		RadioSoundInstance second = output.lastProgramme();
		Slot expected = BroadcastSchedule.next(TEJO, first).orElseThrow();
		assertEquals(expected.sound(), second.getLocation());
		assertEquals(0.0, second.getOffsetSeconds());
		assertEquals(1.0f, second.getCurrentVolume(), "from its start: no fade-in");
		assertEquals(expected.clipId(), channel.debugState().clipId());
	}

	@Test
	void aFrozenClockNeverRestartsTheClip() {
		clock.frozen = true;
		tick();
		step(150); // 7.5 s with the day time standing still
		assertEquals(1, output.programmes().size());
	}

	@Test
	void aClockThatStandsStillButIsNotFrozenWouldRestartTheClipOverAndOver() {
		dayTimeStands = true;
		tick();
		step(150);
		assertTrue(output.programmes().size() >= 2, "this is what the frozen flag prevents");
	}

	@Test
	void aClipThatEndsALittleLateDoesNotPutTheRadioBehind() {
		tick();
		Slot first = scheduled(TEJO, clock.dayTime);
		RadioSoundInstance firstSound = output.lastProgramme();
		int ticksToNominalEnd = (int) Math.round((durationOf(TEJO, first) - first.offsetSeconds()) * 20.0);

		step(ticksToNominalEnd + 6); // the audio runs 0.3 s longer than the file says
		assertEquals(1, output.programmes().size(), "a small lateness is no reason to restart");
		output.end(firstSound);
		step(1);

		assertEquals(2, output.programmes().size());
		Slot expected = scheduled(TEJO, clock.dayTime);
		RadioSoundInstance second = output.lastProgramme();
		assertEquals(expected.sound(), second.getLocation());
		assertEquals(expected.offsetSeconds(), second.getOffsetSeconds(), 1e-9);
		assertTrue(second.getOffsetSeconds() > 0.25 && second.getOffsetSeconds() < 0.5, "caught up by the lateness: " + second.getOffsetSeconds());
	}

	@Test
	void aClipOfTenSecondsRepeatedAllNightStaysInStepWithTheSchedule() {
		StationDefinition chimes = RadioTestKit.chimeStation("chimes", 1000);
		stations = List.of(chimes);
		tick();
		// Each clip's audio ends 4 ticks after the nominal 10 s; 30 clips would add 6 s of drift if it were not caught up.
		for (int clip = 0; clip < 30; clip++) {
			RadioSoundInstance playing = output.lastProgramme();
			double remaining = chimes.clips().get("chime").duration() - playing.getOffsetSeconds();
			step((int) Math.round(remaining * 20.0) + 4);
			output.end(playing);
			step(1);
		}
		Slot expected = scheduled(chimes, clock.dayTime);
		RadioDebugState state = channel.debugState();
		assertEquals(expected.offsetSeconds(), state.offsetSeconds(), 0.5);
		assertEquals(31, output.programmes().size(), "one start per clip, no resync");
	}

	@Test
	void aClipStillSoundingLongAfterItShouldHaveEndedIsReplaced() {
		clock.frozen = true;
		tick();
		Slot first = scheduled(TEJO, clock.dayTime);
		RadioSoundInstance firstSound = output.lastProgramme();
		long expectedEnd = SyncPolicy.expectedEndTicks(durationOf(TEJO, first), first.offsetSeconds());

		step((int) expectedEnd - 1);
		assertEquals(1, output.programmes().size());
		step(1);
		assertEquals(2, output.programmes().size());
		assertEquals(0.0f, firstSound.getTargetVolume(), "the overdue one is faded out");
		assertEquals(BroadcastSchedule.next(TEJO, first).orElseThrow().sound(), output.lastProgramme().getLocation());
	}

	// ------------------------------------------------------------------ resyncing

	@Test
	void aJumpInTimeBeyondFiveSecondsStartsAgainFromTheSchedule() {
		tick();
		clock.dayTime += 200; // sleeping through ten seconds
		step(1);
		assertEquals(2, output.programmes().size());
		Slot expected = scheduled(TEJO, clock.dayTime);
		assertEquals(expected.sound(), output.lastProgramme().getLocation());
		assertEquals(expected.offsetSeconds(), output.lastProgramme().getOffsetSeconds(), 1e-9);
	}

	@Test
	void aJumpInTimeOfFourSecondsIsLeftAlone() {
		tick();
		clock.dayTime += 80;
		step(5);
		assertEquals(1, output.programmes().size());
	}

	@Test
	void whenThePhaseChangesTheRadioSwitchesOverAtOnceWithoutFading() {
		clock.dayTime = 72_000L + 11_997L; // three ticks before dusk
		tick();
		RadioSoundInstance day = output.lastProgramme();
		step(3);

		assertEquals(2, output.programmes().size());
		assertTrue(output.stopped.contains(day), "a scheduled switch-over cuts the old clip; a fade would open a second stream");
		RadioSoundInstance dusk = output.lastProgramme();
		assertEquals(RadioTestKit.id("radio.tejo.ident"), dusk.getLocation(), "every phase opens with the ident");
		assertEquals(0.0, dusk.getOffsetSeconds());
		assertEquals(Phase.DUSK, scheduled(TEJO, clock.dayTime).phase());
	}

	// ------------------------------------------------------------------ breaking news

	@Test
	void breakingNewsInterruptsTheProgrammeAndHandsBackWhenItIsOver() {
		tick();
		RadioSoundInstance scheduledSound = output.lastProgramme();
		news = new BreakingNewsPayload("tejo", clock.gameTime, 0L);
		step(1);

		assertEquals(2, output.programmes().size());
		RadioSoundInstance bulletin = output.lastProgramme();
		assertEquals(RadioTestKit.id("radio.tejo.flash"), bulletin.getLocation());
		assertEquals(0.0f, scheduledSound.getTargetVolume(), "the programme gives way");
		RadioDebugState state = channel.debugState();
		assertTrue(state.breaking());
		assertEquals("flash", state.clipId());
		assertEquals("breaking", state.kind());

		step(298); // 299 of the 300 ticks of the bulletin
		assertEquals(2, output.programmes().size());
		step(2);
		assertEquals(3, output.programmes().size());
		assertFalse(channel.debugState().breaking());
		assertEquals(scheduled(TEJO, clock.dayTime).sound(), output.lastProgramme().getLocation());
	}

	@Test
	void aBulletinWhoseAudioEndedEarlyIsNotPlayedAgain() {
		tick();
		news = new BreakingNewsPayload("tejo", clock.gameTime, 3L);
		step(1);
		RadioSoundInstance bulletin = output.lastProgramme();
		step(200);
		output.end(bulletin);
		step(1);

		assertFalse(channel.debugState().breaking());
		int count = output.programmes().size();
		step(100); // the clock still says five seconds of the bulletin were left
		assertEquals(count, output.programmes().size());
		assertFalse(channel.debugState().breaking());
	}

	@Test
	void aBulletinForAnotherStationIsIgnored() {
		tick();
		news = new BreakingNewsPayload("lisboa", clock.gameTime, 0L);
		step(20);
		assertEquals(1, output.programmes().size());
		assertFalse(channel.debugState().breaking());
	}

	@Test
	void clearingTheBulletinGoesBackToTheSchedule() {
		tick();
		news = new BreakingNewsPayload("tejo", clock.gameTime, 0L);
		step(20);
		assertTrue(channel.debugState().breaking());
		news = BreakingNewsPayload.CLEARED;
		step(1);
		assertFalse(channel.debugState().breaking());
		assertEquals(scheduled(TEJO, clock.dayTime).sound(), output.lastProgramme().getLocation());
	}

	@Test
	void aNewerBulletinReplacesTheOneOnTheAir() {
		tick();
		news = new BreakingNewsPayload("tejo", clock.gameTime, 0L);
		step(20);
		int before = output.programmes().size();
		news = new BreakingNewsPayload("tejo", clock.gameTime, 1L);
		step(1);
		assertEquals(before + 1, output.programmes().size());
		assertTrue(channel.debugState().breaking());
		assertEquals(0.05, channel.debugState().offsetSeconds(), 1e-9, "the new bulletin starts from its own beginning");
	}

	@Test
	void aBulletinKeepsPlayingThroughAPhaseChange() {
		clock.dayTime = 72_000L + 11_900L;
		tick();
		news = new BreakingNewsPayload("tejo", clock.gameTime, 0L);
		step(1);
		int count = output.programmes().size();
		step(150); // dusk begins at 12000, in the middle of the bulletin
		assertEquals(Phase.DUSK, Phase.of(clock.dayTime));
		assertEquals(count, output.programmes().size());
		assertTrue(channel.debugState().breaking());
	}

	// ------------------------------------------------------------------ the engine

	@Test
	void aSoundThatNeverStartsIsRetriedFromTheScheduleAfterFiveSeconds() {
		output.refuseStarts = true;
		tick();
		assertEquals(1, output.programmes().size());
		step(30);
		assertNull(channel.debugState().clipId(), "given up on after the grace period");
		assertEquals(1, output.loops(CARRIER_EVENT).size(), "meanwhile there is hiss, not a skipped schedule");

		int attempts = output.programmes().size();
		step(90);
		assertEquals(attempts, output.programmes().size(), "waiting");
		output.refuseStarts = false;
		step(20);
		assertEquals(attempts + 1, output.programmes().size());
		Slot expected = scheduled(TEJO, clock.dayTime);
		assertEquals(expected.sound(), output.lastProgramme().getLocation(), "from the schedule, not from the next clip");
		assertEquals(expected.clipId(), channel.debugState().clipId());
	}

	@Test
	void aLoopTheEngineDroppedIsStartedAgain() {
		tick();
		StaticSoundInstance first = output.loops(STATIC_EVENT).get(0);
		output.end(first);
		step(1);
		assertEquals(2, output.loops(STATIC_EVENT).size());
	}

	@Test
	void thereIsOneProgrammeAndOneStaticLoopAtATimeAcrossManyChanges() {
		tick();
		for (int round = 0; round < 40; round++) {
			dial = switch (round % 4) {
				case 0 -> 1000;
				case 1 -> 1100;
				case 2 -> 1300;
				default -> 1012;
			};
			RadioSoundInstance current = output.programmes().isEmpty() ? null : output.lastProgramme();
			step(25);
			if (round % 5 == 4 && current != null) {
				output.end(current);
			}
			step(1);
			long wantedProgrammes = output.programmes().stream().filter(sound -> sound.getTargetVolume() > 0.0f).count();
			assertTrue(wantedProgrammes <= 1, "round " + round + ": " + wantedProgrammes + " programmes wanted");
			assertEquals(1, output.loops(STATIC_EVENT).size(), "the static loop is never doubled");
		}
	}

	// ------------------------------------------------------------------ leaving

	@Test
	void releaseFadesEverythingOutOverHalfASecond() {
		stations = List.of(RadioTestKit.station("weak", 1000, 0.5));
		tick();
		RadioSoundInstance programme = output.lastProgramme();
		StaticSoundInstance staticLoop = output.loops(STATIC_EVENT).get(0);
		for (int i = 0; i < 10; i++) {
			programme.tick();
			staticLoop.tick();
		}
		assertEquals(0.5f, programme.getCurrentVolume(), 1e-6f);

		channel.release();
		assertEquals(0.0f, programme.getTargetVolume());
		assertEquals(0.0f, staticLoop.getTargetVolume());
		assertFalse(programme.isStopped());
		assertFalse(staticLoop.isStopped());
		fadeCompletely(programme);
		fadeCompletely(staticLoop);
		assertTrue(programme.isStopped());
		assertTrue(staticLoop.isStopped());
		assertTrue(output.stopped.isEmpty(), "faded, not cut");
	}

	@Test
	void stopNowCutsEverythingAtOnce() {
		dial = 1300;
		tick();
		channel.stopNow();
		assertEquals(2, output.stopped.size());
	}
}
