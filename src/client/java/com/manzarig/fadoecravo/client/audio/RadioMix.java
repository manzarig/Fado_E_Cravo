package com.manzarig.fadoecravo.client.audio;

/**
 * The volumes a radio is faded to: how loud the programme, the static and the carrier hiss are. The
 * radio sets them as targets and each sound glides there by itself, so a change of reception is a
 * smooth swell of the programme over the static, not a jump.
 *
 * <p>On the air the programme is as loud as the signal {@code s} and the static fills the rest,
 * {@code (1 - s) * 0.6}. A station that is tuned in but silent, or no station at all, gives full
 * static and the faint hiss of an empty carrier. Every value stays within {@code [0, 1]}, because
 * the range of a sound comes from sounds.json and only a volume above 1 would stretch it.
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

	/** The mix for a programme received at {@code signal}; values outside {@code [0, 1]} are clamped. */
	static RadioMix onAir(double signal) {
		float s = (float) Math.max(0.0, Math.min(1.0, signal));
		return new RadioMix(s, (1.0f - s) * STATIC_MAX, 0.0f);
	}

	/** The mix when nothing is on the air: no programme, full static and the carrier hiss. */
	static RadioMix offAir() {
		return new RadioMix(0.0f, STATIC_MAX, CARRIER_LEVEL);
	}
}
