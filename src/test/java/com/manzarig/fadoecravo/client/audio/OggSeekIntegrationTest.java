package com.manzarig.fadoecravo.client.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.FiniteAudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Decodes the real {@code seek_test.ogg} with Minecraft's own decoder ({@link JOrbisAudioStream},
 * pure Java, no natives) and checks that skipping lands on the right part of the clip.
 *
 * <p>The clip is 60 s, mono, 22 050 Hz, made of twelve 5 s sine segments with 10 ms fades at the
 * joins. Segment i covers [5i, 5i + 5) s at 300 + 100 * i Hz:
 *
 * <pre>
 *   s   0-5  300 Hz     5-10  400 Hz    10-15  500 Hz    15-20  600 Hz
 *      20-25  700 Hz    25-30  800 Hz   30-35  900 Hz    35-40 1000 Hz
 *      40-45 1100 Hz    45-50 1200 Hz   50-55 1300 Hz    55-60 1400 Hz
 * </pre>
 *
 * <p>Regenerate with ffmpeg (one {@code aevalsrc} input per segment, each with
 * {@code afade=t=in:d=0.01,afade=t=out:st=4.99:d=0.01}, joined by {@code concat=n=12:v=0:a=1}), then
 * {@code -ac 1 -ar 22050 -c:a libvorbis -q:a 2}.
 */
class OggSeekIntegrationTest {
	private static final String OGG_RESOURCE = "/assets/fadoecravo/sounds/test/seek_test.ogg";
	private static final ResourceLocation OGG_PATH = ResourceLocation.fromNamespaceAndPath("fadoecravo", "sounds/test/seek_test.ogg");
	private static final double WINDOW_SECONDS = 0.2;

	private static InputStream openOgg() {
		InputStream in = OggSeekIntegrationTest.class.getResourceAsStream(OGG_RESOURCE);
		assertNotNull(in, "seek_test.ogg must be on the test classpath: " + OGG_RESOURCE);
		return in;
	}

	/** Every PCM byte of the clip, decoded with no skipping. */
	private static byte[] decodeAll() throws IOException {
		try (FiniteAudioStream stream = new JOrbisAudioStream(openOgg())) {
			return toBytes(stream.readAll());
		}
	}

	private static byte[] toBytes(ByteBuffer buffer) {
		byte[] bytes = new byte[buffer.remaining()];
		buffer.duplicate().get(bytes);
		return bytes;
	}

