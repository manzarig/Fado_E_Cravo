package com.manzarig.fadoecravo.broadcast;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A radio station as described by a schema v1 station file ({@code stations/<id>.json}): where it
 * sits on the dial, how strong it is, what it broadcasts in each {@link Phase} and the clips it can
 * play. {@link BroadcastSchedule} and {@link Tuning} work on these.
 *
 * <p>The record is immutable: the constructor copies the lists and maps. {@link #CODEC} reads the
 * file as written and does not judge it; {@link #validate()} reports what is wrong with the content
 * and {@link #parse(JsonElement)} does both.
 *
 * @param schema         file format version, 1 for this class
 * @param id             station id, equal to the file name without {@code .json}
 * @param name           translation key of the display name, e.g. {@code station.fadoecravo.radio_tejo}
 * @param frequencyKhz   position on the dial in kHz
 * @param signalStrength strength in {@code (0, 1]} that the signal never exceeds, however well tuned
 * @param phases         what the station does in each phase; every phase should be present
 * @param breaking       ids of the clips that can be used for a breaking-news bulletin
 * @param clips          every clip the station can play, by id
 */
public record StationDefinition(int schema, String id, String name, int frequencyKhz, double signalStrength,
	Map<Phase, PhaseDefinition> phases, List<String> breaking, Map<String, ClipInfo> clips) {
	/** The only schema version this class understands. */
	public static final int SCHEMA = 1;
	/** Lowest frequency on the dial, the same as {@code RadioBlockEntity.MIN_KHZ}. */
	public static final int MIN_KHZ = 530;
	/** Highest frequency on the dial, the same as {@code RadioBlockEntity.MAX_KHZ}. */
	public static final int MAX_KHZ = 1600;
	/** JSON codec with the exact snake_case keys of schema v1. It does not check the content; see {@link #validate()}. */
	public static final Codec<StationDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.INT.fieldOf("schema").forGetter(StationDefinition::schema),
		Codec.STRING.fieldOf("id").forGetter(StationDefinition::id),
		Codec.STRING.fieldOf("name").forGetter(StationDefinition::name),
		Codec.INT.fieldOf("frequency_khz").forGetter(StationDefinition::frequencyKhz),
		Codec.DOUBLE.fieldOf("signal_strength").forGetter(StationDefinition::signalStrength),
		Codec.unboundedMap(Phase.CODEC, PhaseDefinition.CODEC).fieldOf("phases").forGetter(StationDefinition::phases),
		Codec.STRING.listOf().fieldOf("breaking").forGetter(StationDefinition::breaking),
		Codec.unboundedMap(Codec.STRING, ClipInfo.CODEC).fieldOf("clips").forGetter(StationDefinition::clips)
	).apply(instance, StationDefinition::new));

	public StationDefinition {
		EnumMap<Phase, PhaseDefinition> phaseCopy = new EnumMap<>(Phase.class);
		phaseCopy.putAll(phases);
		phases = Collections.unmodifiableMap(phaseCopy);
		breaking = List.copyOf(breaking);
		clips = Collections.unmodifiableMap(new LinkedHashMap<>(clips));
	}

	/**
	 * Reads a station file and rejects it if {@link #validate()} finds anything wrong.
	 *
	 * @return the station, or an error whose message lists the problems
	 */
	public static DataResult<StationDefinition> parse(JsonElement json) {
		return CODEC.parse(JsonOps.INSTANCE, json).flatMap(station -> {
			List<String> problems = station.validate();
			if (problems.isEmpty()) {
				return DataResult.success(station);
			}
			return DataResult.error(() -> "Invalid station: " + String.join("; ", problems));
		});
	}

	/**
	 * Checks the content of the station.
	 *
	 * <p>It reports an unsupported schema, a blank id or name, a frequency off the dial, a signal
	 * strength outside {@code (0, 1]}, a missing phase, clip ids that are not in {@code clips}, clips
	 * with an unknown kind or a duration that is not positive, a breaking clip that is not of kind
	 * {@code breaking}, a negative {@code interstitial_every}, interstitials that can never be
	 * played, and an on-air phase that has nothing to loop.
	 *
	 * @return one message per problem; empty when the station is fine
	 */
	public List<String> validate() {
		List<String> problems = new ArrayList<>();
		if (schema != SCHEMA) {
			problems.add("schema is " + schema + ", expected " + SCHEMA);
		}
		if (id.isBlank()) {
			problems.add("id is blank");
		}
		if (name.isBlank()) {
			problems.add("name is blank");
		}
		if (frequencyKhz < MIN_KHZ || frequencyKhz > MAX_KHZ) {
			problems.add("frequency_khz " + frequencyKhz + " is outside " + MIN_KHZ + ".." + MAX_KHZ);
		}
		if (!(signalStrength > 0.0 && signalStrength <= 1.0)) {
			problems.add("signal_strength " + signalStrength + " is outside (0, 1]");
		}

		for (Phase phase : Phase.values()) {
			PhaseDefinition definition = phases.get(phase);
			if (definition == null) {
				problems.add("phase " + phase.key() + " is missing");
			} else {
				validatePhase(phase, definition, problems);
			}
		}

		for (String clipId : breaking) {
			ClipInfo clip = clips.get(clipId);
			if (clip == null) {
				problems.add("breaking refers to unknown clip '" + clipId + "'");
			} else if (!ClipInfo.KIND_BREAKING.equals(clip.kind())) {
				problems.add("breaking clip '" + clipId + "' has kind '" + clip.kind() + "', expected breaking");
			}
		}

		clips.forEach((clipId, clip) -> {
			if (!ClipInfo.KINDS.contains(clip.kind())) {
				problems.add("clip '" + clipId + "' has unknown kind '" + clip.kind() + "'");
			}
			if (!(clip.duration() > 0.0) || Double.isInfinite(clip.duration())) {
				problems.add("clip '" + clipId + "' has duration " + clip.duration() + ", which is not a positive number");
			}
		});
		return problems;
	}

	private void validatePhase(Phase phase, PhaseDefinition definition, List<String> problems) {
		String prefix = "phase " + phase.key() + ": ";
		checkReferences(prefix, "opening", definition.opening(), problems);
		checkReferences(prefix, "programs", definition.programs(), problems);
		checkReferences(prefix, "interstitials", definition.interstitials(), problems);
		if (definition.interstitialEvery() < 0) {
			problems.add(prefix + "interstitial_every is " + definition.interstitialEvery() + ", expected 0 or more");
		}
		if (!definition.onAir()) {
			return;
		}
		if (definition.programs().isEmpty() && definition.interstitials().isEmpty()) {
			problems.add(prefix + "on the air but has no programs or interstitials to loop, so it falls silent after the opening");
		}
		if (!definition.programs().isEmpty() && !definition.interstitials().isEmpty() && definition.interstitialEvery() == 0) {
			problems.add(prefix + "has interstitials but interstitial_every is 0, so they are never played");
		}
	}

	private void checkReferences(String prefix, String list, List<String> ids, List<String> problems) {
		for (String clipId : ids) {
			if (!clips.containsKey(clipId)) {
				problems.add(prefix + list + " refers to unknown clip '" + clipId + "'");
			}
		}
	}
}
