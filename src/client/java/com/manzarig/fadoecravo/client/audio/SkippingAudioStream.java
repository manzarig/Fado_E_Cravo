package com.manzarig.fadoecravo.client.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;

/**
 * Wraps an {@link AudioStream} and discards the first {@code bytesToSkip} bytes of PCM, so a clip
 * can start playing partway through. Minecraft's OGG decoder cannot seek, so the skipped part is
 * decoded and thrown away.
 *
 * <p>The skip happens on the first {@link #read(int)}, or earlier if {@link #skipNow(int)} is
 * called. Calling it from a background thread keeps the long decode off the sound thread, which
 * is the one that normally makes the first {@code read}. If the source ends before the offset is
 * reached, every {@code read} returns an empty buffer, which the sound engine treats as the end
 * of the stream.
 *
 * <p>Not thread-safe: use from one thread at a time. Handing it from the priming thread to the
 * sound thread through a {@code CompletableFuture} is a safe publication.
 */
public class SkippingAudioStream implements AudioStream {
	private final AudioStream source;
	private long bytesToSkip;
	private boolean ended;
	/** Bytes of the buffer that straddled the offset, waiting to be returned by the next read. */
	private ByteBuffer leftover;

	/**
	 * @param source      stream to wrap; closed together with this one
	 * @param bytesToSkip PCM bytes to discard, should be a multiple of the frame size; negative counts as 0
	 */
	public SkippingAudioStream(AudioStream source, long bytesToSkip) {
		this.source = source;
		this.bytesToSkip = Math.max(0L, bytesToSkip);
	}

	/** Skips {@code offsetSeconds} into {@code source}, using the source's own format to size the skip. */
	public static SkippingAudioStream ofSeconds(AudioStream source, double offsetSeconds) {
		return new SkippingAudioStream(source, PcmOffsets.bytesToSkip(source.getFormat(), offsetSeconds));
	}

	@Override
	public AudioFormat getFormat() {
		return source.getFormat();
	}

	/**
	 * Does the skip now instead of on the first read. Does nothing if it is already done.
	 *
	 * @param chunkSize size passed to the source's {@code read} while discarding
	 */
	public void skipNow(int chunkSize) throws IOException {
		int chunk = Math.max(1, chunkSize);
		while (bytesToSkip > 0 && !ended) {
			ByteBuffer buffer = source.read(chunk);
			int available = buffer == null ? 0 : buffer.remaining();
			if (available == 0) {
				ended = true;
				bytesToSkip = 0L;
			} else if (available <= bytesToSkip) {
				bytesToSkip -= available;
			} else {
				// Not sliced: the engine hands this to OpenAL by address, and position() is honoured.
				buffer.position(buffer.position() + (int) bytesToSkip);
				bytesToSkip = 0L;
				leftover = buffer;
			}
		}
	}

	@Override
	public ByteBuffer read(int size) throws IOException {
		skipNow(size);
		if (leftover != null) {
			ByteBuffer buffer = leftover;
			leftover = null;
			return buffer;
		}
		if (ended) {
			return emptyBuffer();
		}
		return source.read(size);
	}

	@Override
	public void close() throws IOException {
		leftover = null;
		source.close();
	}

	private static ByteBuffer emptyBuffer() {
		// Direct, like every buffer the vanilla decoder returns, because OpenAL reads it by address.
		return ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());
	}
}
