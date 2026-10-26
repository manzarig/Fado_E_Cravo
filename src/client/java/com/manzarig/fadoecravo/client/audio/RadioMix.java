package com.manzarig.fadoecravo.client.audio;

/**
 * The volumes a radio is faded to: how loud the programme, the static and the carrier hiss are. The
 * radio sets them as targets and each sound glides there by itself, so a change of reception is a
 * smooth swell of the programme over the static, not a jump.
 *
 * <p>On the air the programme is as loud as the signal {@code s} and the static fills the rest,
 * {@code (1 - s) * 0.6}. A weak station (signal strength {@code k} below 1) adds a floor to that,
 * {@code (1 - k) * 0.35}, so the static never thins out the way it does for a strong one and the
 * station sounds as faint as it is; the sum is capped at the 0.6 of an empty dial. (The floor is
 * added, not taken as a {@code max}: {@code s <= k}, so {@code (1 - s) * 0.6} is never below
 * {@code (1 - k) * 0.6}, and a {@code max} with the smaller {@code (1 - k) * 0.35} would never
 * change anything.) A station that is tuned in but silent, or no station at all, gives full static
 * and the faint hiss of an empty carrier. Every value stays within {@code [0, 1]}, because the range
 * of a sound comes from sounds.json and only a volume above 1 would stretch it.
 *
 * <p>Radios that play the same station near the listener add up, so each is turned down by
 * {@link #crowdGain}.
 *
 * <p>A loop whose target is (nearly) 0 is not kept running, only to be silent: the sound engine
 * announces every sound it plays to the subtitles, whatever its volume. {@link #loopWanted} says
 * when a loop is started and when it is let go.
 *
 * @param programme  volume of the station's clip
 * @param staticLoop volume of {@code sfx.static_loop}
 * @param carrier    volume of {@code sfx.carrier_hiss}; 0 means the hiss is not wanted
 */
record RadioMix(float programme, float staticLoop, float carrier) {
	/** Volume of the static when nothing is received. */
	static final float STATIC_MAX = 0.6f;
	/** Volume of the carrier hiss when a station has nothing on the air, or none is tuned. */
	static final float CARRIER_LEVEL = 0.5f;
	/** Static added for each unit a station's signal strength is below 1, whatever the dial position. */
	static final float WEAK_STATIC_FLOOR = 0.35f;
	/** A loop that is not playing is started when its target is above this. */
	static final float LOOP_START_VOLUME = 0.02f;
	/** A loop that is playing is faded out and stopped when its target is at or below this; the gap to the start volume stops it flapping. */
	static final float LOOP_STOP_VOLUME = 0.01f;

	/**
	 * The mix for a programme received at {@code signal}; values outside {@code [0, 1]} are clamped.
	 *
	 * @param signal   reception strength, the station's {@code signalStrength} times the fall-off of the dial
	 * @param strength the station's {@code signalStrength}, 1 for a station that comes in clearly
	 */
	static RadioMix onAir(double signal, double strength) {
		float s = clamp01(signal);
		float k = clamp01(strength);
		float noise = Math.min(STATIC_MAX, (1.0f - s) * STATIC_MAX + (1.0f - k) * WEAK_STATIC_FLOOR);
		return new RadioMix(s, noise, 0.0f);
	}

	/** The mix when nothing is on the air: no programme, full static and the carrier hiss. */
	static RadioMix offAir() {
		return new RadioMix(0.0f, STATIC_MAX, CARRIER_LEVEL);
	}

	/** Exponent of {@link #crowdGain}: between 0.5 (equal power) and 1 (equal amplitude). */
	static final double CROWD_EXPONENT = 0.75;

	/**
	 * How much quieter each radio of a group is played when {@code crowd} radios on the same station
	 * are near the listener: {@code crowd^-0.75}. Radios a few ticks apart add up roughly as power,
	 * which {@code 1/sqrt(crowd)} would balance, but radios switched on in the same tick play the
	 * same samples and add as amplitude, which {@code 1/crowd} would balance. With {@code 1/sqrt}
	 * four such radios still peaked at 0 dBFS in the critic's capture; the 0.75 exponent leaves about
	 * 3 dB of headroom there while a group of incoherent radios is only slightly quieter than one.
	 * 1 for a radio on its own, or for a count below 1.
	 */
	static float crowdGain(int crowd) {
		return crowd <= 1 ? 1.0f : (float) Math.pow(crowd, -CROWD_EXPONENT);
	}

	/** This mix with all three volumes multiplied by {@link #crowdGain} of {@code crowd}; they never rise. */
	RadioMix inCrowd(int crowd) {
		float gain = crowdGain(crowd);
		return new RadioMix(programme * gain, staticLoop * gain, carrier * gain);
	}

	/**
	 * Whether a loop with the target {@code volume} should be playing.
	 *
	 * @param running whether it is playing now (and not on its way out)
	 */
	static boolean loopWanted(float volume, boolean running) {
		return volume > (running ? LOOP_STOP_VOLUME : LOOP_START_VOLUME);
	}

	private static float clamp01(double value) {
		return (float) Math.max(0.0, Math.min(1.0, value));
	}
}
