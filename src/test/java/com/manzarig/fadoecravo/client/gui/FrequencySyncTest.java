package com.manzarig.fadoecravo.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class FrequencySyncTest {
	private static final int MIN = 530;
	private static final int MAX = 1600;

	private static FrequencySync sync(int initial) {
		return new FrequencySync(initial, MIN, MAX);
	}

	@Test
	void theContractedTimingsAreUsed() {
		assertEquals(250L, FrequencySync.SEND_DELAY_MS);
		assertEquals(1000L, FrequencySync.HOLD_MS);
	}

	@Test
	void startsOnTheGivenFrequencyWithNothingToSend() {
		FrequencySync sync = sync(1035);
		assertEquals(1035, sync.shown());
		assertFalse(sync.hasUnsentChange());
		assertFalse(sync.isAwaitingEcho());
		assertEquals(OptionalInt.empty(), sync.pollSend(100_000));
		assertEquals(OptionalInt.empty(), sync.flush(100_000));
	}

	@Test
	void initialValueIsClamped() {
		assertEquals(MAX, sync(9999).shown());
		assertEquals(MIN, sync(0).shown());
	}

	@Test
	void aLocalChangeShowsAtOnceAndIsClamped() {
		FrequencySync sync = sync(1035);
		assertTrue(sync.set(1040, 0));
		assertEquals(1040, sync.shown());
		assertTrue(sync.hasUnsentChange());
		assertTrue(sync.nudge(-1, 1));
		assertEquals(1039, sync.shown());
		assertTrue(sync.set(5000, 2));
		assertEquals(MAX, sync.shown());
		assertFalse(sync.set(MAX + 10, 3), "already at the end");
		assertFalse(sync.nudge(1, 4), "cannot go past the end");
		assertTrue(sync.set(-5, 5));
		assertEquals(MIN, sync.shown());
		assertFalse(sync.nudge(-10, 6));
	}

	@Test
	void settingTheShownValueIsNotAChange() {
		FrequencySync sync = sync(1035);
		assertFalse(sync.set(1035, 0));
		assertFalse(sync.hasUnsentChange());
	}

	@Test
	void theChangeIsSentQuarterOfASecondAfterTheLastOne() {
		FrequencySync sync = sync(1035);
		sync.set(1040, 1000);
		assertEquals(OptionalInt.empty(), sync.pollSend(1000));
		assertEquals(OptionalInt.empty(), sync.pollSend(1249));
		assertEquals(OptionalInt.of(1040), sync.pollSend(1250));
		assertFalse(sync.hasUnsentChange());
		assertTrue(sync.isAwaitingEcho());
		assertEquals(OptionalInt.empty(), sync.pollSend(5000), "sent only once");
	}

	@Test
	void everyChangeRestartsTheDelay() {
		FrequencySync sync = sync(1035);
		sync.set(1036, 0);
		sync.set(1037, 200);
		sync.set(1038, 400);
		assertEquals(OptionalInt.empty(), sync.pollSend(500), "the first change is old, the last is not");
		assertEquals(OptionalInt.empty(), sync.pollSend(649));
		assertEquals(OptionalInt.of(1038), sync.pollSend(650));
	}

	@Test
	void flushSendsAtOnceAndOnlyOnce() {
		FrequencySync sync = sync(1035);
		sync.set(1100, 10);
		assertEquals(OptionalInt.of(1100), sync.flush(11));
		assertEquals(OptionalInt.empty(), sync.flush(12));
		assertEquals(OptionalInt.empty(), sync.pollSend(10_000));
	}

	@Test
	void aStaleServerValueDoesNotMoveTheNeedleWhileTheChangeIsUnsent() {
		FrequencySync sync = sync(1035);
		sync.set(1040, 0);
		sync.onServerValue(1035, 100);
		assertEquals(1040, sync.shown());
		sync.onServerValue(1035, 2000);
		assertEquals(1040, sync.shown(), "as long as it is unsent, however old");
		assertTrue(sync.hasUnsentChange());
	}

	@Test
	void aStaleServerValueDoesNotMoveTheNeedleAfterSendingEither() {
		FrequencySync sync = sync(1035);
		sync.set(1040, 0);
		assertEquals(OptionalInt.of(1040), sync.pollSend(250));
		sync.onServerValue(1035, 300);
		sync.onServerValue(1035, 600);
		sync.onServerValue(1035, 1249);
		assertEquals(1040, sync.shown());
		assertTrue(sync.isAwaitingEcho());
	}

	@Test
	void theEchoIsTheAcknowledgement() {
		FrequencySync sync = sync(1035);
		sync.set(1040, 0);
		sync.pollSend(250);
		sync.onServerValue(1040, 300);
		assertFalse(sync.isAwaitingEcho());
		assertEquals(1040, sync.shown());
		// From now on the server is followed again.
		sync.onServerValue(1050, 400);
		assertEquals(1050, sync.shown());
	}

	@Test
	void aServerThatNeverRepeatsTheValueWinsAfterOneSecond() {
		FrequencySync sync = sync(1035);
		sync.set(1040, 0);
		sync.pollSend(250);
		sync.onServerValue(1035, 1249);
		assertEquals(1040, sync.shown());
		sync.onServerValue(1035, 1250);
		assertEquals(1035, sync.shown(), "rejected, or changed by somebody else");
		assertFalse(sync.isAwaitingEcho());
	}

	@Test
	void anotherPlayersChangeShowsWhenNothingIsPending() {
		FrequencySync sync = sync(1035);
		sync.onServerValue(700, 0);
		assertEquals(700, sync.shown());
		assertFalse(sync.hasUnsentChange());
	}

	@Test
	void draggingKeepsTheLocalValueAcrossSeveralSends() {
		FrequencySync sync = sync(1035);
		sync.set(1040, 0);
		assertEquals(OptionalInt.of(1040), sync.pollSend(250));
		sync.set(1060, 300);
		// The echo of the first packet arrives while the player has moved on.
		sync.onServerValue(1040, 350);
		assertEquals(1060, sync.shown());
		assertEquals(OptionalInt.of(1060), sync.pollSend(550));
		sync.onServerValue(1040, 600);
		assertEquals(1060, sync.shown(), "1040 is still the old echo");
		sync.onServerValue(1060, 700);
		assertEquals(1060, sync.shown());
		assertFalse(sync.isAwaitingEcho());
	}

	@Test
	void comingBackToTheServersValueBeforeSendingSendsNothing() {
		FrequencySync sync = sync(1035);
		sync.set(1040, 0);
		sync.set(1035, 100);
		assertEquals(OptionalInt.empty(), sync.pollSend(350));
		assertFalse(sync.hasUnsentChange());
		assertFalse(sync.isAwaitingEcho());
	}

	@Test
	void comingBackToTheOldValueAfterSendingMustStillBeSent() {
		FrequencySync sync = sync(1035);
		sync.set(1040, 0);
		assertEquals(OptionalInt.of(1040), sync.pollSend(250));
		// The server may have applied 1040 by now, although the screen has not heard it yet.
		sync.set(1035, 300);
		assertEquals(OptionalInt.of(1035), sync.pollSend(550));
		sync.onServerValue(1040, 600);
		assertEquals(1035, sync.shown());
		sync.onServerValue(1035, 700);
		assertEquals(1035, sync.shown());
		assertFalse(sync.isAwaitingEcho());
	}

	@Test
	void discardingGoesBackToTheServersValue() {
		FrequencySync sync = sync(1035);
		sync.set(1040, 0);
		sync.discardPending();
		assertEquals(1035, sync.shown());
		assertEquals(OptionalInt.empty(), sync.flush(10));
	}

	@Test
	void serverValuesAreClamped() {
		FrequencySync sync = sync(1035);
		sync.onServerValue(99_999, 0);
		assertEquals(MAX, sync.shown());
	}

	@Test
	void backwardsBandIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> new FrequencySync(1000, 1600, 530));
	}
}
