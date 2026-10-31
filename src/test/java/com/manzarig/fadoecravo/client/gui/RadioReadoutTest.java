package com.manzarig.fadoecravo.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.manzarig.fadoecravo.broadcast.Phase;
import com.manzarig.fadoecravo.broadcast.PhaseDefinition;
import com.manzarig.fadoecravo.broadcast.StationDefinition;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RadioReadoutTest {
	private static final long NOON = 6000L;
	private static final long MIDNIGHT = 18000L;

	private static final PhaseDefinition ON_AIR = new PhaseDefinition(true, List.of("a"), List.of("b"), List.of(), 0);

	/** A station that broadcasts in the phases given and is off the air in the others. */
	private static StationDefinition station(String id, int khz, double strength, Phase... onAirIn) {
		Map<Phase, PhaseDefinition> phases = new EnumMap<>(Phase.class);
		for (Phase phase : Phase.values()) {
			phases.put(phase, PhaseDefinition.OFF_AIR);
		}
		for (Phase phase : onAirIn) {
			phases.put(phase, ON_AIR);
		}
		return new StationDefinition(1, id, "station.fadoecravo." + id, khz, strength, phases, List.of(), Map.of());
	}

	/** The readout of a radio that is switched on. */
	private static RadioReadout readout(List<StationDefinition> stations, int khz, long dayTime) {
		return RadioReadout.of(stations, khz, dayTime, true);
	}

	@Test
	void nothingTunedWithoutStations() {
		RadioReadout readout = readout(List.of(), 1035, NOON);
		assertEquals(RadioReadout.Status.NO_STATION, readout.status());
		assertNull(readout.station());
		assertEquals(0.0, readout.signal());
		assertFalse(readout.locked());
	}

	@Test
	void nothingTunedBetweenStations() {
		List<StationDefinition> stations = List.of(station("a", 670, 1.0, Phase.DAY), station("b", 1035, 1.0, Phase.DAY));
		RadioReadout readout = readout(stations, 850, NOON);
		assertEquals(RadioReadout.Status.NO_STATION, readout.status());
		assertEquals(0.0, readout.signal());
	}

	@Test
	void aStationOnTheAirIsLockedInsideTheClearWindow() {
		StationDefinition tejo = station("tejo", 1035, 1.0, Phase.DAY);
		for (int khz = 1031; khz <= 1039; khz++) {
			RadioReadout readout = readout(List.of(tejo), khz, NOON);
			assertEquals(RadioReadout.Status.ON_AIR, readout.status(), "khz " + khz);
			assertSame(tejo, readout.station());
			assertEquals(1.0, readout.signal(), 1e-12);
			assertTrue(readout.locked(), "khz " + khz);
		}
	}

	@Test
	void aStationOnTheAirFadesOutsideTheWindow() {
		StationDefinition tejo = station("tejo", 1035, 1.0, Phase.DAY);
		RadioReadout near = readout(List.of(tejo), 1035 + 9, NOON);
		assertEquals(RadioReadout.Status.ON_AIR, near.status());
		assertFalse(near.locked());
		assertEquals(linearFade(9), near.signal(), 1e-12);
		assertEquals(RadioReadout.Status.NO_STATION, readout(List.of(tejo), 1035 + 15, NOON).status());
	}

	/** (15 - distance) / 11, the linear fade between 4 and 15 kHz. */
	private static double linearFade(double distance) {
		return (15 - distance) / 11.0;
	}

	@Test
	void aWeakStationNeverShowsFullSignalAndIsWeakNotLocked() {
		StationDefinition weak = station("weak", 1440, 0.55, Phase.NIGHT);
		RadioReadout readout = readout(List.of(weak), 1440, MIDNIGHT);
		assertEquals(RadioReadout.Status.ON_AIR, readout.status());
		assertEquals(0.55, readout.signal(), 1e-12);
		assertFalse(readout.locked(), "55% is not a lock");
		assertTrue(readout.weak());
	}

	@Test
	void aWeakStationIsWeakAcrossTheWholeClearWindowAndNotBeyond() {
		StationDefinition weak = station("weak", 1440, 0.55, Phase.NIGHT);
		for (int khz = 1436; khz <= 1444; khz++) {
			RadioReadout readout = readout(List.of(weak), khz, MIDNIGHT);
			assertTrue(readout.weak(), "khz " + khz);
			assertFalse(readout.locked(), "khz " + khz);
		}
		for (int khz : new int[] {1435, 1445, 1450}) {
			RadioReadout readout = readout(List.of(weak), khz, MIDNIGHT);
			assertEquals(RadioReadout.Status.ON_AIR, readout.status(), "khz " + khz);
			assertFalse(readout.weak(), "off-centre is neither weak nor locked, khz " + khz);
			assertFalse(readout.locked(), "khz " + khz);
		}
	}

	@Test
	void theLineBetweenLockedAndWeakIsAStrengthOfThreeQuarters() {
		StationDefinition fair = station("fair", 1000, RadioReadout.STRONG_SIGNAL, Phase.DAY);
		assertEquals(0.75, RadioReadout.STRONG_SIGNAL);
		assertTrue(readout(List.of(fair), 1000, NOON).locked(), "exactly 0.75 is strong enough");
		StationDefinition fainter = station("fainter", 1000, 0.74, Phase.DAY);
		RadioReadout readout = readout(List.of(fainter), 1000, NOON);
		assertFalse(readout.locked());
		assertTrue(readout.weak());
	}

	@Test
	void aStrongStationIsNeverWeak() {
		StationDefinition tejo = station("tejo", 1035, 1.0, Phase.DAY);
		for (int khz = 1020; khz <= 1050; khz++) {
			assertFalse(readout(List.of(tejo), khz, NOON).weak(), "khz " + khz);
		}
	}

	@Test
	void aWeakStationOutsideItsHoursIsOffTheAirNotWeak() {
		StationDefinition nightOnly = station("night", 1440, 0.55, Phase.NIGHT);
		RadioReadout readout = readout(List.of(nightOnly), 1440, NOON);
		assertEquals(RadioReadout.Status.OFF_AIR, readout.status());
		assertFalse(readout.weak());
		assertFalse(readout.locked());
	}

	// ------------------------------------------------------------------ power

	@Test
	void aRadioThatIsOffShowsNothingReceivedOnAStation() {
		StationDefinition tejo = station("tejo", 1035, 1.0, Phase.DAY);
		RadioReadout readout = RadioReadout.of(List.of(tejo), 1035, NOON, false);
		assertEquals(RadioReadout.Status.POWER_OFF, readout.status());
		assertEquals(0.0, readout.signal(), "the bar is empty");
		assertFalse(readout.locked(), "no 'Signal locked' with the power off");
		assertFalse(readout.weak());
		assertSame(tejo, readout.station(), "the station under the needle is still known, for pre-tuning");
	}

	@Test
	void aRadioThatIsOffIsOffWhateverTheDialAndTheHour() {
		StationDefinition weak = station("weak", 1440, 0.55, Phase.NIGHT);
		for (long time : new long[] {NOON, MIDNIGHT}) {
			for (int khz : new int[] {530, 850, 1440, 1445, 1600}) {
				RadioReadout readout = RadioReadout.of(List.of(weak), khz, time, false);
				assertEquals(RadioReadout.Status.POWER_OFF, readout.status(), "khz " + khz + " at " + time);
				assertEquals(0.0, readout.signal());
				assertFalse(readout.locked());
				assertFalse(readout.weak());
			}
		}
	}

	@Test
	void aRadioThatIsOffHasNoStationBetweenStations() {
		StationDefinition tejo = station("tejo", 1035, 1.0, Phase.DAY);
		RadioReadout readout = RadioReadout.of(List.of(tejo), 850, NOON, false);
		assertEquals(RadioReadout.Status.POWER_OFF, readout.status());
		assertNull(readout.station());
	}

	@Test
	void switchingOnGivesTheNormalReadoutOfTheSameDial() {
		StationDefinition tejo = station("tejo", 1035, 1.0, Phase.DAY);
		assertEquals(RadioReadout.Status.POWER_OFF, RadioReadout.of(List.of(tejo), 1035, NOON, false).status());
		RadioReadout on = RadioReadout.of(List.of(tejo), 1035, NOON, true);
		assertEquals(RadioReadout.Status.ON_AIR, on.status());
		assertTrue(on.locked());
	}

	@Test
	void aStationOutsideItsHoursIsOffTheAir() {
		StationDefinition nightOnly = station("night", 1440, 0.55, Phase.NIGHT);
		RadioReadout readout = readout(List.of(nightOnly), 1440, NOON);
		assertEquals(RadioReadout.Status.OFF_AIR, readout.status());
		assertSame(nightOnly, readout.station());
		assertEquals(0.55, readout.signal(), 1e-12, "the carrier is still received");
		assertFalse(readout.locked());
	}

	@Test
	void theDayTimeKeepsCountingPastOneDay() {
		StationDefinition dayOnly = station("day", 1035, 1.0, Phase.DAY);
		long noonOfDay9 = 9 * 24000L + NOON;
		assertEquals(RadioReadout.Status.ON_AIR, readout(List.of(dayOnly), 1035, noonOfDay9).status());
		assertEquals(RadioReadout.Status.OFF_AIR, readout(List.of(dayOnly), 1035, 9 * 24000L + MIDNIGHT).status());
	}

	@Test
	void everyPhaseBoundaryIsOnTheRightSide() {
		StationDefinition dawnOnly = station("dawn", 1035, 1.0, Phase.DAWN);
		assertEquals(RadioReadout.Status.ON_AIR, readout(List.of(dawnOnly), 1035, 23000).status());
		assertEquals(RadioReadout.Status.ON_AIR, readout(List.of(dawnOnly), 1035, 999).status());
		assertEquals(RadioReadout.Status.OFF_AIR, readout(List.of(dawnOnly), 1035, 1000).status());
		assertEquals(RadioReadout.Status.OFF_AIR, readout(List.of(dawnOnly), 1035, 22999).status());
	}

	@Test
	void aMissingPhaseCountsAsOffTheAir() {
		StationDefinition noPhases = new StationDefinition(1, "x", "station.fadoecravo.x", 1035, 1.0, Map.of(), List.of(), Map.of());
		RadioReadout readout = readout(List.of(noPhases), 1035, NOON);
		assertEquals(RadioReadout.Status.OFF_AIR, readout.status());
	}

	@Test
	void theStrongerStationIsTheOneShown() {
		StationDefinition a = station("a", 1000, 1.0, Phase.DAY);
		StationDefinition b = station("b", 1010, 1.0, Phase.DAY);
		assertSame(a, readout(List.of(a, b), 1003, NOON).station());
		assertSame(b, readout(List.of(a, b), 1008, NOON).station());
	}
}
