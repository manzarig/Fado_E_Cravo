package com.manzarig.fadoecravo.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.manzarig.fadoecravo.client.audio.RadioCrowd.Radio;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Who counts as company for a radio: same station, within 32 blocks of the listener. */
class RadioCrowdTest {
	private static final String TEJO = "radio_tejo";
	private static final String LUSITANA = "emissora_lusitana";

	private static Radio radio(int x, String station) {
		return new Radio(new BlockPos(x, 64, 0), station);
	}

	private static Map<BlockPos, Integer> sizes(List<Radio> radios) {
		return RadioCrowd.sizes(radios, 0.5, 64.5, 0.5);
	}

	private static int sizeOf(Map<BlockPos, Integer> sizes, Radio radio) {
		return sizes.get(radio.pos());
	}

	@Test
	void theRadiusIsThirtyTwoBlocks() {
		assertEquals(32.0, RadioCrowd.RADIUS_BLOCKS);
	}

	@Test
	void aRadioOnItsOwnIsACrowdOfOne() {
		Radio only = radio(3, TEJO);
		assertEquals(1, sizeOf(sizes(List.of(only)), only));
	}

	@Test
	void noRadiosNoSizes() {
		assertTrue(sizes(List.of()).isEmpty());
	}

	@Test
	void fourNearRadiosOnOneStationAreACrowdOfFour() {
		List<Radio> radios = List.of(radio(3, TEJO), radio(5, TEJO), radio(7, TEJO), radio(9, TEJO));
		Map<BlockPos, Integer> sizes = sizes(radios);
		for (Radio radio : radios) {
			assertEquals(4, sizeOf(sizes, radio));
		}
		assertEquals(RadioMix.crowdGain(4), RadioMix.crowdGain(sizeOf(sizes, radios.get(0))), 1e-6f);
	}

	@Test
	void radiosOnOtherStationsAreNotCompany() {
		Radio a = radio(3, TEJO);
		Radio b = radio(5, TEJO);
		Radio c = radio(7, LUSITANA);
		Map<BlockPos, Integer> sizes = sizes(List.of(a, b, c));
		assertEquals(2, sizeOf(sizes, a));
		assertEquals(2, sizeOf(sizes, b));
		assertEquals(1, sizeOf(sizes, c));
	}

	@Test
	void radiosTunedToNothingAreOneGroupBecauseTheyAllPlayStatic() {
		Radio a = radio(3, null);
		Radio b = radio(5, null);
		Radio c = radio(7, TEJO);
		Map<BlockPos, Integer> sizes = sizes(List.of(a, b, c));
		assertEquals(2, sizeOf(sizes, a));
		assertEquals(2, sizeOf(sizes, b));
		assertEquals(1, sizeOf(sizes, c));
	}

	@Test
	void aLoneDistantRadioIsNotQuieted() {
		Radio far = radio(50, TEJO);
		Map<BlockPos, Integer> sizes = sizes(List.of(far));
		assertEquals(1, sizeOf(sizes, far));
		assertEquals(1.0f, RadioMix.crowdGain(sizeOf(sizes, far)));
	}

	@Test
	void aDistantRadioDoesNotQuietTheNearOnes() {
		Radio near = radio(3, TEJO);
		Radio far = radio(50, TEJO);
		Map<BlockPos, Integer> sizes = sizes(List.of(near, far));
		assertEquals(1, sizeOf(sizes, near), "the far one is not counted");
		assertEquals(1, sizeOf(sizes, far), "and a lone far radio, with one near, is still not quieted by it");
	}

	@Test
	void everyRadioOfAStationSharesTheCountOfTheNearOnes() {
		Radio a = radio(3, TEJO);
		Radio b = radio(6, TEJO);
		Radio c = radio(9, TEJO);
		Radio far = radio(50, TEJO);
		Map<BlockPos, Integer> sizes = sizes(List.of(a, b, c, far));
		assertEquals(3, sizeOf(sizes, a));
		assertEquals(3, sizeOf(sizes, b));
		assertEquals(3, sizeOf(sizes, c));
		assertEquals(3, sizeOf(sizes, far), "the group stays even");
	}

	@Test
	void theRadiusIsMeasuredToTheMiddleOfTheBlockFromTheListener() {
		Radio edge = new Radio(new BlockPos(31, 64, 0), TEJO); // centre 31.5 from 0.5: exactly 31
		Radio over = new Radio(new BlockPos(32, 64, 0), TEJO); // exactly 32: still inside
		Radio out = new Radio(new BlockPos(33, 64, 0), TEJO); // 33: outside
		Map<BlockPos, Integer> sizes = sizes(List.of(edge, over, out));
		assertEquals(2, sizeOf(sizes, edge));
		assertEquals(2, sizeOf(sizes, over));
		assertEquals(2, sizeOf(sizes, out), "counted for the group, not as a member");
	}

	@Test
	void heightCountsLikeDistance() {
		Radio up = new Radio(new BlockPos(0, 64 + 33, 0), TEJO);
		Radio level = radio(2, TEJO);
		Map<BlockPos, Integer> sizes = sizes(List.of(up, level));
		assertEquals(1, sizeOf(sizes, level));
	}

	@Test
	void theListenerMovingChangesTheCrowd() {
		List<Radio> radios = List.of(radio(0, TEJO), radio(10, TEJO));
		assertEquals(2, RadioCrowd.sizes(radios, 5.0, 64.5, 0.5).get(radios.get(0).pos()));
		assertEquals(1, RadioCrowd.sizes(radios, 100.0, 64.5, 0.5).get(radios.get(0).pos()), "out of earshot of both");
	}
}
