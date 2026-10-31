package com.manzarig.fadoecravo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FadoECravoTest {
	@Test
	void modIdMatchesContract() {
		assertEquals("fadoecravo", FadoECravo.MOD_ID);
	}
}
