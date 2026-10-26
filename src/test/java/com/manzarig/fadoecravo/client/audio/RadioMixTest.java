package com.manzarig.fadoecravo.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RadioMixTest {
	@Test
	void aClearSignalIsAllProgrammeAndNoStatic() {
		RadioMix mix = RadioMix.onAir(1.0);
		assertEquals(1.0f, mix.programme());
		assertEquals(0.0f, mix.staticLoop());
		assertEquals(0.0f, mix.carrier());
	}

	@Test
	void theStaticFillsWhatTheSignalLeavesOpen() {
		RadioMix half = RadioMix.onAir(0.5);
		assertEquals(0.5f, half.programme());
		assertEquals(0.3f, half.staticLoop(), 1e-6f);
		RadioMix faint = RadioMix.onAir(0.1);
		assertEquals(0.1f, faint.programme(), 1e-6f);
		assertEquals(0.54f, faint.staticLoop(), 1e-6f);
	}

	@Test
	void aSignalOutsideZeroToOneIsClamped() {
		assertEquals(1.0f, RadioMix.onAir(1.7).programme());
		assertEquals(0.0f, RadioMix.onAir(1.7).staticLoop());
		assertEquals(0.0f, RadioMix.onAir(-0.2).programme());
		assertEquals(0.6f, RadioMix.onAir(-0.2).staticLoop());
	}

	@Test
	void nothingOnTheAirIsStaticAndTheCarrierHiss() {
		RadioMix mix = RadioMix.offAir();
		assertEquals(0.0f, mix.programme());
		assertEquals(0.6f, mix.staticLoop());
		assertEquals(0.5f, mix.carrier());
	}

	@Test
	void noVolumeEverExceedsOne() {
		for (int i = 0; i <= 100; i++) {
			RadioMix mix = RadioMix.onAir(i / 100.0);
			assertTrue(mix.programme() <= 1.0f && mix.staticLoop() <= 1.0f && mix.carrier() <= 1.0f);
		}
	}
}
