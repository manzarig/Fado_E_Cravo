package com.manzarig.fadoecravo.client.debug;

import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes {@code playback.log}: one JSON object per line, flushed after every line so that a
 * crash or a kill still leaves everything up to that moment on disk.
 */
final class CriticLog implements AutoCloseable {
	private final BufferedWriter writer;
	private boolean failed;

	CriticLog(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
	}

	/** Appends one line; a write error is reported once and then ignored, because logging must not stop the run. */
	synchronized void write(JsonObject line) {
		if (failed) {
			return;
		}
		try {
			writer.write(line.toString());
			writer.newLine();
			writer.flush();
		} catch (IOException e) {
			failed = true;
			System.err.println("[fadoecravo critic] cannot write playback.log: " + e);
		}
	}

	@Override
	public synchronized void close() {
		try {
			writer.close();
		} catch (IOException e) {
			// Nothing more to do.
		}
	}
}
