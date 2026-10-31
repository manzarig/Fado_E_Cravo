package com.manzarig.fadoecravo.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.Test;

/** The parts of {@link RadioSoundInstance} that do not need a running sound engine. */
class RadioSoundInstanceTest {
	private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("fadoecravo", "test.seek_test");

	private static RadioSoundInstance radio(float volume) {
		return new RadioSoundInstance(ID, new BlockPos(10, 64, -3), 12.5, volume);
	}

	@Test
	void constructorSetsUpAPositionalRecordsSound() {
		RadioSoundInstance radio = radio(0.8f);
		assertEquals(ID, radio.getLocation());
		assertEquals(SoundSource.RECORDS, radio.getSource());
		assertEquals(SoundInstance.Attenuation.LINEAR, radio.getAttenuation());
		assertFalse(radio.isLooping());
		assertFalse(radio.isRelative());
		assertEquals(0, radio.getDelay());
		assertEquals(10.5, radio.getX());
		assertEquals(64.5, radio.getY());
		assertEquals(-2.5, radio.getZ());
		assertEquals(0.8f, radio.getCurrentVolume());
		assertEquals(12.5, radio.getOffsetSeconds());
		assertFalse(radio.isStopped());
	}

	@Test
	void negativeOffsetCountsAsZero() {
		assertEquals(0.0, new RadioSoundInstance(ID, BlockPos.ZERO, -4.0, 1.0f).getOffsetSeconds());
	}

	@Test
	void volumeFadesByAboutATenthPerTick() {
		RadioSoundInstance radio = radio(1.0f);
		radio.setTargetVolume(0.5f);
		radio.tick();
		assertEquals(0.9f, radio.getCurrentVolume(), 1e-6f);
		for (int i = 0; i < 4; i++) {
			radio.tick();
		}
		assertEquals(0.5f, radio.getCurrentVolume(), 1e-6f);
		radio.tick();
		assertEquals(0.5f, radio.getCurrentVolume(), "stays on the target");
		radio.setTargetVolume(1.0f);
		for (int i = 0; i < 5; i++) {
			radio.tick();
		}
		assertEquals(1.0f, radio.getCurrentVolume(), 1e-6f);
		assertFalse(radio.isStopped());
	}

	@Test
	void fadeOutAndStopStopsOnlyOnceSilent() {
		RadioSoundInstance radio = radio(1.0f);
		radio.fadeOutAndStop();
		for (int i = 0; i < 9; i++) {
			radio.tick();
			assertFalse(radio.isStopped(), "still audible after tick " + (i + 1));
		}
		for (int i = 0; i < 3 && !radio.isStopped(); i++) {
			radio.tick();
		}
		assertTrue(radio.isStopped());
		assertEquals(0.0f, radio.getCurrentVolume());
	}

	@Test
	void settingATargetCancelsAPendingStop() {
		RadioSoundInstance radio = radio(1.0f);
		radio.fadeOutAndStop();
		radio.tick();
		radio.setTargetVolume(1.0f);
		for (int i = 0; i < 20; i++) {
			radio.tick();
		}
		assertFalse(radio.isStopped());
		assertEquals(1.0f, radio.getCurrentVolume(), 1e-6f);
	}

	@Test
	void aSilentRadioStillStartsSoItCanFadeIn() {
		RadioSoundInstance radio = radio(0.0f);
		assertTrue(radio.canStartSilent());
		radio.setTargetVolume(1.0f);
		radio.tick();
		assertEquals(0.1f, radio.getCurrentVolume(), 1e-6f);
	}
}
