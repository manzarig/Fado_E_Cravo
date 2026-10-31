package com.manzarig.fadoecravo.client.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;

class SkippingAudioStreamTest {
	private static final AudioFormat MONO_16 = new AudioFormat(22_050f, 16, 1, true, false);

	/**
	 * Source of 16-bit mono samples whose value is the sample index, so a byte position identifies
	 * itself. Chunk sizes come from {@code chunkPattern} and ignore the requested size, as a real
	 * decoder's do. Chunks are not frame aligned unless the pattern is.
	 */
	private static final class RampStream implements AudioStream {
		private final byte[] pcm;
		private final int[] chunkPattern;
		private int pos;
		private int calls;
		boolean closed;

		RampStream(int samples, int... chunkPattern) {
			pcm = new byte[samples * 2];
			ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(ramp(0, samples));
			this.chunkPattern = chunkPattern;
		}

		@Override
		public AudioFormat getFormat() {
			return MONO_16;
		}

		@Override
		public ByteBuffer read(int size) {
			int chunk = Math.min(chunkPattern[calls++ % chunkPattern.length], pcm.length - pos);
			ByteBuffer buffer = ByteBuffer.allocateDirect(chunk).order(ByteOrder.nativeOrder());
			buffer.put(pcm, pos, chunk).flip();
			pos += chunk;
			return buffer;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	private static short[] ramp(int fromSample, int count) {
		short[] samples = new short[count];
		for (int i = 0; i < count; i++) {
			samples[i] = (short) (fromSample + i);
		}
		return samples;
	}

	private static byte[] drain(AudioStream stream, int requestSize) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		while (true) {
			ByteBuffer buffer = stream.read(requestSize);
			if (buffer.remaining() == 0) {
				return out.toByteArray();
			}
			byte[] bytes = new byte[buffer.remaining()];
			buffer.get(bytes);
			out.write(bytes);
		}
	}

	private static short[] toSamples(byte[] pcm) {
		short[] samples = new short[pcm.length / 2];
		ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
		return samples;
	}

	@Test
	void firstBytesAfterTheSkipAreTheExpectedSamples() throws IOException {
		// Odd, unaligned chunk sizes: 7, 13, 3 and 101 bytes repeating.
		try (SkippingAudioStream stream = new SkippingAudioStream(new RampStream(10_000, 7, 13, 3, 101), 1000 * 2)) {
			ByteBuffer first = stream.read(4096);
			// The chunk that crossed the offset must come back cut exactly at the offset.
			assertTrue(first.remaining() > 0);
			ByteBuffer second = stream.read(4096);
			byte[] bytes = new byte[first.remaining() + second.remaining()];
			int firstLength = first.remaining();
			first.get(bytes, 0, firstLength);
			second.get(bytes, firstLength, bytes.length - firstLength);
			short[] samples = toSamples(bytes);
			assertEquals(1000, samples[0]);
			assertEquals(1001, samples[1]);
			assertEquals(1000 + samples.length - 1, samples[samples.length - 1]);
		}
	}

	@Test
	void restOfTheStreamIsExactlyTheTail() throws IOException {
		int total = 5000;
		int skipSamples = 1234;
		SkippingAudioStream stream = new SkippingAudioStream(new RampStream(total, 7, 13, 3, 101, 4001), skipSamples * 2L);
		assertArrayEquals(ramp(skipSamples, total - skipSamples), toSamples(drain(stream, 512)));
	}

	@Test
	void skipEndingExactlyOnAChunkBoundary() throws IOException {
		// Chunks of 100 bytes, skip of 300 bytes: the third chunk is consumed whole.
		SkippingAudioStream stream = new SkippingAudioStream(new RampStream(1000, 100), 300);
		assertArrayEquals(ramp(150, 850), toSamples(drain(stream, 64)));
	}

	@Test
	void oneHugeChunkIsCutAtTheOffset() throws IOException {
		SkippingAudioStream stream = new SkippingAudioStream(new RampStream(1000, 100_000), 400);
		ByteBuffer first = stream.read(64);
		assertEquals(2000 - 400, first.remaining());
		assertEquals(200, first.order(ByteOrder.LITTLE_ENDIAN).getShort(first.position()));
	}

	@Test
	void zeroAndNegativeSkipPassThrough() throws IOException {
		assertArrayEquals(ramp(0, 600), toSamples(drain(new SkippingAudioStream(new RampStream(600, 7, 50), 0), 64)));
		assertArrayEquals(ramp(0, 600), toSamples(drain(new SkippingAudioStream(new RampStream(600, 7, 50), -10), 64)));
	}

	@Test
	void offsetPastTheEndGivesAnEmptyBufferForever() throws IOException {
		SkippingAudioStream stream = new SkippingAudioStream(new RampStream(100, 7, 13), 100 * 2 + 2);
		for (int i = 0; i < 3; i++) {
			ByteBuffer buffer = stream.read(1024);
			assertEquals(0, buffer.remaining());
			assertTrue(buffer.isDirect(), "OpenAL reads the buffer by address");
		}
	}

	@Test
	void offsetExactlyAtTheEndGivesAnEmptyBuffer() throws IOException {
		SkippingAudioStream stream = new SkippingAudioStream(new RampStream(100, 7, 13), 100 * 2);
		assertEquals(0, stream.read(1024).remaining());
	}

	@Test
	void skipNowDoesTheWorkEarlyAndReadReturnsTheLeftover() throws IOException {
		RampStream source = new RampStream(2000, 64);
		SkippingAudioStream stream = new SkippingAudioStream(source, 1000 * 2);
		stream.skipNow(64);
		int consumed = source.pos;
		assertTrue(consumed >= 2000, "the source was read up to the offset");
		stream.skipNow(64); // idempotent
		assertEquals(consumed, source.pos);
		assertArrayEquals(ramp(1000, 1000), toSamples(drain(stream, 256)));
	}

	@Test
	void ofSecondsUsesTheSourceFormat() throws IOException {
		// 22 050 Hz mono: 0.1 s is 2205 samples.
		SkippingAudioStream stream = SkippingAudioStream.ofSeconds(new RampStream(5000, 100), 0.1);
		assertEquals(2205, toSamples(drain(stream, 512))[0]);
	}

	@Test
	void delegatesFormatAndClose() throws IOException {
		RampStream source = new RampStream(10, 4);
		SkippingAudioStream stream = new SkippingAudioStream(source, 0);
		assertEquals(MONO_16, stream.getFormat());
		assertFalse(source.closed);
		stream.close();
		assertTrue(source.closed);
	}
}
