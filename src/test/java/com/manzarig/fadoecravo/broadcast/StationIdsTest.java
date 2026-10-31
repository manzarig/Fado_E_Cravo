package com.manzarig.fadoecravo.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The reading of station ids from file names; the jar lookup itself needs the Fabric loader. */
class StationIdsTest {
	@Test
	void idsAreTheNamesOfTheJsonFilesWithoutTheExtension() {
		assertEquals(List.of("radio_tejo"), StationIds.fromFileNames(List.of("radio_tejo.json")));
	}

	@Test
	void idsAreSortedAlphabetically() {
		List<String> ids = StationIds.fromFileNames(List.of("radio_tejo.json", "emissora_lusitana.json", "radio_atlantico.json"));
		assertEquals(List.of("emissora_lusitana", "radio_atlantico", "radio_tejo"), ids);
	}

	@Test
	void otherFilesAndBareExtensionsAreIgnored() {
		List<String> ids = StationIds.fromFileNames(List.of("README.md", "radio_tejo.json.bak", ".json", "emissora_lusitana.json", "notes"));
		assertEquals(List.of("emissora_lusitana"), ids);
	}

	@Test
	void duplicatesCountOnce() {
		assertEquals(List.of("radio_tejo"), StationIds.fromFileNames(List.of("radio_tejo.json", "radio_tejo.json")));
	}

	@Test
	void noFilesMeansNoStations() {
		assertTrue(StationIds.fromFileNames(List.of()).isEmpty());
	}

	/** The files the audio pipeline generated must read as the three stations of the contract. */
	@Test
	void theStationFilesOnTheClasspathAreTheBuiltInStations() throws IOException, URISyntaxException {
		var directory = StationIdsTest.class.getResource("/assets/fadoecravo/stations");
		assertNotNull(directory, "assets/fadoecravo/stations is missing from the resources");
		try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
			List<String> ids = StationIds.fromFileNames(files.map(file -> file.getFileName().toString()).toList());
			assertTrue(ids.containsAll(List.of("emissora_lusitana", "radio_tejo", "radio_atlantico")), ids.toString());
		}
	}
}
