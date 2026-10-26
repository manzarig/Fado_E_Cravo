package com.manzarig.fadoecravo.client.gui;

import com.manzarig.fadoecravo.broadcast.Phase;
import com.manzarig.fadoecravo.broadcast.PhaseDefinition;
import com.manzarig.fadoecravo.broadcast.StationDefinition;
import com.manzarig.fadoecravo.broadcast.Tuning;
import java.util.Collection;
import java.util.Optional;

/**
 * What the radio screen says about the dial position: which station is received, whether it is
 * broadcasting a programme at this time of day and how strongly it comes in. Pure logic on top of
 * {@link Tuning}, so it is unit tested.
 *
 * @param status  what is on the dial
 * @param station the strongest station received, or {@code null} for {@link Status#NO_STATION}
 * @param signal  reception strength in {@code [0, 1]}, from {@link Tuning}
 * @param locked  whether the station is on the air and the dial is within its clear window, so
 *                the programme comes in at full strength
 */
public record RadioReadout(Status status, StationDefinition station, double signal, boolean locked) {
	/** What the dial is tuned to. */
	public enum Status {
		/** No station is received at all: the radio plays static. */
		NO_STATION,
		/** A station is received but it has no programme in the current phase of the day: carrier hiss only. */
		OFF_AIR,
		/** A station is received and broadcasting. */
		ON_AIR
	}

	private static final RadioReadout NONE = new RadioReadout(Status.NO_STATION, null, 0.0, false);

	/**
	 * Works out the readout for the dial at {@code khz}.
	 *
	 * @param stations the stations that exist
	 * @param khz      the dial frequency
	 * @param dayTime  the world's day time, as {@code Level#getDayTime()}
	 */
	public static RadioReadout of(Collection<StationDefinition> stations, int khz, long dayTime) {
		Optional<Tuning.Tuned> best = Tuning.best(stations, khz);
		if (best.isEmpty()) {
			return NONE;
		}
		StationDefinition station = best.get().station();
		double signal = best.get().signal();
		PhaseDefinition phase = station.phases().getOrDefault(Phase.of(dayTime), PhaseDefinition.OFF_AIR);
		if (!phase.onAir()) {
			return new RadioReadout(Status.OFF_AIR, station, signal, false);
		}
		boolean locked = Tuning.falloff((double) khz - station.frequencyKhz()) >= 1.0;
		return new RadioReadout(Status.ON_AIR, station, signal, locked);
	}
}
