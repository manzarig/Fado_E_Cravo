package com.manzarig.fadoecravo.broadcast;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.Locale;

/**
 * One of the four parts of the Minecraft day that a station's schedule is written for.
 *
 * <p>The phases tile the whole 24 000-tick day. {@link #DAWN} wraps past midnight, so it has the
 * largest start tick and the smallest end tick. A phase is identified in JSON by its lowercase
 * name ({@code "dawn"}, {@code "day"}, {@code "dusk"}, {@code "night"}).
 *
 * <p>All methods take the day time as the world keeps it ({@code Level#getDayTime()}), which keeps
 * counting up forever rather than wrapping at 24 000. Everything here works for any {@code long},
 * negative values included, and none of it needs a running game.
 */
public enum Phase {
	/** Ticks 23000 to 1000 of the next day, so it straddles midnight. */
	DAWN(23000, 1000),
	/** Ticks 1000 to 12000. */
	DAY(1000, 12000),
	/** Ticks 12000 to 13800. */
	DUSK(12000, 13800),
	/** Ticks 13800 to 23000. */
	NIGHT(13800, 23000);

	/** Length of a Minecraft day in ticks. */
	public static final int DAY_TICKS = 24000;
	/** Ticks added to the day time before dividing into days, so that a dawn is not split at midnight. */
	private static final int DAY_SHIFT_TICKS = 1000;
	/** JSON codec: the lowercase phase name. */
	public static final Codec<Phase> CODEC = Codec.STRING.comapFlatMap(Phase::fromKey, Phase::key);

	private final int startTick;
	private final int endTick;

	Phase(int startTick, int endTick) {
		this.startTick = startTick;
		this.endTick = endTick;
	}

	/** First tick of the phase, as a time of day in {@code [0, 24000)}. */
	public int startTick() {
		return startTick;
	}

	/** First tick after the phase, as a time of day in {@code [0, 24000)}; for {@link #DAWN} this is smaller than {@link #startTick()}. */
	public int endTick() {
		return endTick;
	}

	/** How many ticks the phase lasts: 2000 for dawn, 11000 for day, 1800 for dusk and 9200 for night. */
	public int lengthTicks() {
		return Math.floorMod(endTick - startTick, DAY_TICKS);
	}

	/** Lowercase name used as the key in station files. */
	public String key() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** Whether a time of day in {@code [0, 24000)} falls inside this phase. */
	private boolean contains(int timeOfDay) {
		if (startTick < endTick) {
			return timeOfDay >= startTick && timeOfDay < endTick;
		}
		return timeOfDay >= startTick || timeOfDay < endTick;
	}

	/**
	 * Ticks from the start of this phase to {@code dayTime}, which should be inside the phase. This
	 * is {@code floorMod(dayTime - startTick, 24000)}, so it also works across midnight for dawn.
	 */
	public int elapsedTicks(long dayTime) {
		// Reduce first so that extreme values cannot overflow the subtraction.
		return Math.floorMod((int) Math.floorMod(dayTime, (long) DAY_TICKS) - startTick, DAY_TICKS);
	}

	/** The phase that {@code dayTime} falls in: 23000 up to 1000 is dawn, then day until 12000, dusk until 13800 and night until 23000. */
	public static Phase of(long dayTime) {
		int timeOfDay = (int) Math.floorMod(dayTime, (long) DAY_TICKS);
		for (Phase phase : values()) {
			if (phase.contains(timeOfDay)) {
				return phase;
			}
		}
		throw new IllegalStateException("No phase for time of day " + timeOfDay);
	}

	/**
	 * Number of the phase instance {@code dayTime} belongs to, {@code floorDiv(dayTime + 1000, 24000)}.
	 * The shift of 1000 makes the dawn that begins at tick 23000 and the one-thousand ticks that
	 * finish it after midnight carry the same number, so a whole dawn is one instance. For the other
	 * phases this is the ordinary day number.
	 *
	 * <p>This only holds for a day time that keeps counting up, like {@code Level#getDayTime()}. A
	 * value already wrapped to {@code [0, 24000)} cannot tell which day it is on.
	 */
	public static long dayOf(long dayTime) {
		// Same as floorDiv(dayTime + 1000, 24000) without the overflow near Long.MAX_VALUE.
		int timeOfDay = (int) Math.floorMod(dayTime, (long) DAY_TICKS);
		long day = Math.floorDiv(dayTime, (long) DAY_TICKS);
		return timeOfDay >= DAY_TICKS - DAY_SHIFT_TICKS ? day + 1 : day;
	}

	private static DataResult<Phase> fromKey(String key) {
		for (Phase phase : values()) {
			if (phase.key().equals(key)) {
				return DataResult.success(phase);
			}
		}
		return DataResult.error(() -> "Unknown phase '" + key + "', expected dawn, day, dusk or night");
	}
}
