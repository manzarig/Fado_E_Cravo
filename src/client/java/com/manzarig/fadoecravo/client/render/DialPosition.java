package com.manzarig.fadoecravo.client.render;

import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;

/**
 * Where the needle and the tuning knob of the radio model are for a frequency, as plain arithmetic
 * that a test can check. It must agree with {@code tools/art/make_radio_model.py}, which draws the
 * scale: the needle is modelled at {@link #REST_KHZ} and travels from {@link #X_AT_MIN} (530 kHz, the
 * viewer's left) to {@link #X_AT_MAX} (1600 kHz) in block pixels.
 */
public final class DialPosition {
	/** Block-pixel x of the needle at the bottom of the band. */
	public static final float X_AT_MIN = 11.5f;
	/** Block-pixel x of the needle at the top of the band. */
	public static final float X_AT_MAX = 4.5f;
	/** The frequency at which the model has its needle. */
	public static final int REST_KHZ = 1035;
	/**
	 * GeckoLib models mirror x (Blockbench's Bedrock export), so a needle moved towards higher block x
	 * is a negative bone offset.
	 */
	static final float MODEL_X_SIGN = -1.0f;
	/** Turns of the tuning knob from one end of the band to the other. */
	static final float KNOB_TURNS = 1.5f;
	/** Fraction of the way to the target the needle covers in one frame. */
	static final float EASE_PER_FRAME = 0.2f;
	/** Closer than this, in kHz, the needle simply arrives. */
	static final float SNAP_KHZ = 0.05f;

	private DialPosition() {
	}

	/** Block-pixel x of the needle at {@code khz}. */
	public static float needleX(float khz) {
		float t = (khz - RadioBlockEntity.MIN_KHZ) / (float) (RadioBlockEntity.MAX_KHZ - RadioBlockEntity.MIN_KHZ);
		t = Math.max(0.0f, Math.min(1.0f, t));
		return X_AT_MIN + (X_AT_MAX - X_AT_MIN) * t;
	}

	/** Offset of the needle bone from where it is modelled, in model pixels. */
	public static float needleOffset(float khz) {
		return MODEL_X_SIGN * (needleX(khz) - needleX(REST_KHZ));
	}

	/** Rotation of the tuning knob in radians: 0 at the bottom of the band, {@link #KNOB_TURNS} turns at the top. */
	public static float knobAngle(float khz) {
		float t = (khz - RadioBlockEntity.MIN_KHZ) / (float) (RadioBlockEntity.MAX_KHZ - RadioBlockEntity.MIN_KHZ);
		return (float) (-t * KNOB_TURNS * 2.0 * Math.PI);
	}

	/**
	 * The next drawn position of a needle at {@code shown} heading for {@code target}: a fifth of the
	 * way each frame, so a jump of the dial becomes a quick glide. {@code NaN} (never drawn) jumps.
	 */
	public static float ease(float shown, int target) {
		if (Float.isNaN(shown) || Math.abs(target - shown) < SNAP_KHZ) {
			return target;
		}
		return shown + (target - shown) * EASE_PER_FRAME;
	}
}
