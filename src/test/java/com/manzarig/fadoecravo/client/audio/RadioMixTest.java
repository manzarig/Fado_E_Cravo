package com.manzarig.fadoecravo.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RadioMixTest {
	@Test
	void aClearSignalIsAllProgrammeAndNoStatic() {
		RadioMix mix = RadioMix.onAir(1.0, 1.0);
		assertEquals(1.0f, mix.programme());
		assertEquals(0.0f, mix.staticLoop());
		assertEquals(0.0f, mix.carrier());
	}

	@Test
	void theStaticFillsWhatTheSignalLeavesOpen() {
		RadioMix half = RadioMix.onAir(0.5, 1.0);
		assertEquals(0.5f, half.programme());
		assertEquals(0.3f, half.staticLoop(), 1e-6f);
		RadioMix faint = RadioMix.onAir(0.1, 1.0);
		assertEquals(0.1f, faint.programme(), 1e-6f);
		assertEquals(0.54f, faint.staticLoop(), 1e-6f);
	}

	@Test
	void aSignalOutsideZeroToOneIsClamped() {
		assertEquals(1.0f, RadioMix.onAir(1.7, 1.0).programme());
		assertEquals(0.0f, RadioMix.onAir(1.7, 1.0).staticLoop());
		assertEquals(0.0f, RadioMix.onAir(-0.2, 1.0).programme());
		assertEquals(0.6f, RadioMix.onAir(-0.2, 1.0).staticLoop());
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
		for (double strength : new double[] {0.0, 0.3, 0.55, 0.75, 1.0}) {
			for (int i = 0; i <= 100; i++) {
				RadioMix mix = RadioMix.onAir(i / 100.0 * strength, strength);
				assertTrue(mix.programme() <= 1.0f && mix.staticLoop() <= 1.0f && mix.carrier() <= 1.0f,
					"strength " + strength + ", signal step " + i);
			}
		}
	}

	// ------------------------------------------------------------------ weak stations

	@Test
	void aStrongStationGetsNoFloor() {
		for (int i = 0; i <= 100; i++) {
			double signal = i / 100.0;
			assertEquals((1.0f - (float) signal) * 0.6f, RadioMix.onAir(signal, 1.0).staticLoop(), 1e-6f, "signal " + signal);
		}
	}

	@Test
	void aWeakStationHasStaticUnderItEvenWhenTunedInExactly() {
		// Atlantico at 0.55, dial on the station: the fade static is (1 - 0.55) * 0.6 = 0.27, the floor 0.45 * 0.35.
		RadioMix mix = RadioMix.onAir(0.55, 0.55);
		assertEquals(0.55f, mix.programme(), 1e-6f);
		assertEquals(0.27f + 0.1575f, mix.staticLoop(), 1e-6f);
		assertTrue(mix.staticLoop() > 0.40f, "about 0.43, up from 0.27");
		assertTrue(mix.staticLoop() > RadioMix.onAir(0.55, 1.0).staticLoop() + 0.15f);
	}

	@Test
	void theFloorOfAWeakStationShowsInTheStaticAtEveryPointOfTheFade() {
		// A max() of the fade static and the floor, as first specified, can never bind: s <= strength
		// means (1 - s) * 0.6 >= (1 - strength) * 0.6 > (1 - strength) * 0.35. Check that the floor is added.
		double strength = 0.55;
		for (int i = 0; i <= 20; i++) {
			double signal = strength * i / 20.0;
			float fade = (1.0f - (float) signal) * RadioMix.STATIC_MAX;
			float floor = (1.0f - (float) strength) * RadioMix.WEAK_STATIC_FLOOR;
			assertTrue(fade > floor, "the floor alone would be hidden by the fade, signal " + signal);
			float actual = RadioMix.onAir(signal, strength).staticLoop();
			assertEquals(Math.min(RadioMix.STATIC_MAX, fade + floor), actual, 1e-6f, "signal " + signal);
			assertTrue(actual > fade || fade + floor >= RadioMix.STATIC_MAX, "signal " + signal);
		}
	}

	@Test
	void theStaticOfAWeakStationNeverExceedsTheStaticOfAnEmptyDial() {
		for (double strength : new double[] {0.0, 0.2, 0.55, 0.74}) {
			for (int i = 0; i <= 100; i++) {
				assertTrue(RadioMix.onAir(i / 100.0 * strength, strength).staticLoop() <= RadioMix.STATIC_MAX,
					"strength " + strength + ", step " + i);
			}
		}
		assertEquals(0.6f, RadioMix.onAir(0.0, 0.55).staticLoop(), 1e-6f, "no jump where the station goes out of reach");
	}

	@Test
	void theStaticOfAWeakStationRisesSmoothlyAsTheSignalFalls() {
		float previous = RadioMix.onAir(0.55, 0.55).staticLoop();
		for (int i = 1; i <= 55; i++) {
			float now = RadioMix.onAir(0.55 - i / 100.0, 0.55).staticLoop();
			assertTrue(now >= previous - 1e-6f, "step " + i);
			assertTrue(now - previous < 0.02f, "no jump at step " + i);
			previous = now;
		}
	}

	@Test
	void aStationOfNoStrengthAtAllIsStaticOnly() {
		RadioMix mix = RadioMix.onAir(0.0, 0.0);
		assertEquals(0.0f, mix.programme());
		assertEquals(0.6f, mix.staticLoop(), 1e-6f);
	}

	// ------------------------------------------------------------------ crowds

	@Test
	void aRadioOnItsOwnIsNotTurnedDown() {
		assertEquals(1.0f, RadioMix.crowdGain(1));
		assertEquals(1.0f, RadioMix.crowdGain(0), "nothing counted is still just this radio");
		assertEquals(1.0f, RadioMix.crowdGain(-3));
	}

	@Test
	void eachRadioOfACrowdIsTurnedDownByNToTheMinusExponent() {
		assertEquals((float) Math.pow(2, -0.75), RadioMix.crowdGain(2), 1e-6f);
		assertEquals((float) Math.pow(3, -0.75), RadioMix.crowdGain(3), 1e-6f);
		assertEquals(0.3535534f, RadioMix.crowdGain(4), 1e-6f);
	}

	@Test
	void aCrowdOfNIsNeverLouderThanOneRadioInPowerAndHasAmplitudeHeadroom() {
		for (int n = 1; n <= 4; n++) {
			float gain = RadioMix.crowdGain(n);
			assertTrue(n * gain * gain <= 1.0f + 1e-5f, "power, n " + n);
			assertTrue(n * gain <= 1.5f, "coherent amplitude at most +3.5 dB, n " + n);
		}
	}

	@Test
	void theGainOnlyFallsAsTheCrowdGrows() {
		float previous = 1.0f;
		for (int n = 1; n <= 16; n++) {
			float gain = RadioMix.crowdGain(n);
			assertTrue(gain <= previous, "n " + n);
			previous = gain;
		}
	}

	@Test
	void aCrowdScalesTheProgrammeTheStaticAndTheHissTogether() {
		RadioMix mix = new RadioMix(0.8f, 0.4f, 0.5f).inCrowd(4);
		float gain = RadioMix.crowdGain(4);
		assertEquals(0.8f * gain, mix.programme(), 1e-6f);
		assertEquals(0.4f * gain, mix.staticLoop(), 1e-6f);
		assertEquals(0.5f * gain, mix.carrier(), 1e-6f);
	}

	@Test
	void aCrowdOfOneChangesNothing() {
		RadioMix base = RadioMix.onAir(0.55, 0.55);
		assertEquals(base, base.inCrowd(1));
		assertEquals(RadioMix.offAir(), RadioMix.offAir().inCrowd(1));
	}

	@Test
	void aCrowdNeverRaisesAVolumeAndSoNoneExceedsOne() {
		for (int n = 1; n <= 8; n++) {
			for (double strength : new double[] {0.4, 1.0}) {
				for (int i = 0; i <= 20; i++) {
					RadioMix base = RadioMix.onAir(i / 20.0 * strength, strength);
					RadioMix crowded = base.inCrowd(n);
					assertTrue(crowded.programme() <= base.programme() && crowded.staticLoop() <= base.staticLoop()
						&& crowded.carrier() <= base.carrier());
					assertTrue(crowded.programme() <= 1.0f && crowded.staticLoop() <= 1.0f && crowded.carrier() <= 1.0f);
				}
			}
		}
		RadioMix off = RadioMix.offAir().inCrowd(4);
		assertEquals(0.6f * RadioMix.crowdGain(4), off.staticLoop(), 1e-6f);
		assertEquals(0.5f * RadioMix.crowdGain(4), off.carrier(), 1e-6f);
	}

	// ------------------------------------------------------------------ when a loop plays

	@Test
	void aSilentLoopIsNotStarted() {
		assertFalse(RadioMix.loopWanted(0.0f, false));
		assertFalse(RadioMix.loopWanted(0.01f, false));
		assertFalse(RadioMix.loopWanted(RadioMix.LOOP_START_VOLUME, false), "it has to rise above the threshold");
		assertTrue(RadioMix.loopWanted(0.03f, false));
		assertTrue(RadioMix.loopWanted(0.6f, false));
	}

	@Test
	void aRunningLoopIsKeptDownToTheStopThreshold() {
		assertTrue(RadioMix.loopWanted(0.015f, true), "inside the gap a running loop stays");
		assertFalse(RadioMix.loopWanted(0.015f, false), "and a stopped one stays stopped");
		assertTrue(RadioMix.loopWanted(0.02f, true));
		assertFalse(RadioMix.loopWanted(RadioMix.LOOP_STOP_VOLUME, true));
		assertFalse(RadioMix.loopWanted(0.0f, true));
	}

	@Test
	void theThresholdsLeaveAGapAndAreSmall() {
		assertTrue(RadioMix.LOOP_STOP_VOLUME < RadioMix.LOOP_START_VOLUME);
		assertTrue(RadioMix.LOOP_START_VOLUME <= 0.05f, "a loop is not held back until it is clearly audible");
	}
}
