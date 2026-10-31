package com.manzarig.fadoecravo.client.gui;

import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;

/**
 * The geometry of the tuning dial: how a frequency maps to an x position in GUI pixels and back,
 * where the tick marks go and when the needle has moved over a station. It is pure arithmetic and
 * needs neither Minecraft nor a screen, so it is unit tested.
 *
 * <p>All x values are relative to whatever origin the caller draws the dial from. A tick mark is a
 * one pixel wide column; {@link #tickX(int)} is the column and {@link #centerX(double)} the middle of
 * that column, which is what the mouse is compared with. With the dial {@link RadioLayout#DIAL_WIDTH}
 * pixels wide, every 10 kHz tick is exactly two pixels from the next, so the ruler looks even at any
 * GUI scale.
 */
public final class DialGeometry {
	/** Lowest frequency on the dial in kHz. */
	public static final int MIN_KHZ = RadioBlockEntity.MIN_KHZ;
	/** Highest frequency on the dial in kHz. */
	public static final int MAX_KHZ = RadioBlockEntity.MAX_KHZ;
	/** Distance between two small tick marks in kHz. */
	public static final int TICK_STEP_KHZ = 10;
	/** Distance between two tall tick marks in kHz. */
	public static final int MAJOR_STEP_KHZ = 100;
	/** Distance between two numbered tick marks in kHz. */
	public static final int LABEL_STEP_KHZ = 200;
	/** The first numbered tick mark; 600, 800, 1000 &hellip; 1600 carry a label. */
	public static final int FIRST_LABEL_KHZ = 600;

	private final int left;
	private final int width;
	private final int minKhz;
	private final int maxKhz;

	/** A dial whose first tick column is {@code left} and whose columns span {@code width} pixels. */
	public DialGeometry(int left, int width) {
		this(left, width, MIN_KHZ, MAX_KHZ);
	}

	public DialGeometry(int left, int width, int minKhz, int maxKhz) {
		if (width <= 0) {
			throw new IllegalArgumentException("width must be positive: " + width);
		}
		if (maxKhz <= minKhz) {
			throw new IllegalArgumentException("maxKhz must be above minKhz: " + minKhz + ".." + maxKhz);
		}
		this.left = left;
		this.width = width;
		this.minKhz = minKhz;
		this.maxKhz = maxKhz;
	}

	public int left() {
		return left;
	}

	public int width() {
		return width;
	}

	/** Brings a frequency into the dial's band. */
	public int clamp(int khz) {
		return Math.max(minKhz, Math.min(maxKhz, khz));
	}

	/** The middle of the tick column for {@code khz}, which may be fractional. Not clamped. */
	public double centerX(double khz) {
		// Multiply before dividing, so that whole kHz steps give exact pixel values.
		return left + 0.5 + (khz - minKhz) * width / (maxKhz - minKhz);
	}

	/** The pixel column of the tick for {@code khz}; the needle and the station marks are centred on it. */
	public int tickX(int khz) {
		return (int) Math.floor(centerX(clamp(khz)));
	}

	/**
	 * The whole-kHz frequency under an x position, for the mouse. It rounds to the nearest kHz and
	 * stays inside the band, so a click or drag beyond either end gives the end frequency.
	 */
	public int khzForX(double x) {
		double khz = minKhz + (x - left - 0.5) * (maxKhz - minKhz) / width;
		// Clamp as a double first, so that an absurd x cannot overflow the conversion to int.
		return clamp((int) Math.round(Math.max(minKhz, Math.min(maxKhz, khz))));
	}

	/** Whether the tick for {@code khz} is a tall one, every 100 kHz. */
	public static boolean isMajor(int khz) {
		return Math.floorMod(khz, MAJOR_STEP_KHZ) == 0;
	}

	/** Whether the tick for {@code khz} carries a number: 600, 800, 1000, 1200, 1400 and 1600. */
	public static boolean isLabelled(int khz) {
		return khz >= FIRST_LABEL_KHZ && Math.floorMod(khz - FIRST_LABEL_KHZ, LABEL_STEP_KHZ) == 0;
	}

	/** The frequency of the first tick mark at or above the lowest frequency. */
	public int firstTickKhz() {
		return Math.floorDiv(minKhz + TICK_STEP_KHZ - 1, TICK_STEP_KHZ) * TICK_STEP_KHZ;
	}

	/** The frequency of the last tick mark at or below the highest frequency. */
	public int lastTickKhz() {
		return Math.floorDiv(maxKhz, TICK_STEP_KHZ) * TICK_STEP_KHZ;
	}

	/**
	 * Whether moving the needle from {@code previousKhz} to {@code currentKhz} brings it into the
	 * window {@code centerKhz +- radiusKhz}. That is the case when it starts outside the window and
	 * either ends inside or travels across it. The second case matters because a Shift-scroll step
	 * of 10 kHz or a quick drag can jump over a window that is only {@code 2 * radius + 1} kHz wide.
	 */
	public static boolean entersWindow(int previousKhz, int currentKhz, int centerKhz, int radiusKhz) {
		int windowLow = centerKhz - radiusKhz;
		int windowHigh = centerKhz + radiusKhz;
		if (previousKhz >= windowLow && previousKhz <= windowHigh) {
			return false;
		}
		int low = Math.min(previousKhz, currentKhz);
		int high = Math.max(previousKhz, currentKhz);
		return high >= windowLow && low <= windowHigh;
	}

	/** Whether the move enters the window of at least one of the stations at {@code centersKhz}. */
	public static boolean entersAnyWindow(int previousKhz, int currentKhz, int[] centersKhz, int radiusKhz) {
		for (int center : centersKhz) {
			if (entersWindow(previousKhz, currentKhz, center, radiusKhz)) {
				return true;
			}
		}
		return false;
	}
}
