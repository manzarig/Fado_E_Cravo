package com.manzarig.fadoecravo.client.station;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.manzarig.fadoecravo.FadoECravo;
import com.manzarig.fadoecravo.broadcast.StationDefinition;
import java.io.Reader;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The stations the client knows about, loaded from {@code assets/<namespace>/stations/*.json} in every
 * resource pack (the mod's own built-in pack first). A later pack can replace a station by shipping a
 * file with the same path, or add new ones. Files that do not parse or validate are skipped and logged.
 *
 * <p>Reloaded with the client's resources (F3+T, pack changes). Read on the client main thread.
 */
public final class StationRegistry {
	private static final String FOLDER = "stations";
	private static volatile Map<String, StationDefinition> stations = Map.of();

	private StationRegistry() {
	}

	/** Registers the reload listener; call once from the client initializer. */
	public static void register() {
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public ResourceLocation getFabricId() {
				return FadoECravo.id("stations");
			}

			@Override
			public void onResourceManagerReload(ResourceManager manager) {
				load(manager);
			}
		});
	}

	/** All loaded stations, ordered by frequency. */
	public static Collection<StationDefinition> all() {
		return stations.values();
	}

	public static Optional<StationDefinition> get(String id) {
		return Optional.ofNullable(stations.get(id));
	}

	private static void load(ResourceManager manager) {
		Map<String, StationDefinition> loaded = new LinkedHashMap<>();
		Map<ResourceLocation, Resource> files = manager.listResources(FOLDER, path -> path.getPath().endsWith(".json"));
		for (Map.Entry<ResourceLocation, Resource> entry : files.entrySet()) {
			ResourceLocation file = entry.getKey();
			try (Reader reader = entry.getValue().openAsReader()) {
				JsonElement json = JsonParser.parseReader(reader);
				StationDefinition.parse(json).resultOrPartial(error ->
						FadoECravo.LOGGER.error("Skipping station file {}: {}", file, error))
					.ifPresent(station -> loaded.put(station.id(), station));
			} catch (Exception e) {
				FadoECravo.LOGGER.error("Skipping station file {}: could not read it", file, e);
			}
		}
		Map<String, StationDefinition> sorted = new LinkedHashMap<>();
		loaded.values().stream()
			.sorted(Comparator.comparingInt(StationDefinition::frequencyKhz).thenComparing(StationDefinition::id))
			.forEach(station -> sorted.put(station.id(), station));
		stations = Collections.unmodifiableMap(sorted);
		FadoECravo.LOGGER.info("Loaded {} radio stations: {}", sorted.size(), sorted.keySet());
	}
}
