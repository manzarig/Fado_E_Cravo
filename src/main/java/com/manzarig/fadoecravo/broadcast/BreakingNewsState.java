package com.manzarig.fadoecravo.broadcast;

import com.manzarig.fadoecravo.net.BreakingNewsPayload;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The breaking news bulletin that is on the air, if any, saved with the overworld so that it
 * survives a restart and can be sent to players who join later.
 *
 * <p>There is at most one bulletin: a new one replaces the old. The state is kept as the payload
 * that is sent to the clients, which is also how "no bulletin" is represented
 * ({@link BreakingNewsPayload#CLEARED}). Clients end a bulletin by themselves when its clip is over,
 * so the server keeps it until it is replaced or cleared by a command.
 */
public class BreakingNewsState extends SavedData {
	/** File name under {@code data/} in the overworld folder, without the extension. */
	public static final String DATA_NAME = "fadoecravo_breaking_news";

	private static final String STATION_KEY = "station";
	private static final String START_KEY = "startGameTime";
	private static final String SEED_KEY = "seed";

	// DataFixTypes cannot be null: reading an existing file passes the tag through it.
	private static final SavedData.Factory<BreakingNewsState> FACTORY =
			new SavedData.Factory<>(BreakingNewsState::new, BreakingNewsState::load, DataFixTypes.LEVEL);

	private BreakingNewsPayload current = BreakingNewsPayload.CLEARED;

	/** The state of the server's overworld, created if the world has none yet. */
	public static BreakingNewsState get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	/** The bulletin on the air, or {@link BreakingNewsPayload#CLEARED}. */
	public BreakingNewsPayload current() {
		return current;
	}

	/** Puts a bulletin on the air in place of any other one. */
	public BreakingNewsPayload start(String stationId, long startGameTime, long seed) {
		current = new BreakingNewsPayload(stationId, startGameTime, seed);
		setDirty();
		return current;
	}

	/** Takes the bulletin off the air. */
	public void clear() {
		current = BreakingNewsPayload.CLEARED;
		setDirty();
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		if (!current.isCleared()) {
			tag.putString(STATION_KEY, current.stationId());
			tag.putLong(START_KEY, current.startGameTime());
			tag.putLong(SEED_KEY, current.seed());
		}
		return tag;
	}

	/** Reads what {@link #save} wrote; a tag without a station is "no bulletin". */
	public static BreakingNewsState load(CompoundTag tag, HolderLookup.Provider registries) {
		BreakingNewsState state = new BreakingNewsState();
		String station = tag.getString(STATION_KEY);
		if (!station.isEmpty()) {
			state.current = new BreakingNewsPayload(station, tag.getLong(START_KEY), tag.getLong(SEED_KEY));
		}
		return state;
	}
}
