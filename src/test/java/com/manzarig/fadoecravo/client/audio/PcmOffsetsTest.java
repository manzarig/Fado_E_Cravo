package com.manzarig.fadoecravo.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;

class PcmOffsetsTest {
	/** What Minecraft's OGG decoder reports: signed 16-bit little-endian. */
	private static AudioFormat pcm16(int sampleRate, int channels) {
		return new AudioFormat(sampleRate, 16, channels, true, false);
	}

	@Test
	void monoAt22050() {
		// 22 050 frames per second, 2 bytes per frame.
		assertEquals(44_100L, PcmOffsets.bytesToSkip(pcm16(22_050, 1), 1.0));
		assertEquals(551_250L, PcmOffsets.bytesToSkip(pcm16(22_050, 1), 12.5));
		assertEquals(2_513_700L, PcmOffsets.bytesToSkip(pcm16(22_050, 1), 57.0));
	}

	@Test
	void stereoDoublesTheByteCount() {
		assertEquals(88_200L, PcmOffsets.bytesToSkip(pcm16(22_050, 2), 1.0));
		assertEquals(176_400L, PcmOffsets.bytesToSkip(pcm16(44_100, 1), 2.0));
	}

	@Test
	void resultIsAlwaysWholeFrames() {
		for (int channels = 1; channels <= 2; channels++) {
			AudioFormat format = pcm16(22_050, channels);
			int frameSize = 2 * channels;
			for (double seconds : new double[] {0.0001, 0.1, 0.333333, 1.7, 12.345678, 59.999}) {
				long bytes = PcmOffsets.bytesToSkip(format, seconds);
				assertEquals(0L, bytes % frameSize, "seconds=" + seconds + " channels=" + channels);
			}
		}
	}

	@Test
	void roundsToTheNearestFrame() {
		// 0.1 s at 44 100 Hz is 4410 frames even though 0.1 * 44100 is not exact in floating point.
		assertEquals(4410L * 2, PcmOffsets.bytesToSkip(44_100, 1, 2, 0.1));
		// Half a frame and a bit rounds up, just under half rounds down.
		assertEquals(2L, PcmOffsets.bytesToSkip(10, 1, 2, 0.1 * 0.51 * 2));
		assertEquals(0L, PcmOffsets.bytesToSkip(10, 1, 2, 0.1 * 0.49));
	}

	@Test
	void explicitLayoutMatchesFormatOverload() {
		assertEquals(PcmOffsets.bytesToSkip(pcm16(22_050, 2), 3.3), PcmOffsets.bytesToSkip(22_050, 2, 2, 3.3));
		assertEquals(8L * 1000, PcmOffsets.bytesToSkip(1000, 2, 4, 1.0), "float32 stereo is 8 bytes per frame");
	}

	@Test
	void negativeAndNanOffsetsClampToZero() {
		AudioFormat format = pcm16(22_050, 1);
		assertEquals(0L, PcmOffsets.bytesToSkip(format, -5.0));
		assertEquals(0L, PcmOffsets.bytesToSkip(format, -0.0));
		assertEquals(0L, PcmOffsets.bytesToSkip(format, 0.0));
		assertEquals(0L, PcmOffsets.bytesToSkip(format, Double.NaN));
		assertEquals(0L, PcmOffsets.bytesToSkip(format, Double.NEGATIVE_INFINITY));
	}

	@Test
	void hugeOffsetsDoNotOverflow() {
		long bytes = PcmOffsets.bytesToSkip(pcm16(22_050, 2), Double.POSITIVE_INFINITY);
		assertTrue(bytes > 0);
		assertEquals(0L, bytes % 4);
		assertTrue(PcmOffsets.bytesToSkip(pcm16(22_050, 2), 1e300) > 0);
	}

	@Test
	void rejectsFormatsWithoutALayout() {
		assertThrows(IllegalArgumentException.class, () -> PcmOffsets.bytesToSkip(0, 1, 2, 1.0));
		assertThrows(IllegalArgumentException.class, () -> PcmOffsets.bytesToSkip(22_050, 0, 2, 1.0));
		assertThrows(IllegalArgumentException.class, () -> PcmOffsets.bytesToSkip(22_050, 1, 0, 1.0));
		AudioFormat unspecified = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 22_050f, AudioSystem.NOT_SPECIFIED, 1,
			AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED, false);
		assertThrows(IllegalArgumentException.class, () -> PcmOffsets.bytesToSkip(unspecified, 1.0));
	}
}
