package com.manzarig.fadoecravo.client.render;

import com.manzarig.fadoecravo.FadoECravo;
import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;
import com.manzarig.fadoecravo.client.audio.RadioAudioManager;
import com.manzarig.fadoecravo.client.gui.RadioScreen;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;

/**
 * The radio's GeckoLib model: {@code geo/block/radio.geo.json}, {@code textures/block/radio.png} and
 * {@code animations/block/radio.animation.json}, all written by {@code tools/art/make_radio_model.py}.
 *
 * <p>The animation file only turns the power knob. The rest moves from code each frame, because it
 * follows live values rather than a timeline:
 * <ul>
 *   <li>the needle slides to the frequency, the one the tuning screen shows while it is open, and
 *       glides there instead of jumping ({@link DialPosition});
 *   <li>the tuning knob turns with the needle;
 *   <li>the speaker cloth trembles with the programme, as strongly as the station comes in
 *       ({@link RadioAudioManager#speakerLevel}).
 * </ul>
 */
public class RadioGeoModel extends DefaultedBlockGeoModel<RadioBlockEntity> {
	/** How far the speaker cloth moves at a full signal, in model pixels. */
	private static final float SPEAKER_TRAVEL = 0.12f;

	public RadioGeoModel() {
		super(FadoECravo.id("radio"));
	}

	@Override
	public void setCustomAnimations(RadioBlockEntity radio, long instanceId, AnimationState<RadioBlockEntity> state) {
		int target = RadioScreen.liveFrequency(radio.getBlockPos()).orElse(radio.getFrequencyKhz());
		float shown = DialPosition.ease(radio.renderedKhz, target);
		radio.renderedKhz = shown;

		getBone("needle").ifPresent(bone -> bone.setPosX(DialPosition.needleOffset(shown)));
		getBone("knob_tune").ifPresent(bone -> bone.setRotZ(DialPosition.knobAngle(shown)));
		getBone("speaker").ifPresent(bone -> moveSpeaker(bone, radio, state.getAnimationTick()));
	}

	private static void moveSpeaker(GeoBone bone, RadioBlockEntity radio, double tick) {
		float level = RadioAudioManager.speakerLevel(radio.getBlockPos());
		if (level <= 0.0f) {
			bone.setPosZ(0.0f);
			return;
		}
		// Two unrelated wobbles make an irregular flutter, not a visible beat.
		double flutter = 0.5 + 0.25 * Math.sin(tick * 2.3) + 0.25 * Math.sin(tick * 3.7 + 1.3);
		bone.setPosZ((float) (-SPEAKER_TRAVEL * level * flutter));
	}
}
