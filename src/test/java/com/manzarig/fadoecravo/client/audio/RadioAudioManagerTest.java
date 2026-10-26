package com.manzarig.fadoecravo.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The manager needs a running client for everything except its constants and the empty snapshot; the
 * logic it drives is tested in {@link RadioChannelTest}, {@link RadioSelectionTest} and
 * {@link SyncPolicyTest}. This only checks that the class loads and links.
 */
class RadioAudioManagerTest {
	@Test
	void theSnapshotIsEmptyBeforeAnyRadioIsPlayed() {
		assertTrue(RadioAudioManager.snapshot().isEmpty());
	}

	@Test
	void theContractLimitsAreTheOnesInClaudeMd() {
		assertEquals(4, RadioSelection.MAX_RADIOS);
		assertEquals(72.0, RadioSelection.RANGE_BLOCKS);
		assertEquals(5.0, SyncPolicy.DRIFT_LIMIT_SECONDS);
		assertEquals(0.5f, RadioMix.CARRIER_LEVEL);
		assertEquals(0.6f, RadioMix.STATIC_MAX);
		assertEquals(10, Math.round(1.0f / RadioSoundInstance.FADE_STEP), "a fade out from full volume takes 10 ticks, half a second");
	}
}
