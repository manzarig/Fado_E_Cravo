package com.manzarig.fadoecravo.client.audio;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Counts the radios that add up to one sound, so that {@link RadioMix#crowdGain} can turn each of
 * them down. Radios playing the same station are in step to within a fraction of a second, so four
 * of them are 6 dB louder than one; the player hears them as one louder programme.
 *
 * <p>The crowd of a radio is every playing radio that is tuned to the same station and within
 * {@value #RADIUS_BLOCKS} blocks of the listener, itself included when it is that close. Radios
 * further away are not counted: at that distance a radio is already at half volume or less, so a
 * lone distant radio is not quieted, and it adds little to a group that is near. Every radio of a
 * station gets the same count, near or far, so the group stays even. Radios tuned to nothing are one
 * group, because all of them play the static.
 *
 * <p>Pure maths on positions; it needs no game.
 */
final class RadioCrowd {
	/** Radios further than this from the listener are not counted. At 32 blocks a sound is down by 6 dB. */
	static final double RADIUS_BLOCKS = 32.0;

	private RadioCrowd() {
	}

	/**
	 * A radio being played.
	 *
	 * @param pos       the radio block
	 * @param stationId the station it is tuned to, or null when none comes in
	 */
	record Radio(BlockPos pos, @Nullable String stationId) {
	}

	/**
	 * The size of the crowd of each radio.
	 *
	 * @param radios the radios being played
	 * @param x      listener position
	 * @param y      listener position
	 * @param z      listener position
	 * @return the count for every position in {@code radios}, at least 1; the distance is measured to the middle of the block
	 */
	static Map<BlockPos, Integer> sizes(List<Radio> radios, double x, double y, double z) {
		double radiusSqr = RADIUS_BLOCKS * RADIUS_BLOCKS;
		Map<BlockPos, Integer> sizes = new HashMap<>();
		for (Radio radio : radios) {
			int near = 0;
			for (Radio other : radios) {
				if (Objects.equals(other.stationId(), radio.stationId()) && distanceSqr(other.pos(), x, y, z) <= radiusSqr) {
					near++;
				}
			}
			sizes.put(radio.pos(), Math.max(1, near));
		}
		return sizes;
	}

	private static double distanceSqr(BlockPos pos, double x, double y, double z) {
		double dx = pos.getX() + 0.5 - x;
		double dy = pos.getY() + 0.5 - y;
		double dz = pos.getZ() + 0.5 - z;
		return dx * dx + dy * dy + dz * dz;
	}
}
