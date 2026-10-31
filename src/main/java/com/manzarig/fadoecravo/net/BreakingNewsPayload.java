package com.manzarig.fadoecravo.net;

import com.manzarig.fadoecravo.FadoECravo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: a breaking news bulletin is on the air on {@code stationId}, from the game time
 * {@code startGameTime}. {@code seed} lets every client choose the same clip from the station's
 * list. An empty station id means there is no bulletin, which is also how it is cleared.
 */
public record BreakingNewsPayload(String stationId, long startGameTime, long seed) implements CustomPacketPayload {
	public static final Type<BreakingNewsPayload> TYPE = new Type<>(FadoECravo.id("breaking_news"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BreakingNewsPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, BreakingNewsPayload::stationId,
			ByteBufCodecs.VAR_LONG, BreakingNewsPayload::startGameTime,
			ByteBufCodecs.VAR_LONG, BreakingNewsPayload::seed,
			BreakingNewsPayload::new);

	/** There is no bulletin. */
	public static final BreakingNewsPayload CLEARED = new BreakingNewsPayload("", 0L, 0L);

	public boolean isCleared() {
		return stationId.isEmpty();
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
