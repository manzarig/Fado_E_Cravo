package com.manzarig.fadoecravo.net;

import com.manzarig.fadoecravo.block.RadioBlock;
import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;
import com.manzarig.fadoecravo.broadcast.BreakingNewsState;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Registers the mod's payloads and the server side of them.
 *
 * <p>Fabric runs a receiver on the server thread, so the handlers below read and change the world
 * directly. A client is not trusted: every request is checked against the player's reach, the loaded
 * chunks and the block that is actually at the position before it does anything.
 */
public final class ModNetworking {
	/** How far a player can be from a radio and still use it: 8 blocks, as a squared distance. */
	public static final double MAX_REACH_SQR = 64.0;

	private ModNetworking() {
	}

	public static void init() {
		PayloadTypeRegistry.playC2S().register(SetFrequencyPayload.TYPE, SetFrequencyPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(TogglePowerPayload.TYPE, TogglePowerPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(BreakingNewsPayload.TYPE, BreakingNewsPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(SetFrequencyPayload.TYPE,
				(payload, context) -> onSetFrequency(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(TogglePowerPayload.TYPE,
				(payload, context) -> onTogglePower(context.player(), payload));

		// A player who joins while a bulletin is on the air hears it too.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			BreakingNewsPayload current = BreakingNewsState.get(server).current();
			if (!current.isCleared()) {
				send(handler.getPlayer(), current);
			}
		});
	}

	/** Sends the bulletin, or {@link BreakingNewsPayload#CLEARED}, to every player on the server. */
	public static void broadcast(MinecraftServer server, BreakingNewsPayload payload) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			send(player, payload);
		}
	}

	/** Skips a client that does not have the mod, which would not know the payload. */
	private static void send(ServerPlayer player, BreakingNewsPayload payload) {
		if (ServerPlayNetworking.canSend(player, BreakingNewsPayload.TYPE)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private static void onSetFrequency(ServerPlayer player, SetFrequencyPayload payload) {
		if (!RadioBlockEntity.isValidKhz(payload.frequencyKhz())) {
			return;
		}
		reachableRadio(player, payload.pos()).ifPresent(radio -> radio.setFrequencyKhz(payload.frequencyKhz()));
	}

	private static void onTogglePower(ServerPlayer player, TogglePowerPayload payload) {
		reachableRadio(player, payload.pos()).ifPresent(radio -> {
			Level level = player.level();
			BlockState state = level.getBlockState(payload.pos());
			RadioBlock.togglePower(level, payload.pos(), state);
		});
	}

	/** Whether a player this far (squared) from the middle of a block may use it. */
	public static boolean isInReach(double distanceSqr) {
		return distanceSqr <= MAX_REACH_SQR;
	}

	/**
	 * The radio at {@code pos} if the player is close enough to it, in a loaded chunk, and the block
	 * there really is a radio. Cheapest checks first, because the position comes from the client.
	 */
	private static Optional<RadioBlockEntity> reachableRadio(ServerPlayer player, BlockPos pos) {
		double distanceSqr = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		if (!isInReach(distanceSqr)) {
			return Optional.empty();
		}
		Level level = player.level();
		if (!level.isLoaded(pos)) {
			return Optional.empty();
		}
		if (level.getBlockEntity(pos) instanceof RadioBlockEntity radio) {
			return Optional.of(radio);
		}
		return Optional.empty();
	}
}
