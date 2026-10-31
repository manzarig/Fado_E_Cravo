package com.manzarig.fadoecravo.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhaseTest {
	@ParameterizedTest
	@CsvSource({
		"0, DAWN", "999, DAWN", "1000, DAY", "11999, DAY", "12000, DUSK", "13799, DUSK",
		"13800, NIGHT", "22999, NIGHT", "23000, DAWN", "23999, DAWN",
	})
	void phaseBoundariesWithinOneDay(long dayTime, Phase expected) {
		assertEquals(expected, Phase.of(dayTime));
	}

	@ParameterizedTest
	@ValueSource(longs = {-100, -3, -1, 1, 3, 100, 1_000_000, 123_456_789})
	void phaseBoundariesRepeatEveryDay(long days) {
		long shift = days * 24000L;
		assertEquals(Phase.DAWN, Phase.of(shift));
		assertEquals(Phase.DAWN, Phase.of(shift + 999));
		assertEquals(Phase.DAY, Phase.of(shift + 1000));
		assertEquals(Phase.DAY, Phase.of(shift + 11999));
		assertEquals(Phase.DUSK, Phase.of(shift + 12000));
		assertEquals(Phase.DUSK, Phase.of(shift + 13799));
		assertEquals(Phase.NIGHT, Phase.of(shift + 13800));
		assertEquals(Phase.NIGHT, Phase.of(shift + 22999));
		assertEquals(Phase.DAWN, Phase.of(shift + 23000));
		assertEquals(Phase.DAWN, Phase.of(shift + 23999));
	}

	@Test
	void negativeDayTimesWrapBackwards() {
		assertEquals(Phase.DAWN, Phase.of(-1)); // tick 23999
		assertEquals(Phase.DAWN, Phase.of(-1000)); // tick 23000
		assertEquals(Phase.NIGHT, Phase.of(-1001)); // tick 22999
		assertEquals(Phase.NIGHT, Phase.of(-10200)); // tick 13800
		assertEquals(Phase.DUSK, Phase.of(-10201)); // tick 13799
		assertEquals(Phase.DAWN, Phase.of(-24000));
		assertEquals(Phase.DAY, Phase.of(-24000 + 1000));
	}

	@Test
	void extremeDayTimesDoNotOverflow() {
		for (long dayTime : new long[] {Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE, Long.MIN_VALUE + 1}) {
			int timeOfDay = BigInteger.valueOf(dayTime).mod(BigInteger.valueOf(24000)).intValue();
			Phase expected = timeOfDay >= 23000 || timeOfDay < 1000 ? Phase.DAWN
				: timeOfDay < 12000 ? Phase.DAY
				: timeOfDay < 13800 ? Phase.DUSK : Phase.NIGHT;
			assertEquals(expected, Phase.of(dayTime), "dayTime " + dayTime);
			BigInteger day = BigInteger.valueOf(dayTime).add(BigInteger.valueOf(1000)).divide(BigInteger.valueOf(24000));
			if (BigInteger.valueOf(dayTime).add(BigInteger.valueOf(1000)).signum() < 0
				&& !BigInteger.valueOf(dayTime).add(BigInteger.valueOf(1000)).mod(BigInteger.valueOf(24000)).equals(BigInteger.ZERO)) {
				day = day.subtract(BigInteger.ONE); // BigInteger divide truncates, floorDiv rounds down
			}
			assertEquals(day.longValueExact(), Phase.dayOf(dayTime), "dayTime " + dayTime);
		}
	}

	@Test
	void phasesTileTheWholeDay() {
		int[] counts = new int[Phase.values().length];
		for (int tick = 0; tick < Phase.DAY_TICKS; tick++) {
			Phase phase = Phase.of(tick);
			counts[phase.ordinal()]++;
			assertTrue(phase.elapsedTicks(tick) < phase.lengthTicks(), "tick " + tick);
			assertEquals(tick == phase.startTick(), phase.elapsedTicks(tick) == 0, "tick " + tick);
		}
		assertEquals(2000, counts[Phase.DAWN.ordinal()]);
		assertEquals(11000, counts[Phase.DAY.ordinal()]);
		assertEquals(1800, counts[Phase.DUSK.ordinal()]);
		assertEquals(9200, counts[Phase.NIGHT.ordinal()]);
		for (Phase phase : Phase.values()) {
			assertEquals(counts[phase.ordinal()], phase.lengthTicks(), phase.name());
		}
	}

	@Test
	void elapsedTicksCountsFromTheStartAndAcrossMidnight() {
		assertEquals(0, Phase.DAWN.elapsedTicks(23000));
		assertEquals(999, Phase.DAWN.elapsedTicks(23999));
		assertEquals(1000, Phase.DAWN.elapsedTicks(24000));
		assertEquals(1000, Phase.DAWN.elapsedTicks(0));
		assertEquals(1999, Phase.DAWN.elapsedTicks(24999));
		assertEquals(1999, Phase.DAWN.elapsedTicks(999));
		assertEquals(0, Phase.DAWN.elapsedTicks(23000 + 24000L * 1_000_000));
		assertEquals(0, Phase.DAY.elapsedTicks(1000));
		assertEquals(10999, Phase.DAY.elapsedTicks(11999));
		assertEquals(0, Phase.NIGHT.elapsedTicks(13800 - 24000));
		for (long dayTime : new long[] {Long.MAX_VALUE, Long.MIN_VALUE}) {
			int timeOfDay = BigInteger.valueOf(dayTime).mod(BigInteger.valueOf(24000)).intValue();
			assertEquals(Math.floorMod(timeOfDay - 23000, 24000), Phase.DAWN.elapsedTicks(dayTime), "dayTime " + dayTime);
		}
	}

	@Test
	void dayIndexIsFloorDivOfShiftedDayTime() {
		for (long dayTime = -100_000; dayTime <= 200_000; dayTime += 7) {
			assertEquals(Math.floorDiv(dayTime + 1000, 24000L), Phase.dayOf(dayTime), "dayTime " + dayTime);
		}
		assertEquals(0, Phase.dayOf(1000));
		assertEquals(0, Phase.dayOf(22999));
		assertEquals(1, Phase.dayOf(23000));
	}

	@Test
	void aDawnKeepsTheSameDayAcrossMidnight() {
		for (long day = -3; day <= 40; day++) {
			long dawnStart = 24000L * day - 1000;
			for (long tick = dawnStart; tick < dawnStart + 2000; tick++) {
				assertEquals(Phase.DAWN, Phase.of(tick));
				assertEquals(day, Phase.dayOf(tick), "dayTime " + tick);
			}
			// The next phase, and the one before, are other instances.
			assertEquals(Phase.DAY, Phase.of(dawnStart + 2000));
			assertEquals(day, Phase.dayOf(dawnStart + 2000));
			assertEquals(Phase.NIGHT, Phase.of(dawnStart - 1));
			assertEquals(day - 1, Phase.dayOf(dawnStart - 1));
		}
		assertEquals(Phase.dayOf(23500 + 24000L * 3), Phase.dayOf(24500 + 24000L * 3));
	}

	@Test
	void everyPhaseInstanceHasOneDayNumber() {
		for (long day = 0; day < 3; day++) {
			for (Phase phase : Phase.values()) {
				long first = 24000L * day + (phase == Phase.DAWN ? -1000 : phase.startTick());
				for (int tick = 0; tick < phase.lengthTicks(); tick++) {
					assertEquals(phase, Phase.of(first + tick));
					assertEquals(day, Phase.dayOf(first + tick), phase + " tick " + tick);
				}
			}
		}
	}

	@Test
	void keysAreLowercasePhaseNames() {
		for (Phase phase : Phase.values()) {
			assertEquals(phase.name().toLowerCase(java.util.Locale.ROOT), phase.key());
			JsonPrimitive json = new JsonPrimitive(phase.key());
			assertEquals(phase, Phase.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(IllegalStateException::new));
			assertEquals(json, Phase.CODEC.encodeStart(JsonOps.INSTANCE, phase).getOrThrow(IllegalStateException::new));
		}
		assertTrue(Phase.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive("noon")).error().isPresent());
		assertTrue(Phase.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive("DAY")).error().isPresent());
	}
}
