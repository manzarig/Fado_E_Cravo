package com.manzarig.fadoecravo.client.render;

import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;

/** How bright the valves and the dial glow while a radio warms up, as plain arithmetic a test can check. */
public final class WarmGlow {
	private WarmGlow() {
	}

	/**
	 * Brightness from 0 to 1. Warm (no ticks left): 1. Warming up: rises with the square of the time
	 * since the switch, the way a filament reddens, with a small flicker while it is still dim.
	 *
	 * @param warmupTicksLeft ticks of warm-up to go, {@link RadioBlockEntity#WARMUP_TICKS} right after the switch
	 * @param time            a running time in ticks, for the flicker
	 */
	public static float brightness(int warmupTicksLeft, double time) {
		if (warmupTicksLeft <= 0) {
			return 1.0f;
		}
		float progress = 1.0f - Math.min(warmupTicksLeft, RadioBlockEntity.WARMUP_TICKS) / (float) RadioBlockEntity.WARMUP_TICKS;
		float flicker = (float) (0.12 * (1.0f - progress) * Math.sin(time * 4.1) * Math.sin(time * 1.7));
		return Math.max(0.0f, Math.min(1.0f, progress * progress + flicker));
	}
}
