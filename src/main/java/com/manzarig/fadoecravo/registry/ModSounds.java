package com.manzarig.fadoecravo.registry;

import com.manzarig.fadoecravo.FadoECravo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * The sound effects of the radio, one {@link SoundEvent} for each {@code sfx.*} entry in
 * {@code sounds.json}. The station and music clips are not registered: the client plays them by
 * id through {@code RadioSoundInstance}.
 */
public final class ModSounds {
	/** Click when the radio is switched on. */
	public static final SoundEvent POWER_ON = register("sfx.power_on");
	/** Click when the radio is switched off. */
	public static final SoundEvent POWER_OFF = register("sfx.power_off");
	/** Valves heating up after the radio is switched on. */
	public static final SoundEvent TUBE_WARMUP = register("sfx.tube_warmup");
	/** Heterodyne whistle when the dial passes over a station. */
	public static final SoundEvent TUNING_WHISTLE = register("sfx.tuning_whistle");
	/** Seamless loop of static between stations. */
	public static final SoundEvent STATIC_LOOP = register("sfx.static_loop");
	/** Seamless loop of the faint hiss of a carrier with no programme. */
	public static final SoundEvent CARRIER_HISS = register("sfx.carrier_hiss");

	private ModSounds() {
	}

	private static SoundEvent register(String path) {
		ResourceLocation id = FadoECravo.id(path);
		// A variable-range event takes its range from attenuation_distance in sounds.json.
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	/** Loads the class, which registers the sounds. */
	static void init() {
	}
}
