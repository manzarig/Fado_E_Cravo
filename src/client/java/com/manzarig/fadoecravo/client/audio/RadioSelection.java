package com.manzarig.fadoecravo.client.audio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/**
 * Picks which of the switched-on radios around the listener get to make a sound. The sound engine
 * has few streaming channels (vanilla opens 8, shared with music and jukeboxes) and every radio on
 * the air needs one, so only the nearest few are heard.
 *
 * <p>A radio that was already chosen last tick is treated as {@link #KEEP_BONUS_BLOCKS} closer than
 * it is. Without that, two radios at almost the same distance would swap places every time the
 * listener moved a step, and each swap fades one out and starts the other part-way into its clip.
 *
 * <p>Pure maths on positions; it needs no game.
 */
final class RadioSelection {
	/** How many radios are heard at once. */
	static final int MAX_RADIOS = 4;
	/** Radios further than this from the listener are ignored. Sounds fade out at 64 blocks, so they start a little before they can be heard. */
	static final double RANGE_BLOCKS = 72.0;
	/** Head start in blocks for a radio that is already playing. */
	static final double KEEP_BONUS_BLOCKS = 2.0;

	private RadioSelection() {
	}

	/**
	 * The radios to play, nearest first.
	 *
	 * @param candidates    radio positions that are switched on
	 * @param alreadyActive whether a radio was chosen last time
	 * @param x             listener position
	 * @param y             listener position
	 * @param z             listener position
	 * @param limit         most radios to return
	 * @param range         radios further than this many blocks from the listener are left out; the
	 *                      distance is measured to the middle of the block
	 * @return up to {@code limit} positions. Equal distances are split by the position, so the answer
	 *         does not depend on the order of {@code candidates}.
	 */
	static List<BlockPos> nearest(Collection<BlockPos> candidates, Predicate<BlockPos> alreadyActive, double x, double y,
		double z, int limit, double range) {
		double rangeSqr = range * range;
		List<Ranked> ranked = new ArrayList<>();
		for (BlockPos pos : candidates) {
			double dx = pos.getX() + 0.5 - x;
			double dy = pos.getY() + 0.5 - y;
			double dz = pos.getZ() + 0.5 - z;
			double distanceSqr = dx * dx + dy * dy + dz * dz;
			if (distanceSqr <= rangeSqr) {
				double score = Math.sqrt(distanceSqr) - (alreadyActive.test(pos) ? KEEP_BONUS_BLOCKS : 0.0);
				ranked.add(new Ranked(pos, score));
			}
		}
		ranked.sort(Comparator.comparingDouble(Ranked::score).thenComparing(Ranked::pos));
		return ranked.stream().limit(Math.max(0, limit)).map(Ranked::pos).toList();
	}

	private record Ranked(BlockPos pos, double score) {
	}
}