	/** Reads at least {@code count} bytes the way the sound engine would, in 1 s requests. */
	private static byte[] readAtLeast(AudioStream stream, int count) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		while (out.size() < count) {
			ByteBuffer buffer = stream.read(44_100);
			if (buffer.remaining() == 0) {
				break;
			}
			out.write(toBytes(buffer));
		}
		return out.toByteArray();
	}

	/** Frequency from positive-going zero crossings over a window of {@code seconds}. */
	private static double estimateHertz(byte[] pcm16Mono, double seconds, int sampleRate) {
		ByteBuffer samples = ByteBuffer.wrap(pcm16Mono).order(ByteOrder.nativeOrder());
		int count = (int) Math.round(seconds * sampleRate);
		int crossings = 0;
		short previous = samples.getShort(0);
		for (int i = 1; i < count; i++) {
			short current = samples.getShort(i * 2);
			if (previous < 0 && current >= 0) {
				crossings++;
			}
			previous = current;
		}
		return crossings / seconds;
	}

	@Test
	void decoderRunsInPlainJUnitAndReportsTheContractFormat() throws IOException {
		try (JOrbisAudioStream stream = new JOrbisAudioStream(openOgg())) {
			AudioFormat format = stream.getFormat();
			assertEquals(22_050f, format.getSampleRate());
			assertEquals(1, format.getChannels());
			assertEquals(16, format.getSampleSizeInBits());
			assertTrue(format.isBigEndian() == false);
		}
		byte[] all = decodeAll();
		double seconds = all.length / 2.0 / 22_050.0;
		assertEquals(60.0, seconds, 0.1, "decoded length");
	}

	@ParameterizedTest(name = "start at {0} s sounds like {1} Hz")
	@CsvSource({"0, 300", "12.5, 500", "31, 900", "57, 1400"})
	void skippedStreamStartsInTheRightSegment(double offsetSeconds, double expectedHertz) throws IOException {
		int windowBytes = (int) Math.round(WINDOW_SECONDS * 22_050) * 2;
		byte[] window;
		long startedAt = System.nanoTime();
		try (AudioStream stream = SkippingAudioStream.ofSeconds(new JOrbisAudioStream(openOgg()), offsetSeconds)) {
			window = readAtLeast(stream, windowBytes);
		}
		long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
		assertTrue(window.length >= windowBytes);

		double hertz = estimateHertz(window, WINDOW_SECONDS, 22_050);
		System.out.printf("offset %5.1f s: measured %7.1f Hz (expected %.0f Hz), open+skip+read took %d ms%n",
			offsetSeconds, hertz, expectedHertz, millis);
		assertEquals(expectedHertz, hertz, expectedHertz * 0.05, "dominant frequency after skipping " + offsetSeconds + " s");

		// Sample-exact: the bytes must be the same ones a full decode has at that position.
		byte[] all = decodeAll();
		int from = (int) PcmOffsets.bytesToSkip(22_050, 1, 2, offsetSeconds);
		byte[] expected = new byte[windowBytes];
		System.arraycopy(all, from, expected, 0, windowBytes);
		byte[] actual = new byte[windowBytes];
		System.arraycopy(window, 0, actual, 0, windowBytes);
		assertArrayEquals(expected, actual, "PCM after the skip equals the full decode from the same position");
	}

	@Test
	void restOfTheClipIsExactlyTheTailOfTheFullDecode() throws IOException {
		byte[] all = decodeAll();
		int from = (int) PcmOffsets.bytesToSkip(22_050, 1, 2, 31.0);
		try (AudioStream stream = SkippingAudioStream.ofSeconds(new JOrbisAudioStream(openOgg()), 31.0)) {
			ByteArrayOutputStream rest = new ByteArrayOutputStream();
			while (true) {
				ByteBuffer buffer = stream.read(44_100);
				if (buffer.remaining() == 0) {
					break;
				}
				rest.write(toBytes(buffer));
			}
			byte[] expected = new byte[all.length - from];
			System.arraycopy(all, from, expected, 0, expected.length);
			assertArrayEquals(expected, rest.toByteArray());
		}
	}

	@Test
	void offsetPastTheEndOfTheClipEndsTheStream() throws IOException {
		try (AudioStream stream = SkippingAudioStream.ofSeconds(new JOrbisAudioStream(openOgg()), 75.0)) {
			assertEquals(0, stream.read(44_100).remaining());
			assertEquals(0, stream.read(44_100).remaining());
		}
	}

	@Test
	void radioSoundInstanceSkipsThroughVanillasSoundBufferLibrary() throws Exception {
		// A real SoundBufferLibrary over an in-memory resource pack: the same getStream call the
		// sound engine makes, wrapped by the same override the game uses.
		ResourceProvider provider = ResourceProvider.fromMap(Map.of(OGG_PATH, new Resource(null, OggSeekIntegrationTest::openOgg)));
		SoundBufferLibrary library = new SoundBufferLibrary(provider);
		RadioSoundInstance radio = new RadioSoundInstance(
			ResourceLocation.fromNamespaceAndPath("fadoecravo", "test.seek_test"), new BlockPos(0, 64, 0), 31.0, 1.0f);

		int windowBytes = (int) Math.round(WINDOW_SECONDS * 22_050) * 2;
		try (AudioStream stream = radio.getAudioStream(library, OGG_PATH, false).get(30, TimeUnit.SECONDS)) {
			assertTrue(stream instanceof SkippingAudioStream);
			double hertz = estimateHertz(readAtLeast(stream, windowBytes), WINDOW_SECONDS, 22_050);
			System.out.printf("RadioSoundInstance at 31 s: measured %.1f Hz%n", hertz);
			assertEquals(900.0, hertz, 45.0);
		}

		// Offset 0 hands back vanilla's own stream untouched.
		RadioSoundInstance fromStart = new RadioSoundInstance(
			ResourceLocation.fromNamespaceAndPath("fadoecravo", "test.seek_test"), new BlockPos(0, 64, 0), 0.0, 1.0f);
		try (AudioStream stream = fromStart.getAudioStream(library, OGG_PATH, false).get(30, TimeUnit.SECONDS)) {
			assertTrue(stream instanceof JOrbisAudioStream);
			assertEquals(300.0, estimateHertz(readAtLeast(stream, windowBytes), WINDOW_SECONDS, 22_050), 15.0);
		}
	}
}
