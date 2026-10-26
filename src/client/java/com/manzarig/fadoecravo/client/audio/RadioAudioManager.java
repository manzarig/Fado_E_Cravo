package com.manzarig.fadoecravo.client.audio;

import com.manzarig.fadoecravo.block.RadioBlock;
import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;
import com.manzarig.fadoecravo.broadcast.ClipInfo;
import com.manzarig.fadoecravo.broadcast.StationDefinition;
import com.manzarig.fadoecravo.broadcast.Tuning;
import com.manzarig.fadoecravo.client.broadcast.ClientBreakingNews;
import com.manzarig.fadoecravo.client.station.StationRegistry;
import com.manzarig.fadoecravo.registry.ModSounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Plays the sound of every switched-on radio near the player: the station's programme, the static
 * between stations and the hiss of a carrier with nothing on it.
 *
 * <p><b>How the sound stays in sync.</b> There is no server-side playback and no message from the
 * server for the programme. A station's file says what it plays in each part of the day, and
 * {@code BroadcastSchedule} turns the world's day time into "this clip, this many seconds in". Every
 * client does that calculation on the same world time, so everyone hears the same programme, and a
 * radio switched on or walked up to is simply started part-way through the clip, with
 * {@link RadioSoundInstance}. Breaking news is the one thing the server announces
 * ({@link ClientBreakingNews}); it also has a calculated position, from the game time it began at.
 * {@link RadioChannel} holds the details of following the schedule.
 *
 * <p><b>Which radios.</b> Radios are tracked as their block entities load and unload. Each tick the
 * manager takes those within {@value RadioSelection#RANGE_BLOCKS} blocks of the camera whose block
 * state has {@code ON}, keeps the {@value RadioSelection#MAX_RADIOS} nearest and hands each a
 * {@link RadioChannel}. A radio that stops qualifying (switched off, too far, unloaded, another
 * level) fades out over half a second; leaving the world stops everything at once. Nothing is
 * played or advanced while the game is paused in a single-player world.
 *
 * <p><b>Several radios.</b> Radios on the same station are in step to within a fraction of a second
 * and add up to a louder programme, 6 dB for four. Each tick the manager counts, for every radio
 * played, the radios on its station within {@value RadioCrowd#RADIUS_BLOCKS} blocks of the
 * listener ({@link RadioCrowd}) and the channel turns its volumes down by the square root of that
 * count ({@link RadioMix#crowdGain}). A radio on its own, or far from the others, is not quieted.
 *
 * <p>Everything runs on the client main thread. Wire it up with one call to {@link #register()} from
 * the client initializer.
 */
public final class RadioAudioManager {
	private static final double RANGE_SQR = RadioSelection.RANGE_BLOCKS * RadioSelection.RANGE_BLOCKS;

	/** Every radio block entity the client has loaded, by position. */
	private static final Map<BlockPos, RadioBlockEntity> RADIOS = new HashMap<>();
	/** The channels of the radios being played, by position. */
	private static final Map<BlockPos, RadioChannel> CHANNELS = new LinkedHashMap<>();
	private static final DayClockWatch CLOCK = new DayClockWatch();
	/** The real sound engine, behind the interface the channels use. */
	private static final RadioChannel.Output ENGINE = new RadioChannel.Output() {
		@Override
		public void play(SoundInstance sound) {
			Minecraft.getInstance().getSoundManager().play(sound);
		}

		@Override
		public boolean isActive(SoundInstance sound) {
			return Minecraft.getInstance().getSoundManager().isActive(sound);
		}

		@Override
		public void stop(SoundInstance sound) {
			Minecraft.getInstance().getSoundManager().stop(sound);
		}
	};

	private static long tick;
	private static ClientLevel level;
	/** The radios played in the last tick, nearest first. */
	private static List<BlockPos> selected = List.of();

	private RadioAudioManager() {
	}

	/**
	 * Registers everything: the block entity load and unload events, the per-tick update, the
	 * breaking-news receiver ({@link ClientBreakingNews#register()}) and the clean-up on disconnect.
	 * Call once from the client initializer.
	 */
	public static void register() {
		ClientBreakingNews.register();
		ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, world) -> {
			if (blockEntity instanceof RadioBlockEntity radio) {
				RADIOS.put(radio.getBlockPos().immutable(), radio);
			}
		});
		ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, world) -> {
			if (blockEntity instanceof RadioBlockEntity radio) {
				onUnload(radio);
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(RadioAudioManager::onClientTick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stopEverything());
	}

	/**
	 * What each radio being played is doing, nearest first. Empty before the first tick or when no
	 * radio qualifies. Call on the client thread.
	 */
	public static List<RadioDebugState> snapshot() {
		List<RadioDebugState> states = new ArrayList<>();
		for (BlockPos pos : selected) {
			RadioChannel channel = CHANNELS.get(pos);
			if (channel != null) {
				states.add(channel.debugState());
			}
		}
		return List.copyOf(states);
	}

	private static void onUnload(RadioBlockEntity radio) {
		BlockPos pos = radio.getBlockPos();
		// A replacement block entity is loaded before the old one is unloaded; keep the newer one.
		if (RADIOS.remove(pos, radio)) {
			RadioChannel channel = CHANNELS.remove(pos);
			if (channel != null) {
				channel.release();
			}
		}
	}

	private static void onClientTick(Minecraft minecraft) {
		ClientLevel currentLevel = minecraft.level;
		if (currentLevel == null) {
			if (level != null || !CHANNELS.isEmpty() || !RADIOS.isEmpty()) {
				stopEverything(); // not every tick on the title screen
			}
			return;
		}
		if (minecraft.player == null || minecraft.isPaused()) {
			return;
		}
		if (currentLevel != level) {
			// Another dimension: the old level's radios are gone (Fabric unloads their block entities), so
			// let whatever is still sounding fade out and start from nothing.
			releaseChannels();
			CLOCK.reset();
			level = currentLevel;
		}
		tick++;
		long dayTime = currentLevel.getDayTime();
		CLOCK.observe(dayTime);

		Vec3 ear = listenerPosition(minecraft);
		List<BlockPos> candidates = new ArrayList<>();
		Iterator<RadioBlockEntity> radios = RADIOS.values().iterator();
		while (radios.hasNext()) {
			RadioBlockEntity radio = radios.next();
			if (radio.isRemoved() || radio.getLevel() != currentLevel) {
				radios.remove();
				continue;
			}
			BlockPos pos = radio.getBlockPos();
			if (distanceSqr(pos, ear) <= RANGE_SQR && isSwitchedOn(currentLevel, pos)) {
				candidates.add(pos);
			}
		}
		selected = RadioSelection.nearest(candidates, CHANNELS::containsKey, ear.x, ear.y, ear.z,
			RadioSelection.MAX_RADIOS, RadioSelection.RANGE_BLOCKS);

		CHANNELS.entrySet().removeIf(entry -> {
			if (selected.contains(entry.getKey())) {
				return false;
			}
			entry.getValue().release();
			return true;
		});

		Collection<StationDefinition> stations = StationRegistry.all();
		RadioChannel.Frame frame = new RadioChannel.Frame(tick, dayTime, currentLevel.getGameTime(), CLOCK.isFrozen(),
			stations, ClientBreakingNews.current());
		Map<BlockPos, Integer> crowds = crowdSizes(stations, ear);
		for (BlockPos pos : selected) {
			RadioChannel channel = CHANNELS.computeIfAbsent(pos,
				key -> new RadioChannel(key, ENGINE, ModSounds.STATIC_LOOP, ModSounds.CARRIER_HISS));
			channel.tick(frame, RADIOS.get(pos).getFrequencyKhz(), crowds.getOrDefault(pos, 1));
		}
	}

	/** For each radio played, how many on its station are near the listener, see {@link RadioCrowd}. */
	private static Map<BlockPos, Integer> crowdSizes(Collection<StationDefinition> stations, Vec3 ear) {
		List<RadioCrowd.Radio> heard = new ArrayList<>();
		for (BlockPos pos : selected) {
			int khz = RADIOS.get(pos).getFrequencyKhz();
			String stationId = Tuning.best(stations, khz).map(tuned -> tuned.station().id()).orElse(null);
			heard.add(new RadioCrowd.Radio(pos, stationId));
		}
		return RadioCrowd.sizes(heard, ear.x, ear.y, ear.z);
	}

	private static boolean isSwitchedOn(ClientLevel world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return state.hasProperty(RadioBlock.ON) && state.getValue(RadioBlock.ON);
	}

	private static double distanceSqr(BlockPos pos, Vec3 point) {
		double dx = pos.getX() + 0.5 - point.x;
		double dy = pos.getY() + 0.5 - point.y;
		double dz = pos.getZ() + 0.5 - point.z;
		return dx * dx + dy * dy + dz * dz;
	}

	/** Where the sound engine puts the listener: the camera, which is not the player in third person. */
	private static Vec3 listenerPosition(Minecraft minecraft) {
		Camera camera = minecraft.gameRenderer.getMainCamera();
		return camera.isInitialized() ? camera.getPosition() : minecraft.player.getEyePosition();
	}

	/** Fades every channel's sounds out and forgets the channels. */
	private static void releaseChannels() {
		for (RadioChannel channel : CHANNELS.values()) {
			channel.release();
		}
		CHANNELS.clear();
		selected = List.of();
	}

	/** Stops every channel's sounds on the spot and forgets the channels. */
	private static void stopChannels() {
		for (RadioChannel channel : CHANNELS.values()) {
			channel.stopNow();
		}
		CHANNELS.clear();
		selected = List.of();
	}

	/**
	 * Stops every sound of the mod's radios on the spot, including those that are fading out and so
	 * belong to no channel any more. The sound engine finds them by the id of their sound: the two
	 * loops and the clips of every station.
	 */
	private static void silenceAll() {
		SoundManager sounds = Minecraft.getInstance().getSoundManager();
		Set<ResourceLocation> ids = new HashSet<>();
		ids.add(ModSounds.STATIC_LOOP.getLocation());
		ids.add(ModSounds.CARRIER_HISS.getLocation());
		for (StationDefinition station : StationRegistry.all()) {
			for (ClipInfo clip : station.clips().values()) {
				ids.add(clip.sound());
			}
		}
		for (ResourceLocation id : ids) {
			sounds.stop(id, SoundSource.RECORDS);
		}
	}

	/** Leaving the world: stop all sound and forget the radios and the level. */
	private static void stopEverything() {
		stopChannels();
		silenceAll();
		RADIOS.clear();
		CLOCK.reset();
		level = null;
	}
}
