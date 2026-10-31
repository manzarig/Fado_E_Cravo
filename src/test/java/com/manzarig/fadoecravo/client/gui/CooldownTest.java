package com.manzarig.fadoecravo.client.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CooldownTest {
	@Test
	void theFirstRequestIsAlwaysGranted() {
		assertTrue(new Cooldown(300).tryAcquire(0));
		assertTrue(new Cooldown(300).tryAcquire(Long.MIN_VALUE / 2));
	}

	@Test
	void atMostOncePerPeriod() {
		Cooldown cooldown = new Cooldown(300);
		assertTrue(cooldown.tryAcquire(1000));
		assertFalse(cooldown.tryAcquire(1000));
		assertFalse(cooldown.tryAcquire(1299));
		assertTrue(cooldown.tryAcquire(1300));
		assertFalse(cooldown.tryAcquire(1599));
		assertTrue(cooldown.tryAcquire(1600));
	}

	@Test
	void aRefusalDoesNotExtendThePeriod() {
		Cooldown cooldown = new Cooldown(300);
		assertTrue(cooldown.tryAcquire(0));
		assertFalse(cooldown.tryAcquire(100));
		assertFalse(cooldown.tryAcquire(200));
		assertTrue(cooldown.tryAcquire(300), "counted from the grant, not from the refusals");
	}

	@Test
	void aClockThatGoesBackwardsDoesNotLockItUp() {
		Cooldown cooldown = new Cooldown(300);
		assertTrue(cooldown.tryAcquire(10_000));
		assertTrue(cooldown.tryAcquire(5_000));
		assertFalse(cooldown.tryAcquire(5_100));
	}

	@Test
	void aNegativePeriodIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> new Cooldown(-1));
	}
}
