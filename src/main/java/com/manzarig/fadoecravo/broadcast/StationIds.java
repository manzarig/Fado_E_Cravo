package com.manzarig.fadoecravo.broadcast;

import com.manzarig.fadoecravo.FadoECravo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The ids of the stations that come with the mod, for the server to check a command against and to
 * suggest.
 *
 * <p>A station id is the name of a file {@code assets/fadoecravo/stations/<id>.json} in the mod's
 * own jar. The server reads the file names without loading the files, since the station model is a
 * matter for the client, and a dedicated server has the assets in the jar like any client does.
 */
public final class StationIds {
	private static final String STATIONS_DIRECTORY = "assets/" + FadoECravo.MOD_ID + "/stations";
	private static final String EXTENSION = ".json";

	private static volatile List<String> builtIn;

	private StationIds() {
	}

	/**
	 * The built-in station ids in alphabetical order. The jar is read once; an empty result is not
	 * kept, so that a failed read is tried again by the next command rather than remembered.
	 */
	public static List<String> builtIn() {
		List<String> ids = builtIn;
		if (ids == null) {
			ids = fromFileNames(listStationFiles());
			if (!ids.isEmpty()) {
				builtIn = ids;
			}
		}
		return ids;
	}

	/** Whether {@code id} is the id of a built-in station. */
	public static boolean isBuiltIn(String id) {
		return builtIn().contains(id);
	}

	/**
	 * Turns the names of the files in the stations directory into ids: those that end in
	 * {@code .json}, without the extension, sorted, without duplicates. Other files are ignored.
	 */
	public static List<String> fromFileNames(Collection<String> fileNames) {
		return fileNames.stream()
				.filter(name -> name.endsWith(EXTENSION) && name.length() > EXTENSION.length())
				.map(name -> name.substring(0, name.length() - EXTENSION.length()))
				.distinct()
				.sorted()
				.toList();
	}

	private static List<String> listStationFiles() {
		List<String> names = new ArrayList<>();
		Optional<Path> directory = FabricLoader.getInstance().getModContainer(FadoECravo.MOD_ID)
				.flatMap(container -> container.findPath(STATIONS_DIRECTORY));
		if (directory.isEmpty()) {
			FadoECravo.LOGGER.warn("The mod has no {} directory, so it knows no stations", STATIONS_DIRECTORY);
			return names;
		}
		try (Stream<Path> files = Files.list(directory.get())) {
			files.forEach(file -> names.add(file.getFileName().toString()));
		} catch (IOException e) {
			FadoECravo.LOGGER.error("Could not list the stations in {}", directory.get(), e);
		}
		return names;
	}
}
