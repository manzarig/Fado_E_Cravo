package com.manzarig.fadoecravo.client.debug;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.manzarig.fadoecravo.client.audio.RadioSoundInstance;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Development-only client command for checking by ear that a radio can start a clip partway in.
 * {@code /fadoecravo_seektest <seconds>} plays {@code test/seek_test.ogg} from that offset, and
 * {@code /fadoecravo_seektest stop} fades it out. The clip is a 60 s tone that rises 100 Hz every
 * 5 s, from 300 Hz, so the pitch tells where playback started.
 */
public final class SeekTestCommand {
	private static final ResourceLocation SOUND_ID = ResourceLocation.fromNamespaceAndPath("fadoecravo", "test.seek_test");
	private static RadioSoundInstance current;

	private SeekTestCommand() {
	}

	/** Registers the command, but only in a development environment. */
	public static void register() {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return;
		}
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(literal("fadoecravo_seektest")
				.then(literal("stop").executes(context -> stop(context.getSource())))
				.then(argument("seconds", DoubleArgumentType.doubleArg(0.0, 60.0))
					.executes(context -> play(context.getSource(), DoubleArgumentType.getDouble(context, "seconds"))))));
	}

	private static int play(FabricClientCommandSource source, double seconds) {
		LocalPlayer player = source.getPlayer();
		Minecraft client = source.getClient();
		stopCurrent();
		BlockPos pos = player.blockPosition().above().relative(player.getDirection(), 2);
		current = new RadioSoundInstance(SOUND_ID, pos, seconds, 1.0f);
		client.getSoundManager().play(current);
		source.sendFeedback(Component.literal("Seek test: " + SOUND_ID + " from " + seconds + " s at " + pos.toShortString()));
		return 1;
	}

	private static int stop(FabricClientCommandSource source) {
		stopCurrent();
		source.sendFeedback(Component.literal("Seek test stopped"));
		return 1;
	}

	private static void stopCurrent() {
		if (current != null) {
			current.fadeOutAndStop();
			current = null;
		}
	}
}
