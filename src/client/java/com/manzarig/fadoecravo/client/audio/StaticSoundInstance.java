package com.manzarig.fadoecravo.client.audio;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * A seamless loop that plays from a radio: the static between stations, or the faint carrier hiss of
 * a station that is on the dial but has nothing on the air. It is the looping counterpart of
 * {@link RadioSoundInstance} and has the same volume API, so a radio can mix the two by setting
 * targets and letting the volumes glide.
 *
 * <p>The sound engine loops it by itself ({@code sounds.json} has these entries with
 * {@code "stream": false}, so the whole OGG sits in a buffer) and calls {@link #tick()} on the client
 * main thread. Keep the volume at 1 or below: the range comes from {@code attenuation_distance} in
 * sounds.json, and only a volume above 1 would widen it.
 */
public class StaticSoundInstance extends AbstractTickableSoundInstance {
	private float targetVolume;
	private boolean stopWhenSilent;

	/**
	 * @param sound  the loop to play, normally {@code ModSounds.STATIC_LOOP} or {@code ModSounds.CARRIER_HISS}
	 * @param pos    block the sound comes from; it plays at the centre of the block
	 * @param volume starting volume, which is also the first fade target
	 */
	public StaticSoundInstance(SoundEvent sound, BlockPos pos, float volume) {
		super(sound, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
		this.volume = Math.max(0.0f, volume);
		this.targetVolume = this.volume;
		this.looping = true;
		this.attenuation = SoundInstance.Attenuation.LINEAR;
		this.x = pos.getX() + 0.5;
		this.y = pos.getY() + 0.5;
		this.z = pos.getZ() + 0.5;
	}

	/** Volume the fade has reached so far; the plain value this class manages, as in {@link RadioSoundInstance#getCurrentVolume()}. */
	public float getCurrentVolume() {
		return volume;
	}

	/** Volume the fade is heading for. */
	public float getTargetVolume() {
		return targetVolume;
	}

	/** Fades to {@code volume} (not below 0) by {@link RadioSoundInstance#FADE_STEP} per tick and cancels a pending {@link #fadeOutAndStop()}. */
	public void setTargetVolume(float volume) {
		this.targetVolume = Math.max(0.0f, volume);
		this.stopWhenSilent = false;
	}

	/** Fades to silence, then stops the sound. */
	public void fadeOutAndStop() {
		this.targetVolume = 0.0f;
		this.stopWhenSilent = true;
	}

	@Override
	public void tick() {
		if (volume < targetVolume) {
			volume = Math.min(volume + RadioSoundInstance.FADE_STEP, targetVolume);
		} else if (volume > targetVolume) {
			volume = Math.max(volume - RadioSoundInstance.FADE_STEP, targetVolume);
		}
		if (stopWhenSilent && volume <= 0.0f) {
			stop();
		}
	}

	/**
	 * The sound engine skips an instance whose volume is 0 when it is started unless this is true. A
	 * radio keeps its static running at volume 0 while a station comes in clearly, and fades it in
	 * again when the dial moves away.
	 */
	@Override
	public boolean canStartSilent() {
		return true;
	}
}
