package com.manzarig.fadoecravo.client.broadcast;

import com.manzarig.fadoecravo.net.BreakingNewsPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The breaking-news bulletin the server last told this client about.
 *
 * <p>The server sends a {@link BreakingNewsPayload} when a bulletin starts, when one is cleared and
 * when a player joins during one. This class keeps the latest of them and nothing else: it does not
 * know whether the bulletin is still on the air, because that depends on the clip's length and the
 * station a radio is tuned to. {@code RadioAudioManager} reads {@link #current()} every tick and
 * asks {@code BroadcastSchedule.breaking} whether the bulletin is still running.
 *
 * <p>Everything here runs on the client main thread: Fabric calls a payload receiver there.
 */
public final class ClientBreakingNews {
	private static BreakingNewsPayload current = BreakingNewsPayload.CLEARED;

	private ClientBreakingNews() {
	}

	/**
	 * Registers the receiver for the payload and forgets the bulletin when the client leaves the
	 * server. Call once; the payload type itself is registered by {@code ModNetworking.init()}, which
	 * runs first because the main initializer runs before the client one.
	 */
	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(BreakingNewsPayload.TYPE, (payload, context) -> set(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
	}

	/** The last bulletin received; {@link BreakingNewsPayload#isCleared()} when there is none. */
	public static BreakingNewsPayload current() {
		return current;
	}

	/** Replaces the bulletin. A cleared payload ends it. */
	static void set(BreakingNewsPayload payload) {
		current = payload;
	}

	/** Forgets the bulletin. */
	public static void clear() {
		current = BreakingNewsPayload.CLEARED;
	}
}
