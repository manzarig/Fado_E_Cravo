package com.manzarig.fadoecravo.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class RadioSelectionTest {
	private static final Predicate<BlockPos> NONE_ACTIVE = pos -> false;

	/** Radios in a row along x at the given distances from a listener standing at the origin's block centre. */
	private static List<BlockPos> row(int... xs) {
		List<BlockPos> radios = new ArrayList<>();
		for (int x : xs) {
			radios.add(new BlockPos(x, 0, 0));
		}
		return radios;
	}

	private static List<BlockPos> nearest(List<BlockPos> radios, Predicate<BlockPos> active, int limit, double range) {
		return RadioSelection.nearest(radios, active, 0.5, 0.5, 0.5, limit, range);
	}

	@Test
	void picksTheNearestInOrder() {
		List<BlockPos> radios = row(30, 5, 50, 10, 20, 40);
		assertEquals(row(5, 10, 20, 30), nearest(radios, NONE_ACTIVE, 4, 72));
	}

	@Test
	void returnsFewerWhenThereAreFewer() {
		assertEquals(row(3, 8), nearest(row(8, 3), NONE_ACTIVE, 4, 72));
		assertTrue(nearest(List.of(), NONE_ACTIVE, 4, 72).isEmpty());
	}

	@Test
	void leavesOutRadiosBeyondTheRange() {
		List<BlockPos> radios = row(10, 72, 73, 100);
		assertEquals(row(10, 72), nearest(radios, NONE_ACTIVE, 4, 72), "exactly 72 blocks is still in range");
	}

	@Test
	void measuresToTheMiddleOfTheBlockInThreeDimensions() {
		BlockPos above = new BlockPos(0, 70, 0);
		BlockPos aside = new BlockPos(0, 0, 71);
		// The listener is 70 blocks below one and 71 away from the other.
		assertEquals(List.of(above, aside), nearest(List.of(aside, above), NONE_ACTIVE, 4, 72));
		assertEquals(List.of(), nearest(List.of(new BlockPos(0, 73, 0)), NONE_ACTIVE, 4, 72));
	}

	@Test
	void equalDistancesAreSplitByPositionSoTheOrderOfTheInputDoesNotMatter() {
		List<BlockPos> radios = new ArrayList<>(List.of(new BlockPos(5, 0, 0), new BlockPos(-4, 0, 0), new BlockPos(0, 0, 5),
			new BlockPos(0, 0, -4), new BlockPos(0, 5, 0)));
		List<BlockPos> first = nearest(radios, NONE_ACTIVE, 3, 72);
		for (int seed = 0; seed < 20; seed++) {
			Collections.shuffle(radios, new Random(seed));
			assertEquals(first, nearest(radios, NONE_ACTIVE, 3, 72));
		}
	}

	@Test
	void aZeroOrNegativeLimitSelectsNothing() {
		assertTrue(nearest(row(1, 2), NONE_ACTIVE, 0, 72).isEmpty());
		assertTrue(nearest(row(1, 2), NONE_ACTIVE, -3, 72).isEmpty());
	}

	@Test
	void aRadioThatIsAlreadyPlayingKeepsItsPlaceAgainstAnAlmostAsNearNewcomer() {
		List<BlockPos> radios = row(10, 11, 12, 13, 14);
		BlockPos playing = new BlockPos(14, 0, 0);
		// Without the head start the 14 would lose to the four nearer ones; 14 - 2 = 12 beats the 13.
		assertEquals(row(10, 11, 12, 14), nearest(radios, Set.of(playing)::contains, 4, 72));
		assertEquals(row(10, 11, 12, 13), nearest(radios, NONE_ACTIVE, 4, 72));
	}

	@Test
	void theHeadStartIsSmallSoAFarRadioDoesNotStayForEver() {
		List<BlockPos> radios = row(10, 11, 12, 13, 30);
		BlockPos playing = new BlockPos(30, 0, 0);
		assertEquals(row(10, 11, 12, 13), nearest(radios, Set.of(playing)::contains, 4, 72));
	}

	@Test
	void theHeadStartDoesNotExtendTheRange() {
		BlockPos playing = new BlockPos(73, 0, 0);
		assertTrue(nearest(List.of(playing), Set.of(playing)::contains, 4, 72).isEmpty());
	}
}
