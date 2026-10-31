package com.manzarig.fadoecravo.command;

import com.manzarig.fadoecravo.FadoECravo;
import com.manzarig.fadoecravo.broadcast.BreakingNewsState;
import com.manzarig.fadoecravo.broadcast.StationIds;
import com.manzarig.fadoecravo.net.BreakingNewsPayload;
import com.manzarig.fadoecravo.net.ModNetworking;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /fadoecravo breaking <station>} puts a breaking news bulletin on the air on a station and
 * {@code /fadoecravo breaking clear} takes it off. Both need permission level 2.
 *
 * <p>The command only records the bulletin and tells the clients; it is the client that interrupts
 * the programme of the radios tuned to the station.
 */
public final class RadioCommand {
	private static final String STATION = "station";

	private static final DynamicCommandExceptionType UNKNOWN_STATION = new DynamicCommandExceptionType(
			id -> Component.translatable("command.fadoecravo.breaking.unknown_station", id));

	private RadioCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(build()));
	}

	/** The command tree, separate from the registration so it can be built without the callback. */
	static LiteralArgumentBuilder<CommandSourceStack> build() {
		return Commands.literal("fadoecravo")
				.requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("breaking")
						.then(Commands.literal("clear")
								.executes(RadioCommand::clear))
						.then(Commands.argument(STATION, StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(StationIds.builtIn(), builder))
								.executes(RadioCommand::breaking)));
	}

	private static int breaking(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String station = StringArgumentType.getString(context, STATION);
		if (!StationIds.isBuiltIn(station)) {
			throw UNKNOWN_STATION.create(station);
		}
		CommandSourceStack source = context.getSource();
		MinecraftServer server = source.getServer();
		ServerLevel overworld = server.overworld();

		BreakingNewsPayload news = BreakingNewsState.get(server)
				.start(station, overworld.getGameTime(), overworld.getRandom().nextLong());
		ModNetworking.broadcast(server, news);

		source.sendSuccess(() -> Component.translatable("command.fadoecravo.breaking.success",
				Component.translatable("station." + FadoECravo.MOD_ID + "." + station)), true);
		return 1;
	}

	private static int clear(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		MinecraftServer server = source.getServer();

		BreakingNewsState.get(server).clear();
		ModNetworking.broadcast(server, BreakingNewsPayload.CLEARED);

		source.sendSuccess(() -> Component.translatable("command.fadoecravo.breaking.cleared"), true);
		return 1;
	}
}
