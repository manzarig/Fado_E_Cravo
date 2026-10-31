package com.manzarig.fadoecravo.net;

import com.manzarig.fadoecravo.FadoECravo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: tune the radio at {@code pos} to {@code frequencyKhz}. The server checks that
 * the player is close to the radio and that the frequency is inside the band before it obeys.
 */
public record SetFrequencyPayload(BlockPos pos, int frequencyKhz) implements CustomPacketPayload {
	public static final Type<SetFrequencyPayload> TYPE = new Type<>(FadoECravo.id("set_frequency"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SetFrequencyPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, SetFrequencyPayload::pos,
			ByteBufCodecs.VAR_INT, SetFrequencyPayload::frequencyKhz,
			SetFrequencyPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
