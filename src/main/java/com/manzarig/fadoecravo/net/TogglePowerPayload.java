package com.manzarig.fadoecravo.net;

import com.manzarig.fadoecravo.FadoECravo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: switch the radio at {@code pos} on or off, exactly as sneaking and using it
 * does. The server checks that the player is close to the radio.
 */
public record TogglePowerPayload(BlockPos pos) implements CustomPacketPayload {
	public static final Type<TogglePowerPayload> TYPE = new Type<>(FadoECravo.id("toggle_power"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TogglePowerPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, TogglePowerPayload::pos,
			TogglePowerPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
