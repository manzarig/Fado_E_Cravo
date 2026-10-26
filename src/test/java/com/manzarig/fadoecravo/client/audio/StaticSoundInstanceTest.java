package com.manzarig.fadoecravo.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.Test;

/** The parts of {@link StaticSoundInstance} that do not need a running sound engine. */
class StaticSoundInstanceTest {
	private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("fadoecravo", "sfx.static_loop");

	private static StaticSoundInstance staticLoop(float volume) {
		return new StaticSoundInstance(SoundEvent.createVariableRangeEvent(ID), new BlockPos(10, 64, -3), volume);
	}

	@Test
	void constructorSetsUpAPositionalLoopInTheRecordsCategory() {
		StaticSoundInstance sound = staticLoop(0.6f);
		assertEquals(ID, sound.getLocation());
		assertEquals(SoundSource.RECORDS, sound.getSource());
		assertEquals(SoundInstance.Attenuation.LINEAR, sound.getAttenuation());
		assertTrue(sound.isLooping());
		assertFalse(sound.isRelative());
		assertEquals(0, sound.getDelay());
		assertEquals(10.5, sound.getX());
		assertEquals(64.5, sound.getY());
		assertEquals(-2.5, sound.getZ());
		assertEquals(0.6f, sound.getCurrentVolume());
		assertEquals(0.6f, sound.getTargetVolume());
		assertFalse(sound.isStopped());
	}

	@Test
	void aNegativeVolumeCountsAsSilence() {
		assertEquals(0.0f, staticLoop(-1.0f).getCurrentVolume());
		StaticSoundInstance sound = staticLoop(0.5f);
		sound.setTargetVolume(-2.0f);
		assertEquals(0.0f, sound.getTargetVolume());
	}

	@Test
	void volumeFadesByAboutATenthPerTick() {
		StaticSoundInstance sound = staticLoop(0.0f);
		sound.setTargetVolume(0.25f);
		sound.tick();
		assertEquals(0.1f, sound.getCurrentVolume(), 1e-6f);
		sound.tick();
		sound.tick();
		assertEquals(0.25f, sound.getCurrentVolume(), 1e-6f);
		sound.tick();
		assertEquals(0.25f, sound.getCurrentVolume(), "stays on the target");
		sound.setTargetVolume(0.0f);
		for (int i = 0; i < 3; i++) {
			sound.tick();
		}
		assertEquals(0.0f, sound.getCurrentVolume(), 1e-6f);
		assertFalse(sound.isStopped(), "a silent loop keeps running until it is told to stop");
	}

	@Test
	void fadeOutAndStopStopsOnlyOnceSilent() {
		StaticSoundInstance sound = staticLoop(1.0f);
		sound.fadeOutAndStop();
		for (int i = 0; i < 9; i++) {
			sound.tick();
			assertFalse(sound.isStopped(), "still audible after tick " + (i + 1));
		}
		for (int i = 0; i < 3 && !sound.isStopped(); i++) {
			sound.tick();
		}
		assertTrue(sound.isStopped());
		assertEquals(0.0f, sound.getCurrentVolume());
	}

	@Test
	void settingATargetCancelsAPendingStop() {
		StaticSoundInstance sound = staticLoop(1.0f);
		sound.fadeOutAndStop();
		sound.tick();
		sound.setTargetVolume(1.0f);
		for (int i = 0; i < 20; i++) {
			sound.tick();
		}
		assertFalse(sound.isStopped());
		assertEquals(1.0f, sound.getCurrentVolume(), 1e-6f);
	}

	@Test
	void aSilentLoopStillStartsSoItCanFadeIn() {
		StaticSoundInstance sound = staticLoop(0.0f);
		assertTrue(sound.canStartSilent());
		sound.setTargetVolume(0.6f);
		sound.tick();
		assertEquals(0.1f, sound.getCurrentVolume(), 1e-6f);
	}

	@Test
	void usesTheSameFadeStepAsTheProgramme() {
		StaticSoundInstance sound = staticLoop(0.0f);
		sound.setTargetVolume(1.0f);
		sound.tick();
		assertEquals(RadioSoundInstance.FADE_STEP, sound.getCurrentVolume(), 1e-6f);
	}
}
