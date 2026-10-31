package com.manzarig.fadoecravo.client.gui;

import java.util.OptionalInt;

/**
 * Keeps the frequency a radio screen shows in step with the server without letting the needle jump.
 *
 * <p>The player changes the frequency locally, which shows at once. The new value is sent to the
 * server {@link #SEND_DELAY_MS} after the last change, and the server answers by updating the block
 * entity, which the screen reads again every tick. That answer can lag behind or still carry an
 * older value, so while a change is unsent, and after it was sent until the server has repeated it
 * (or {@link #HOLD_MS} have passed), the local value wins. After that the server's value is shown
 * again, which is how a change by another player or a rejected packet shows up.
 *
 * <p>The class only decides; it neither sends nor reads the clock. The caller passes the time in
 * milliseconds and sends what {@link #pollSend} or {@link #flush} return.
 */
public final class FrequencySync {
	/** How long after the last change the new frequency is sent. */
	public static final long SEND_DELAY_MS = 250L;
	/** How long after sending the local value is kept if the server has not repeated it. */
	public static final long HOLD_MS = 1000L;

	private final int minKhz;
	private final int maxKhz;
	/** The frequency shown and used by the screen. */
	private int shown;
	/** The last frequency the server reported. */
	private int server;
	private boolean dirty;
	private long changedAtMs;
	private boolean awaitingEcho;
	private int sentKhz;
	private long sentAtMs;

	public FrequencySync(int initialKhz, int minKhz, int maxKhz) {
		if (maxKhz < minKhz) {
			throw new IllegalArgumentException("maxKhz must not be below minKhz: " + minKhz + ".." + maxKhz);
		}
		this.minKhz = minKhz;
		this.maxKhz = maxKhz;
		this.shown = clamp(initialKhz);
		this.server = this.shown;
	}

	/** The frequency to show. */
	public int shown() {
		return shown;
	}

	/** Whether a local change has not been handed out by {@link #pollSend} or {@link #flush} yet. */
	public boolean hasUnsentChange() {
		return dirty;
	}

	/** Whether the last value sent has not yet been repeated by the server. */
	public boolean isAwaitingEcho() {
		return awaitingEcho;
	}

	/**
	 * A local change. The value is brought into the band.
	 *
	 * @return whether the shown frequency changed
	 */
	public boolean set(int khz, long nowMs) {
		int clamped = clamp(khz);
		if (clamped == shown) {
			return false;
		}
		shown = clamped;
		dirty = true;
		changedAtMs = nowMs;
		return true;
	}

	/** A local change by {@code deltaKhz} from the frequency shown; see {@link #set}. */
	public boolean nudge(int deltaKhz, long nowMs) {
		return set(shown + deltaKhz, nowMs);
	}

	/**
	 * The frequency to send now, if there is an unsent change that has been left alone for
	 * {@link #SEND_DELAY_MS}. Returning it counts it as sent.
	 */
	public OptionalInt pollSend(long nowMs) {
		if (!dirty || nowMs - changedAtMs < SEND_DELAY_MS) {
			return OptionalInt.empty();
		}
		return take(nowMs);
	}

	/** The frequency to send right away, if there is an unsent change, regardless of the delay. */
	public OptionalInt flush(long nowMs) {
		return dirty ? take(nowMs) : OptionalInt.empty();
	}

	/**
	 * Forgets an unsent change and goes back to the server's frequency. Used when the radio is gone
	 * or out of reach, where sending would be pointless.
	 */
	public void discardPending() {
		dirty = false;
		awaitingEcho = false;
		shown = server;
	}

	/** The frequency the server reports for the radio; call it every tick. */
	public void onServerValue(int khz, long nowMs) {
		server = clamp(khz);
		if (dirty) {
			return;
		}
		if (awaitingEcho) {
			if (server != sentKhz && nowMs - sentAtMs < HOLD_MS) {
				return;
			}
			awaitingEcho = false;
		}
		shown = server;
	}

	private OptionalInt take(long nowMs) {
		dirty = false;
		if (!awaitingEcho && shown == server) {
			// The player ended up where the server already is, so there is nothing to say.
			return OptionalInt.empty();
		}
		awaitingEcho = true;
		sentKhz = shown;
		sentAtMs = nowMs;
		return OptionalInt.of(shown);
	}

	private int clamp(int khz) {
		return Math.max(minKhz, Math.min(maxKhz, khz));
	}
}
