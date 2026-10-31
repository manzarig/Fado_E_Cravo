package com.manzarig.fadoecravo.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.manzarig.fadoecravo.broadcast.Phase;
import com.manzarig.fadoecravo.broadcast.Slot;
import com.manzarig.fadoecravo.client.audio.SyncPolicy.PlayState;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SyncPolicyTest {
	private static final ResourceLocation SOUND = ResourceLocation.fromNamespaceAndPath("fadoecravo", "radio.test.clip");

	private static Slot slot(String clip, double offset, int index) {
		return new Slot(clip, SOUND, "music", offset, Phase.DAY, 3L, index);
	}

	// ------------------------------------------------------------------ driftSeconds

	@Test
	void theSameClipDriftsByTheDifferenceOfThePositions() {
		assertEquals(0.0, SyncPolicy.driftSeconds(slot("a", 10, 4), 10.0, 60, slot("a", 10.0, 4), 60));
		assertEquals(3.5, SyncPolicy.driftSeconds(slot("a", 10, 4), 13.5, 60, slot("a", 10.0, 4), 60), 1e-9);
		assertEquals(3.5, SyncPolicy.driftSeconds(slot("a", 10, 4), 10.0, 60, slot("a", 13.5, 4), 60), 1e-9);
	}

	@Test
	void theDriftIsMeasuredAcrossTheBoundaryWhenTheScheduleIsOneClipOn() {
		// Playing "a" 58 s into 60: 2 s left. The schedule is 1.5 s into "b". The radio is 3.5 s behind.
		assertEquals(3.5, SyncPolicy.driftSeconds(slot("a", 0, 4), 58.0, 60, slot("b", 1.5, 5), 40), 1e-9);
	}

	@Test
	void theDriftIsMeasuredAcrossTheBoundaryWhenTheScheduleIsOneClipBack() {
		// Playing "b" 1 s in; the schedule is still in "a", 59 s of 60: 1 s left there. The radio is 2 s ahead.
		assertEquals(2.0, SyncPolicy.driftSeconds(slot("b", 0, 5), 1.0, 40, slot("a", 59.0, 4), 60), 1e-9);
	}

	@Test
	void aPositionPastTheEndOfTheClipCountsAsTheEnd() {
		assertEquals(0.3, SyncPolicy.driftSeconds(slot("a", 0, 4), 61.0, 60, slot("b", 0.3, 5), 40), 1e-9);
	}

	@Test
	void clipsMoreThanOneApartAreInfinitelyFar() {
		assertEquals(Double.POSITIVE_INFINITY, SyncPolicy.driftSeconds(slot("a", 0, 4), 1.0, 60, slot("c", 0.0, 6), 20));
		assertEquals(Double.POSITIVE_INFINITY, SyncPolicy.driftSeconds(slot("a", 0, 4), 1.0, 60, slot("c", 0.0, 2), 20));
	}

	@Test
	void anotherPhaseInstanceCannotBeCompared() {
		Slot nextDay = new Slot("a", SOUND, "music", 0.0, Phase.DAY, 4L, 4);
		Slot nextPhase = new Slot("b", SOUND, "music", 0.0, Phase.DUSK, 3L, 5);
		assertEquals(Double.POSITIVE_INFINITY, SyncPolicy.driftSeconds(slot("a", 0, 4), 1.0, 60, nextDay, 60));
		assertEquals(Double.POSITIVE_INFINITY, SyncPolicy.driftSeconds(slot("a", 0, 4), 1.0, 60, nextPhase, 60));
	}

	@Test
	void aBulletinIsOnlyComparedWithItself() {
		Slot bulletin = new Slot("flash", SOUND, "breaking", 4.0, Phase.DAY, 3L, Slot.BREAKING_INDEX);
		assertEquals(1.0, SyncPolicy.driftSeconds(bulletin, 5.0, 15, bulletin, 15), 1e-9);
		assertEquals(Double.POSITIVE_INFINITY, SyncPolicy.driftSeconds(bulletin, 5.0, 15, slot("a", 5.0, 0), 60));
		assertEquals(Double.POSITIVE_INFINITY, SyncPolicy.driftSeconds(slot("a", 5.0, 0), 5.0, 60, bulletin, 15));
	}

	@Test
	void sameSlotIgnoresTheOffset() {
		assertTrue(SyncPolicy.sameSlot(slot("a", 1, 4), slot("a", 50, 4)));
		assertFalse(SyncPolicy.sameSlot(slot("a", 1, 4), slot("a", 1, 5)));
		assertFalse(SyncPolicy.sameSlot(slot("a", 1, 4), slot("b", 1, 4)));
	}

	// ------------------------------------------------------------------ continuation

	@Test
	void withoutAScheduleTheNextClipFollows() {
		Optional<Slot> next = Optional.of(slot("b", 0, 5));
		assertEquals(next, SyncPolicy.continuation(next, Optional.empty(), 40, 0));
	}

	@Test
	void theScheduleDecidesWhenThereIsNoNextClip() {
		Optional<Slot> scheduled = Optional.of(slot("b", 2, 5));
		assertEquals(scheduled, SyncPolicy.continuation(Optional.empty(), scheduled, 0, 40));
		assertEquals(Optional.empty(), SyncPolicy.continuation(Optional.empty(), Optional.empty(), 0, 0));
	}

	@Test
	void theSameClipIsStartedWhereTheScheduleSaysItIs() {
		Optional<Slot> next = Optional.of(slot("b", 0, 5));
		Optional<Slot> scheduled = Optional.of(slot("b", 0.35, 5));
		assertEquals(scheduled, SyncPolicy.continuation(next, scheduled, 40, 40), "the radio was 0.35 s late");
	}

	@Test
	void aScheduleThatHasMovedOnByLessThanTheLimitStillGetsTheNextClipFromItsStart() {
		// The radio ended early: the schedule is still 1 s from the end of "a" (index 4).
		Optional<Slot> next = Optional.of(slot("b", 0, 5));
		Optional<Slot> scheduled = Optional.of(slot("a", 59, 4));
		assertEquals(next, SyncPolicy.continuation(next, scheduled, 40, 60));
	}

	@Test
	void aScheduleFarFromTheNextClipWins() {
		// Stopped from outside, then the clock moved on 30 s.
		Optional<Slot> next = Optional.of(slot("b", 0, 5));
		Optional<Slot> scheduled = Optional.of(slot("b", 30, 5));
		assertEquals(scheduled, SyncPolicy.continuation(next, scheduled, 40, 40));
		Optional<Slot> farther = Optional.of(slot("d", 3, 7));
		assertEquals(farther, SyncPolicy.continuation(next, farther, 40, 20));
	}

	// ------------------------------------------------------------------ playState

	@Test
	void aSoundTheEngineHasAndHasNotFinishedIsPlaying() {
		assertEquals(PlayState.PLAYING, SyncPolicy.playState(true, true, 100, 1000));
	}

	@Test
	void aSoundTheEngineHadAndNoLongerHasHasEnded() {
		assertEquals(PlayState.ENDED, SyncPolicy.playState(true, false, 100, 1000));
		assertEquals(PlayState.ENDED, SyncPolicy.playState(true, false, 1, 1000), "even a very short one");
	}

	@Test
	void aSoundTheEngineHasNotReportedYetGetsTheGracePeriod() {
		assertEquals(PlayState.PLAYING, SyncPolicy.playState(false, false, 0, 1000));
		assertEquals(PlayState.PLAYING, SyncPolicy.playState(false, false, SyncPolicy.START_GRACE_TICKS - 1, 1000));
		assertEquals(PlayState.NEVER_STARTED, SyncPolicy.playState(false, false, SyncPolicy.START_GRACE_TICKS, 1000));
		assertEquals(PlayState.NEVER_STARTED, SyncPolicy.playState(false, false, 5000, 1000), "never an ended clip");
	}

	@Test
	void anActiveSoundPastItsExpectedEndIsGivenUpOn() {
		assertEquals(PlayState.PLAYING, SyncPolicy.playState(true, true, 999, 1000));
		assertEquals(PlayState.ENDED, SyncPolicy.playState(true, true, 1000, 1000));
	}

	@Test
	void theGracePeriodIsAboutOneAndAHalfSeconds() {
		assertEquals(1.5, SyncPolicy.START_GRACE_TICKS / 20.0);
	}

	@Test
	void theExpectedEndIsTheRemainderPlusTwoSeconds() {
		assertEquals(62 * 20, SyncPolicy.expectedEndTicks(60.0, 0.0));
		assertEquals(22 * 20, SyncPolicy.expectedEndTicks(60.0, 40.0));
		assertEquals(40, SyncPolicy.expectedEndTicks(0.0, 0.0), "a zero-length clip still has the margin");
		assertEquals(40, SyncPolicy.expectedEndTicks(60.0, 75.0), "an offset past the end leaves only the margin");
		assertEquals(Math.ceil((10.3 - 4.05 + 2.0) * 20), SyncPolicy.expectedEndTicks(10.3, 4.05));
	}
}
