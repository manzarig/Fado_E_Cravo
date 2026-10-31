package com.manzarig.fadoecravo.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DialGeometryTest {
	private static final DialGeometry DIAL = new DialGeometry(RadioLayout.DIAL_LEFT, RadioLayout.DIAL_WIDTH);

	@Test
	void theBandIsTheRadioBand() {
		assertEquals(530, DialGeometry.MIN_KHZ);
		assertEquals(1600, DialGeometry.MAX_KHZ);
	}

	@Test
	void endsOfTheBandLandOnTheFirstAndLastColumn() {
		assertEquals(17, DIAL.tickX(530));
		assertEquals(17.5, DIAL.centerX(530), 1e-9);
		assertEquals(17 + 214, DIAL.tickX(1600));
		assertEquals(17 + 214 + 0.5, DIAL.centerX(1600), 1e-9);
	}

	@Test
	void everyKilohertzSurvivesTheRoundTripThroughX() {
		for (int khz = 530; khz <= 1600; khz++) {
			assertEquals(khz, DIAL.khzForX(DIAL.centerX(khz)), "khz " + khz);
		}
	}

	@Test
	void aMouseWithinAHalfKilohertzOfATickPicksThatKilohertz() {
		for (int khz = 531; khz < 1600; khz++) {
			double x = DIAL.centerX(khz);
			double halfKhzInPixels = 0.5 * RadioLayout.DIAL_WIDTH / 1070.0;
			assertEquals(khz, DIAL.khzForX(x + halfKhzInPixels * 0.9), "above " + khz);
			assertEquals(khz, DIAL.khzForX(x - halfKhzInPixels * 0.9), "below " + khz);
		}
	}

	@Test
	void anXBeyondEitherEndGivesTheEndFrequency() {
		assertEquals(530, DIAL.khzForX(-1000));
		assertEquals(530, DIAL.khzForX(0));
		assertEquals(530, DIAL.khzForX(17));
		assertEquals(1600, DIAL.khzForX(231.5));
		assertEquals(1600, DIAL.khzForX(500));
		assertEquals(1600, DIAL.khzForX(Double.POSITIVE_INFINITY));
		assertEquals(530, DIAL.khzForX(Double.NEGATIVE_INFINITY));
		assertEquals(530, DIAL.khzForX(Double.NaN));
	}

	@Test
	void khzForXNeverDecreasesAsXGrows() {
		int previous = DIAL.khzForX(-5);
		for (double x = -5; x < 260; x += 0.05) {
			int khz = DIAL.khzForX(x);
			assertTrue(khz >= previous, "at x=" + x);
			previous = khz;
		}
	}

	@Test
	void tickXIsClampedToTheBand() {
		assertEquals(DIAL.tickX(530), DIAL.tickX(100));
		assertEquals(DIAL.tickX(1600), DIAL.tickX(9999));
	}

	@Test
	void smallTicksAreExactlyTwoPixelsApart() {
		for (int khz = DIAL.firstTickKhz(); khz < DIAL.lastTickKhz(); khz += DialGeometry.TICK_STEP_KHZ) {
			assertEquals(2, DIAL.tickX(khz + DialGeometry.TICK_STEP_KHZ) - DIAL.tickX(khz), "after " + khz);
		}
	}

	@Test
	void tallTicksAreTwentyPixelsApart() {
		for (int khz = 600; khz < 1600; khz += 100) {
			assertEquals(20, DIAL.tickX(khz + 100) - DIAL.tickX(khz), "after " + khz);
		}
	}

	@Test
	void ticksRunFrom530To1600() {
		assertEquals(530, DIAL.firstTickKhz());
		assertEquals(1600, DIAL.lastTickKhz());
		assertEquals(108, (DIAL.lastTickKhz() - DIAL.firstTickKhz()) / DialGeometry.TICK_STEP_KHZ + 1);
	}

	@Test
	void tickRangeRoundsInwardsWhenTheBandDoesNotStartOnATick() {
		DialGeometry odd = new DialGeometry(0, 100, 533, 1597);
		assertEquals(540, odd.firstTickKhz());
		assertEquals(1590, odd.lastTickKhz());
	}

	@Test
	void everyHundredIsTallAndNumbersAreOnTheEvenHundredsFrom600() {
		assertTrue(DialGeometry.isMajor(600));
		assertTrue(DialGeometry.isMajor(700));
		assertTrue(DialGeometry.isMajor(1600));
		assertFalse(DialGeometry.isMajor(530));
		assertFalse(DialGeometry.isMajor(610));
		assertFalse(DialGeometry.isMajor(1590));
		for (int khz : new int[] {600, 800, 1000, 1200, 1400, 1600}) {
			assertTrue(DialGeometry.isLabelled(khz), "labelled " + khz);
		}
		for (int khz : new int[] {530, 540, 590, 610, 700, 900, 1100, 1500, 1590, 400, 200}) {
			assertFalse(DialGeometry.isLabelled(khz), "unlabelled " + khz);
		}
	}

	@Test
	void theDialSitsInsideTheStripWithRoomForTheLabels() {
		RadioLayout.Rect strip = RadioLayout.STRIP;
		// 11 columns free to the left of the first tick and 10 to the right of the last.
		assertEquals(strip.x() + 11, DIAL.tickX(530));
		assertEquals(strip.right() - 11, DIAL.tickX(1600));
	}

	@Test
	void badDimensionsAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> new DialGeometry(0, 0));
		assertThrows(IllegalArgumentException.class, () -> new DialGeometry(0, 100, 600, 600));
	}

	// ---- crossing detection

	@Test
	void comingIntoTheWindowFromEitherSideCounts() {
		// A station at 1035 with a clear window of 4: 1031..1039.
		assertTrue(DialGeometry.entersWindow(1030, 1031, 1035, 4));
		assertTrue(DialGeometry.entersWindow(1040, 1039, 1035, 4));
		assertTrue(DialGeometry.entersWindow(900, 1035, 1035, 4));
		assertTrue(DialGeometry.entersWindow(1200, 1036, 1035, 4));
	}

	@Test
	void movingWithinOrOutOfTheWindowDoesNot() {
		assertFalse(DialGeometry.entersWindow(1031, 1039, 1035, 4));
		assertFalse(DialGeometry.entersWindow(1035, 1036, 1035, 4));
		assertFalse(DialGeometry.entersWindow(1031, 1030, 1035, 4));
		assertFalse(DialGeometry.entersWindow(1039, 1100, 1035, 4));
		assertFalse(DialGeometry.entersWindow(1035, 1035, 1035, 4));
	}

	@Test
	void stayingOutsideWithoutPassingTheWindowDoesNot() {
		assertFalse(DialGeometry.entersWindow(1000, 1030, 1035, 4));
		assertFalse(DialGeometry.entersWindow(1030, 1000, 1035, 4));
		assertFalse(DialGeometry.entersWindow(1040, 1100, 1035, 4));
		assertFalse(DialGeometry.entersWindow(1030, 1030, 1035, 4));
	}

	@Test
	void jumpingCleanOverTheWindowCounts() {
		// A Shift-scroll step of 10 can skip a window 9 kHz wide.
		assertTrue(DialGeometry.entersWindow(1030, 1040, 1035, 4));
		assertTrue(DialGeometry.entersWindow(1040, 1030, 1035, 4));
		assertTrue(DialGeometry.entersWindow(700, 1500, 1035, 4));
	}

	@Test
	void everySingleStepFromOutsideToInsideIsDetectedExactlyOnce() {
		// Walk the whole dial one kHz at a time and count the entries into the window around 1035.
		int entries = 0;
		for (int khz = 530; khz < 1600; khz++) {
			if (DialGeometry.entersWindow(khz, khz + 1, 1035, 4)) {
				entries++;
			}
		}
		assertEquals(1, entries);
		entries = 0;
		for (int khz = 1600; khz > 530; khz--) {
			if (DialGeometry.entersWindow(khz, khz - 1, 1035, 4)) {
				entries++;
			}
		}
		assertEquals(1, entries);
	}

	@Test
	void anyWindowChecksEveryStation() {
		int[] stations = {670, 1035, 1440};
		assertTrue(DialGeometry.entersAnyWindow(1000, 1032, stations, 4));
		assertTrue(DialGeometry.entersAnyWindow(1000, 1450, stations, 4));
		assertTrue(DialGeometry.entersAnyWindow(600, 671, stations, 4));
		assertFalse(DialGeometry.entersAnyWindow(700, 900, stations, 4));
		assertFalse(DialGeometry.entersAnyWindow(1033, 1037, stations, 4));
		assertFalse(DialGeometry.entersAnyWindow(1000, 1100, new int[0], 4));
	}
}
