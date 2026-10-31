package com.manzarig.fadoecravo.broadcast;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * How well a radio dial position picks up a station.
 *
 * <p>A station is received at full strength when the dial is within {@link #CLEAR_KHZ} of its
 * frequency. Further away the signal fades out in a straight line and is gone at
 * {@link #FADE_KHZ}. The station's own {@link StationDefinition#signalStrength()} then scales the
 * result, so a weak station never comes in clearly. This class has no state and needs no game.
 */
public final class Tuning {
	/** Distance in kHz from the station's frequency within which it comes in at full strength. */
	public static final int CLEAR_KHZ = 4;
	/** Distance in kHz at which the signal has faded to nothing. */
	public static final int FADE_KHZ = 15;

	private Tuning() {
	}

	/**
	 * A station and the strength it is received at.
	 *
	 * @param station the station
	 * @param signal  reception strength in {@code (0, 1]}
	 */
	public record Tuned(StationDefinition station, double signal) {
	}

	/**
	 * The unscaled reception for a dial that is {@code distanceKhz} away from a station, in
	 * {@code [0, 1]}: 1 up to {@link #CLEAR_KHZ}, then falling linearly to 0 at {@link #FADE_KHZ}.
	 * The direction does not matter.
	 */
	public static double falloff(double distanceKhz) {
		double distance = Math.abs(distanceKhz);
		if (distance <= CLEAR_KHZ) {
			return 1.0;
		}
		if (distance >= FADE_KHZ) {
			return 0.0;
		}
		return (FADE_KHZ - distance) / (FADE_KHZ - CLEAR_KHZ);
	}

	/** Strength in {@code [0, 1]} at which {@code station} is received with the dial at {@code khz}: {@link #falloff} times the signal strength. */
	public static double signal(StationDefinition station, int khz) {
		return falloff((double) khz - station.frequencyKhz()) * station.signalStrength();
	}

	/**
	 * The station that comes in strongest with the dial at {@code khz}. Stations at the same
	 * strength are split by the lower frequency, then by the lower id, so the answer does not
	 * depend on the order of the collection.
	 *
	 * @return the station and its signal, or empty when no station is received at all
	 */
	public static Optional<Tuned> best(Collection<StationDefinition> stations, int khz) {
		return stations.stream()
			.map(station -> new Tuned(station, signal(station, khz)))
			.filter(tuned -> tuned.signal() > 0.0)
			.min(Comparator.comparingDouble(Tuned::signal).reversed()
				.thenComparingInt((Tuned tuned) -> tuned.station().frequencyKhz())
				.thenComparing(tuned -> tuned.station().id()));
	}
}
