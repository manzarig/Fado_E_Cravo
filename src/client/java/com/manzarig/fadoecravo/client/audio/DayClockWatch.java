package com.manzarig.fadoecravo.client.audio;

/**
 * Notices when the world's day time has stopped, which is the case with {@code doDaylightCycle false}.
 * A radio then has no use for the clock: asking it where the programme should be would give the same
 * answer for ever, and a radio that restarted its clip whenever it differed from that answer would
 * restart it every few seconds. While the clock is frozen a radio just plays on from clip to clip.
 *
 * <p>Call {@link #observe} once per client tick with {@code Level#getDayTime()}. The clock counts as
 * frozen once the value has been the same for {@link SyncPolicy#FROZEN_AFTER_TICKS} ticks in a row
 * (2 s); any change, forwards or backwards, ends it at once.
 */
final class DayClockWatch {
	private boolean seen;
	private long lastDayTime;
	private int unchangedTicks;

	/** Records this tick's day time. */
	void observe(long dayTime) {
		if (!seen || dayTime != lastDayTime) {
			seen = true;
			lastDayTime = dayTime;
			unchangedTicks = 0;
		} else if (unchangedTicks < Integer.MAX_VALUE) {
			unchangedTicks++;
		}
	}

	/** Whether the day time has stood still for long enough that it should not be trusted to move. */
	boolean isFrozen() {
		return seen && unchangedTicks >= SyncPolicy.FROZEN_AFTER_TICKS;
	}

	/** Forgets what was seen, e.g. for a new level. */
	void reset() {
		seen = false;
		unchangedTicks = 0;
	}
}
