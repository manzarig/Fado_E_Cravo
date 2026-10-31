package com.manzarig.fadoecravo.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;
import org.junit.jupiter.api.Test;

class WarmGlowTest {
	@Test
	void aWarmRadioGlowsFully() {
		assertEquals(1.0f, WarmGlow.brightness(0, 123.4));
		assertEquals(1.0f, WarmGlow.brightness(-5, 0.0));
	}

	@Test
	void theGlowRisesOverTheWarmUpAndStaysInRange() {
		float early = 0.0f;
		float late = 0.0f;
		for (int left = RadioBlockEntity.WARMUP_TICKS; left > 0; left--) {
			for (double time = 0; time < 40; time += 0.37) {
				float b = WarmGlow.brightness(left, time);
				assertTrue(b >= 0.0f && b <= 1.0f, b + " at " + left);
			}
			if (left == RadioBlockEntity.WARMUP_TICKS - 5) {
				early = WarmGlow.brightness(left, 0.0);
			}
			if (left == 5) {
				late = WarmGlow.brightness(left, 0.0);
			}
		}
		assertTrue(early < 0.1f, "dim right after the switch: " + early);
		assertTrue(late > 0.7f, "nearly warm at the end: " + late);
	}
}
