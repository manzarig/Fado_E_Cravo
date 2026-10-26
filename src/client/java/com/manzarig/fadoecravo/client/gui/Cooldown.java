package com.manzarig.fadoecravo.client.gui;

/**
 * Lets something happen at most once every {@code periodMs} milliseconds. The caller passes the
 * time, so it can be tested without a clock.
 */
public final class Cooldown {
	private final long periodMs;
	private boolean fired;
	private long lastMs;

	public Cooldown(long periodMs) {
		if (periodMs < 0) {
			throw new IllegalArgumentException("periodMs must not be negative: " + periodMs);
		}
		this.periodMs = periodMs;
	}

	/**
	 * Asks to go ahead at {@code nowMs}. A yes starts a new period; a no does not extend the old one.
	 */
	public boolean tryAcquire(long nowMs) {
		if (fired && nowMs >= lastMs && nowMs - lastMs < periodMs) {
			return false;
		}
		fired = true;
		lastMs = nowMs;
		return true;
	}
}
