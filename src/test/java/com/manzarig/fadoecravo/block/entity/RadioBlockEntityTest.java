package com.manzarig.fadoecravo.block.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The frequency rules of the radio, which are plain numbers and need no block entity. */
class RadioBlockEntityTest {
	@Test
	void theBandIsTheMediumWaveBandOfTheContract() {
		assertEquals(530, RadioBlockEntity.MIN_KHZ);
		assertEquals(1600, RadioBlockEntity.MAX_KHZ);
		assertEquals(1035, RadioBlockEntity.DEFAULT_KHZ);
	}

	@Test
	void frequenciesInsideTheBandAreKept() {
		assertEquals(530, RadioBlockEntity.clampKhz(530));
		assertEquals(1035, RadioBlockEntity.clampKhz(1035));
		assertEquals(1600, RadioBlockEntity.clampKhz(1600));
	}

	@Test
	void frequenciesOutsideTheBandAreClampedToItsEdges() {
		assertEquals(530, RadioBlockEntity.clampKhz(529));
		assertEquals(530, RadioBlockEntity.clampKhz(0));
		assertEquals(530, RadioBlockEntity.clampKhz(Integer.MIN_VALUE));
		assertEquals(1600, RadioBlockEntity.clampKhz(1601));
		assertEquals(1600, RadioBlockEntity.clampKhz(Integer.MAX_VALUE));
	}

	@Test
	void validityMatchesTheEdgesOfTheBand() {
		assertFalse(RadioBlockEntity.isValidKhz(529));
		assertTrue(RadioBlockEntity.isValidKhz(530));
		assertTrue(RadioBlockEntity.isValidKhz(1600));
		assertFalse(RadioBlockEntity.isValidKhz(1601));
		assertFalse(RadioBlockEntity.isValidKhz(Integer.MIN_VALUE));
		assertFalse(RadioBlockEntity.isValidKhz(Integer.MAX_VALUE));
	}

	@Test
	void everyClampedValueIsValid() {
		for (int khz = -2_000; khz <= 4_000; khz += 7) {
			assertTrue(RadioBlockEntity.isValidKhz(RadioBlockEntity.clampKhz(khz)), "khz=" + khz);
		}
	}
}
