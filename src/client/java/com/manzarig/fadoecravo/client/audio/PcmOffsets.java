package com.manzarig.fadoecravo.client.audio;

import javax.sound.sampled.AudioFormat;

/**
 * Converts a time offset into a number of PCM bytes to discard.
 *
 * <p>Kept free of Minecraft types so it can be unit-tested in plain JUnit. The result is always a
 * whole number of frames (one sample for every channel), so a stream that starts reading after the
 * skip never begins in the middle of a stereo pair or a sample.
 */
public final class PcmOffsets {
	private PcmOffsets() {
	}

	/**
	 * Bytes to skip for {@code offsetSeconds} of audio in {@code format}.
	 *
	 * @param format        PCM format as reported by the audio stream
	 * @param offsetSeconds how far into the clip playback should start; negative or NaN counts as 0
	 * @return a non-negative multiple of the frame size
	 */
	public static long bytesToSkip(AudioFormat format, double offsetSeconds) {
		int channels = format.getChannels();
		int bits = format.getSampleSizeInBits();
		if (channels <= 0 || bits <= 0) {
			throw new IllegalArgumentException("Audio format has no channel count or sample size: " + format);
		}
		return bytesToSkip(Math.round(format.getSampleRate()), channels, (bits + 7) / 8, offsetSeconds);
	}

	/**
	 * Bytes to skip for {@code offsetSeconds} of audio at the given sample layout.
	 *
	 * @param sampleRate     frames per second
	 * @param channels       samples per frame
	 * @param bytesPerSample bytes in one sample (2 for 16-bit PCM)
	 * @param offsetSeconds  negative or NaN counts as 0
	 * @return a non-negative multiple of {@code channels * bytesPerSample}
	 */
	public static long bytesToSkip(int sampleRate, int channels, int bytesPerSample, double offsetSeconds) {
		if (sampleRate <= 0 || channels <= 0 || bytesPerSample <= 0) {
			throw new IllegalArgumentException("sampleRate, channels and bytesPerSample must be positive");
		}
		if (!(offsetSeconds > 0.0)) {
			return 0L;
		}
		long frameSize = (long) channels * bytesPerSample;
		double frames = Math.rint(offsetSeconds * sampleRate);
		long maxFrames = Long.MAX_VALUE / frameSize;
		long wholeFrames = frames >= maxFrames ? maxFrames : (long) frames;
		return wholeFrames * frameSize;
	}
}
