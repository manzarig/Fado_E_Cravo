package com.manzarig.fadoecravo.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TuningTest {
	private static final double DELTA = 1e-12;

	private static StationDefinition station(String id, int khz, double strength) {
		return TestStations.station(id).frequency(khz).strength(strength).build();
	}

	@Test
	void theConstantsAreTheContractedOnes() {
		assertEquals(4, Tuning.CLEAR_KHZ);
		assertEquals(15, Tuning.FADE_KHZ);
	}

	@Test
	void falloffIsFlatThenLinearThenZero() {
		assertEquals(1.0, Tuning.falloff(0), DELTA);
		assertEquals(1.0, Tuning.falloff(4), DELTA);
		assertEquals(1.0, Tuning.falloff(-4), DELTA);
		assertEquals(0.5, Tuning.falloff(9.5), DELTA);
		assertEquals(0.5, Tuning.falloff(-9.5), DELTA);
		assertEquals(0.0, Tuning.falloff(15), DELTA);
		assertEquals(0.0, Tuning.falloff(-15), DELTA);
		assertEquals(0.0, Tuning.falloff(400), DELTA);
		assertEquals(10.0 / 11, Tuning.falloff(5), DELTA);
		assertEquals(1.0 / 11, Tuning.falloff(14), DELTA);
		double previous = 1.0;
		for (double distance = 4; distance <= 15; distance += 0.25) {
			double value = Tuning.falloff(distance);
			assertTrue(value <= previous, "falls with distance at " + distance);
			previous = value;
		}
	}

	@Test
	void signalAtWholeKilohertzOffsets() {
		StationDefinition tejo = TestStations.load("radio_tejo"); // 1035 kHz, strength 1
		assertEquals(1.0, Tuning.signal(tejo, 1035), DELTA);
		assertEquals(1.0, Tuning.signal(tejo, 1035 + 4), DELTA);
		assertEquals(1.0, Tuning.signal(tejo, 1035 - 4), DELTA);
		assertEquals(10.0 / 11, Tuning.signal(tejo, 1035 + 5), DELTA);
		assertEquals(6.0 / 11, Tuning.signal(tejo, 1035 + 9), DELTA);
		assertEquals(5.0 / 11, Tuning.signal(tejo, 1035 - 10), DELTA);
		assertEquals(0.0, Tuning.signal(tejo, 1035 + 15), DELTA);
		assertEquals(0.0, Tuning.signal(tejo, 1035 - 15), DELTA);
		assertEquals(0.0, Tuning.signal(tejo, 530), DELTA);
		assertEquals(0.0, Tuning.signal(tejo, 1600), DELTA);
	}

	@Test
	void signalIsSymmetricAroundTheStation() {
		StationDefinition tejo = TestStations.load("radio_tejo");
		for (int offset = 0; offset <= 20; offset++) {
			assertEquals(Tuning.signal(tejo, 1035 + offset), Tuning.signal(tejo, 1035 - offset), DELTA, "offset " + offset);
		}
	}

	@Test
	void theHalfwayPointIsAHalfAtNineAndAHalfKilohertz() {
		// The dial moves in whole kHz, so 9.5 is only reached through falloff().
		assertEquals(0.5, Tuning.falloff(9.5), DELTA);
		StationDefinition station = station("a", 1000, 1.0);
		assertTrue(Tuning.signal(station, 1009) > 0.5);
		assertTrue(Tuning.signal(station, 1010) < 0.5);
	}

	@Test
	void aWeakStationNeverComesInClearly() {
		StationDefinition atlantico = TestStations.load("radio_atlantico"); // 1440 kHz, strength 0.55
		assertEquals(0.55, atlantico.signalStrength());
		assertEquals(0.55, Tuning.signal(atlantico, 1440), DELTA);
		assertEquals(0.55, Tuning.signal(atlantico, 1444), DELTA);
		assertEquals(0.55 * 5 / 11, Tuning.signal(atlantico, 1450), DELTA);
		assertEquals(0.55 * 6 / 11, Tuning.signal(atlantico, 1431), DELTA);
		assertEquals(0.0, Tuning.signal(atlantico, 1455), DELTA);
		assertEquals(0.0, Tuning.signal(atlantico, 1425), DELTA);
	}

	@Test
	void bestPicksTheStrongestStationOfTheRealOnes() {
		List<StationDefinition> all = new ArrayList<>();
		for (String id : TestStations.IDS) {
			all.add(TestStations.load(id));
		}
		assertBest(all, 1035, "radio_tejo", 1.0);
		assertBest(all, 1038, "radio_tejo", 1.0);
		assertBest(all, 670, "emissora_lusitana", 1.0);
		assertBest(all, 1440, "radio_atlantico", 0.55);
		assertBest(all, 1446, "radio_atlantico", 0.55 * 9 / 11);
		// The gaps between the stations receive nothing.
		assertEquals(Optional.empty(), Tuning.best(all, 900));
		assertEquals(Optional.empty(), Tuning.best(all, 530));
		assertEquals(Optional.empty(), Tuning.best(all, 1600));
		assertEquals(Optional.empty(), Tuning.best(List.of(), 1035));
	}

	private static void assertBest(List<StationDefinition> stations, int khz, String id, double signal) {
		Tuning.Tuned tuned = Tuning.best(stations, khz).orElseThrow();
		assertEquals(id, tuned.station().id(), "at " + khz);
		assertEquals(signal, tuned.signal(), DELTA, "at " + khz);
		assertEquals(Tuning.signal(tuned.station(), khz), tuned.signal(), DELTA);
	}

	@Test
	void bestBetweenTwoCloseStationsFollowsTheDial() {
		StationDefinition low = station("low", 1000, 1.0);
		StationDefinition high = station("high", 1010, 1.0);
		List<StationDefinition> stations = List.of(high, low);
		assertBest(stations, 1000, "low", 1.0);
		assertBest(stations, 1004, "low", 1.0);
		assertBest(stations, 1006, "high", 1.0); // 4 kHz from 'high'
		assertBest(stations, 1010, "high", 1.0);
		assertBest(stations, 1012, "high", 1.0);
		assertBest(stations, 996, "low", 1.0);
		assertBest(stations, 1014, "high", 1.0);
		assertBest(stations, 1018, "high", 7.0 / 11); // 8 kHz from 'high', 18 from 'low'
		assertBest(List.of(low), 1010, "low", 5.0 / 11);
	}

	@Test
	void stationsOutOfRangeOfEachOtherDoNotMask() {
		StationDefinition low = station("low", 1000, 1.0);
		StationDefinition high = station("high", 1012, 1.0);
		// 7 kHz from 'low' (8/11) and 5 kHz from 'high' (10/11).
		Tuning.Tuned tuned = Tuning.best(List.of(low, high), 1007).orElseThrow();
		assertEquals("high", tuned.station().id());
		assertEquals(10.0 / 11, tuned.signal(), DELTA);
	}

	@Test
	void equalSignalsAreSplitByTheLowerFrequencyWhateverTheOrder() {
		StationDefinition low = station("low", 1000, 1.0);
		StationDefinition high = station("high", 1010, 1.0);
		// 1005 is 5 kHz from each, so both come in at 10/11.
		for (List<StationDefinition> stations : List.of(List.of(low, high), List.of(high, low))) {
			Tuning.Tuned tuned = Tuning.best(stations, 1005).orElseThrow();
			assertEquals("low", tuned.station().id());
			assertEquals(10.0 / 11, tuned.signal(), DELTA);
		}
		// Inside both clear zones both are at full strength: still the lower one.
		StationDefinition near = station("near", 1006, 1.0);
		assertEquals("low", Tuning.best(List.of(near, low), 1003).orElseThrow().station().id());
		assertEquals("low", Tuning.best(List.of(low, near), 1003).orElseThrow().station().id());
	}

	@Test
	void sameFrequencyAndStrengthAreSplitById() {
		StationDefinition b = station("b_station", 1000, 1.0);
		StationDefinition a = station("a_station", 1000, 1.0);
		assertEquals("a_station", Tuning.best(List.of(b, a), 1000).orElseThrow().station().id());
		List<StationDefinition> reversed = new ArrayList<>(List.of(b, a));
		Collections.reverse(reversed);
		assertEquals("a_station", Tuning.best(reversed, 1000).orElseThrow().station().id());
	}

	@Test
	void aStrongerStationBeatsAWeakOneEvenWhenFurtherAway() {
		StationDefinition weak = station("weak", 1440, 0.55);
		StationDefinition strong = station("strong", 1448, 1.0);
		// 2 kHz from 'weak' = 0.55, 6 kHz from 'strong' = 9/11 = 0.818 ...
		assertEquals("strong", Tuning.best(List.of(weak, strong), 1442).orElseThrow().station().id());
		// ... but 'weak' wins when 'strong' is too far to matter
		assertEquals("weak", Tuning.best(List.of(weak, strong), 1431).orElseThrow().station().id());
		// A weak station at its best loses against a stronger one that is only a little off.
		StationDefinition faint = station("faint", 1000, 0.3);
		StationDefinition other = station("other", 1009, 0.9);
		assertEquals("other", Tuning.best(List.of(faint, other), 1004).orElseThrow().station().id());
	}
}
