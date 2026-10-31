package com.manzarig.fadoecravo.broadcast;

/**
 * One phase of one day: the unit a station's schedule is planned in. {@code day} is numbered as
 * {@link Phase#dayOf(long)} numbers it, so a whole dawn, which straddles midnight, is one instance.
 *
 * @param phase the part of the day
 * @param day   the number of the instance
 */
public record PhaseInstance(Phase phase, long day) {
	/** The instance that {@code dayTime} falls in by the clock. */
	public static PhaseInstance of(long dayTime) {
		return new PhaseInstance(Phase.of(dayTime), Phase.dayOf(dayTime));
	}

	/** The instance just before this one: dawn follows the night of the day before, the others follow each other within a day. */
	public PhaseInstance previous() {
		return switch (phase) {
			case DAWN -> new PhaseInstance(Phase.NIGHT, day - 1);
			case DAY -> new PhaseInstance(Phase.DAWN, day);
			case DUSK -> new PhaseInstance(Phase.DAY, day);
			case NIGHT -> new PhaseInstance(Phase.DUSK, day);
		};
	}

	/** Length of the phase in seconds. */
	public double lengthSeconds() {
		return phase.lengthTicks() / 20.0;
	}
}
