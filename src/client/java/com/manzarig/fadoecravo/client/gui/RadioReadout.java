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
 * <p>With the radio switched off ({@link Status#POWER_OFF}) nothing is received: the signal is 0 and
 * neither {@code locked} nor {@code weak} is set. The station the dial points at is still named, so
 * that the player can tune a radio before switching it on.
 *
 * @param status  what is on the dial
 * @param station the strongest station received, or {@code null} for {@link Status#NO_STATION} and
 *                for a radio that is off with nothing under the needle
 * @param signal  reception strength in {@code [0, 1]}, from {@link Tuning}; 0 when the radio is off
 * @param locked  whether the station is on the air, the dial is within its clear window and the
 *                station is strong enough ({@link #STRONG_SIGNAL}) to come in at full strength
 * @param weak    whether the station is on the air and the dial is within its clear window, but the
 *                station is too weak ({@code signalStrength} below {@link #STRONG_SIGNAL}) to come in
 *                clearly, so the screen says "Weak signal" where it would say "Signal locked"
 */
public record RadioReadout(Status status, StationDefinition station, double signal, boolean locked, boolean weak) {
	/** What the dial is tuned to. */
	public enum Status {
		/** The radio is switched off: no reception, whatever the dial says. */
		POWER_OFF,
		/** No station is received at all: the radio plays static. */
		NO_STATION,
		/** A station is received but it has no programme in the current phase of the day: carrier hiss only. */
		OFF_AIR,
		/** A station is received and broadcasting. */
		ON_AIR
	}

	/** A station with at least this {@code signalStrength} can be "locked"; a weaker one is only "weak". */
	public static final double STRONG_SIGNAL = 0.75;

	private static final RadioReadout NONE = new RadioReadout(Status.NO_STATION, null, 0.0, false, false);

	/**
	 * Works out the readout for the dial at {@code khz}.
	 *
	 * @param stations the stations that exist
	 * @param khz      the dial frequency
	 * @param dayTime  the world's day time, as {@code Level#getDayTime()}
	 * @param on       whether the radio is switched on
	 */
	public static RadioReadout of(Collection<StationDefinition> stations, int khz, long dayTime, boolean on) {
		Optional<Tuning.Tuned> best = Tuning.best(stations, khz);
		if (!on) {
			return new RadioReadout(Status.POWER_OFF, best.map(Tuning.Tuned::station).orElse(null), 0.0, false, false);
		}
		if (best.isEmpty()) {
			return NONE;
		}
		StationDefinition station = best.get().station();
		double signal = best.get().signal();
		PhaseDefinition phase = station.phases().getOrDefault(Phase.of(dayTime), PhaseDefinition.OFF_AIR);
		if (!phase.onAir()) {
			return new RadioReadout(Status.OFF_AIR, station, signal, false, false);
		}
		boolean inClearWindow = Tuning.falloff((double) khz - station.frequencyKhz()) >= 1.0;
		boolean strong = station.signalStrength() >= STRONG_SIGNAL;
		return new RadioReadout(Status.ON_AIR, station, signal, inClearWindow && strong, inClearWindow && !strong);
	}
}
