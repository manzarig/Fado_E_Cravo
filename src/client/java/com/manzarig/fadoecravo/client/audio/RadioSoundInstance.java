package com.manzarig.fadoecravo.client.audio;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.Util;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;

/**
 * A radio station clip that starts playing partway through, as if the radio had been tuned in
 * while the programme was already on air.
 *
 * <p>Vanilla always plays a sound from its first sample and its ticking sound instances have no
 * way to change that. Fabric API adds {@link #getAudioStream} to every sound instance and the sound
 * engine calls it instead of loading the OGG directly, so this class overrides it to wrap the
 * decoded stream in a {@link SkippingAudioStream}. The skipped part is decoded on a background
 * thread so the sound thread is not blocked.
 *
 * <p>It also owns the radio's volume: {@link #setTargetVolume(float)} moves the volume towards a
 * target by {@value #FADE_STEP} per tick, and {@link #fadeOutAndStop()} fades to silence and then
 * stops the sound. Everything except {@link #getAudioStream} runs on the client main thread.
 */
public class RadioSoundInstance extends AbstractTickableSoundInstance {
	private static final Logger LOGGER = LogUtils.getLogger();
	/** Volume change per client tick while fading. */
	public static final float FADE_STEP = 0.1f;
	/** Chunk size for the discarding reads; large enough to keep the number of buffers allocated low. */
	private static final int SKIP_CHUNK_BYTES = 64 * 1024;

	private final double offsetSeconds;
	private float targetVolume;
	private boolean stopWhenSilent;

	/**
	 * @param soundId       id of an entry in sounds.json, e.g. {@code fadoecravo:radio.radio_tejo.ident_1}
	 * @param pos           block the sound comes from; it plays at the centre of the block
	 * @param offsetSeconds how far into the clip to start; negative counts as 0
	 * @param volume        starting volume, which is also the first fade target
	 */
	public RadioSoundInstance(ResourceLocation soundId, BlockPos pos, double offsetSeconds, float volume) {
		// AbstractTickableSoundInstance has no ResourceLocation constructor, only SoundEvent. A
		// variable-range event is never registered; the sound engine only reads its location.
		super(SoundEvent.createVariableRangeEvent(soundId), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
		this.offsetSeconds = Math.max(0.0, offsetSeconds);
		this.volume = Math.max(0.0f, volume);
		this.targetVolume = this.volume;
		this.looping = false;
		this.attenuation = SoundInstance.Attenuation.LINEAR;
		this.x = pos.getX() + 0.5;
		this.y = pos.getY() + 0.5;
		this.z = pos.getZ() + 0.5;
	}

	/** Seconds into the clip where playback starts. */
	public double getOffsetSeconds() {
		return offsetSeconds;
	}

	/**
	 * Volume the fade has reached so far. Unlike {@link #getVolume()}, which also multiplies in the
	 * volume from sounds.json and so only works once the sound engine has resolved the instance,
	 * this is the plain value this class manages.
	 */
	public float getCurrentVolume() {
		return volume;
	}

	/** Volume the fade is heading for. */
	public float getTargetVolume() {
		return targetVolume;
	}

	/** Fades to {@code volume} (not below 0) by {@value #FADE_STEP} per tick and cancels a pending {@link #fadeOutAndStop()}. */
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
			volume = Math.min(volume + FADE_STEP, targetVolume);
		} else if (volume > targetVolume) {
			volume = Math.max(volume - FADE_STEP, targetVolume);
		}
		if (stopWhenSilent && volume <= 0.0f) {
			stop();
		}
	}

	/**
	 * The sound engine skips an instance whose volume is 0 when it is started unless this is true.
	 * A radio may be started silent and faded in, and must still get its tick calls.
	 */
	@Override
	public boolean canStartSilent() {
		return true;
	}

	/**
	 * Loads the OGG through vanilla, then skips {@link #getOffsetSeconds()} into it on a background
	 * thread. Called by the sound engine through the hook Fabric API injects into
	 * {@code SoundEngine#play}; {@code id} is the OGG path, equal to {@code getSound().getPath()}.
	 */
	@Override
	public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, ResourceLocation id, boolean repeatInstantly) {
		CompletableFuture<AudioStream> vanilla = loader.getStream(id, repeatInstantly);
		if (offsetSeconds <= 0.0) {
			return vanilla;
		}
		return vanilla.thenApplyAsync(stream -> {
			try {
				SkippingAudioStream skipping = SkippingAudioStream.ofSeconds(stream, offsetSeconds);
				skipping.skipNow(SKIP_CHUNK_BYTES);
				return (AudioStream) skipping;
			} catch (IOException | RuntimeException e) {
				LOGGER.error("Could not skip {} s into {}", offsetSeconds, id, e);
				try {
					stream.close();
				} catch (IOException closeError) {
					e.addSuppressed(closeError);
				}
				throw new CompletionException(e);
			}
		}, Util.backgroundExecutor());
	}
}
