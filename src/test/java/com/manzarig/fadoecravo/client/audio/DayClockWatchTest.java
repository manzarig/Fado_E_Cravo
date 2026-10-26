package com.manzarig.fadoecravo.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DayClockWatchTest {
	@Test
	void aRunningClockIsNeverFrozen() {
		DayClockWatch watch = new DayClockWatch();
		for (long time = 1000; time < 1500; time++) {
			watch.observe(time);
			assertFalse(watch.isFrozen());
		}
	}

	@Test
	void aClockThatStandsStillForFortyTicksIsFrozen() {
		DayClockWatch watch = new DayClockWatch();
		watch.observe(5000); // the first sight of the value
		for (int i = 1; i < SyncPolicy.FROZEN_AFTER_TICKS; i++) {
			watch.observe(5000);
			assertFalse(watch.isFrozen(), "after " + i + " ticks of standing still");
		}
		watch.observe(5000);
		assertTrue(watch.isFrozen());
		watch.observe(5000);
		assertTrue(watch.isFrozen());
	}

	@Test
	void anyChangeEndsTheFreezeAtOnce() {
		DayClockWatch watch = new DayClockWatch();
		for (int i = 0; i < 100; i++) {
			watch.observe(5000);
		}
		assertTrue(watch.isFrozen());
		watch.observe(5001);
		assertFalse(watch.isFrozen());
	}

	@Test
	void aJumpBackwardsIsAChangeToo() {
		DayClockWatch watch = new DayClockWatch();
		for (int i = 0; i < 100; i++) {
			watch.observe(5000);
		}
		watch.observe(1000);
		assertFalse(watch.isFrozen());
	}

	@Test
	void nothingIsFrozenBeforeTheFirstObservationOrAfterAReset() {
		DayClockWatch watch = new DayClockWatch();
		assertFalse(watch.isFrozen());
		for (int i = 0; i < 100; i++) {
			watch.observe(5000);
		}
		watch.reset();
		assertFalse(watch.isFrozen());
		watch.observe(5000);
		assertFalse(watch.isFrozen());
	}

	@Test
	void theThresholdIsTwoSeconds() {
		assertEquals(2.0, SyncPolicy.FROZEN_AFTER_TICKS / 20.0);
	}
}
