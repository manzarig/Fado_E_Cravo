package com.manzarig.fadoecravo.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

/** The ids and the wire format of the payloads, which client and server must agree on. */
class PayloadsTest {
	/** Writes the value and reads it back, as a packet does. None of the payloads uses a registry. */
	private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
		codec.encode(buffer, value);
		T decoded = codec.decode(buffer);
		assertEquals(0, buffer.readableBytes(), "the codec left bytes unread");
		return decoded;
	}

	@Test
	void payloadIdsAreTheOnesOfTheContract() {
		assertEquals("fadoecravo:set_frequency", SetFrequencyPayload.TYPE.id().toString());
		assertEquals("fadoecravo:toggle_power", TogglePowerPayload.TYPE.id().toString());
		assertEquals("fadoecravo:breaking_news", BreakingNewsPayload.TYPE.id().toString());
	}

	@Test
	void eachPayloadReportsItsOwnType() {
		assertEquals(SetFrequencyPayload.TYPE, new SetFrequencyPayload(BlockPos.ZERO, 1035).type());
		assertEquals(TogglePowerPayload.TYPE, new TogglePowerPayload(BlockPos.ZERO).type());
		assertEquals(BreakingNewsPayload.TYPE, BreakingNewsPayload.CLEARED.type());
	}

	@Test
	void setFrequencyRoundTrips() {
		SetFrequencyPayload payload = new SetFrequencyPayload(new BlockPos(-123, 64, 4567), 1440);
		assertEquals(payload, roundTrip(SetFrequencyPayload.CODEC, payload));
	}

	@Test
	void togglePowerRoundTrips() {
		TogglePowerPayload payload = new TogglePowerPayload(new BlockPos(10, -60, -20));
		assertEquals(payload, roundTrip(TogglePowerPayload.CODEC, payload));
	}

	@Test
	void breakingNewsRoundTripsWithANegativeSeed() {
		BreakingNewsPayload payload = new BreakingNewsPayload("emissora_lusitana", 987_654_321_012L, Long.MIN_VALUE + 17);
		assertEquals(payload, roundTrip(BreakingNewsPayload.CODEC, payload));
	}

	@Test
	void anEmptyStationIdMeansCleared() {
		BreakingNewsPayload decoded = roundTrip(BreakingNewsPayload.CODEC, BreakingNewsPayload.CLEARED);
		assertTrue(decoded.isCleared());
		assertFalse(new BreakingNewsPayload("radio_tejo", 1L, 2L).isCleared());
	}

	@Test
	void reachIsEightBlocks() {
		assertTrue(ModNetworking.isInReach(0.0));
		assertTrue(ModNetworking.isInReach(64.0));
		assertFalse(ModNetworking.isInReach(64.01));
	}
}
