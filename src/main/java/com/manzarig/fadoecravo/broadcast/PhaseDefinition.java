package com.manzarig.fadoecravo.broadcast;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * What a station broadcasts in one {@link Phase}, as written in the station file.
 *
 * <p>The lists hold clip ids, which are keys of {@link StationDefinition#clips()}. The records
 * are immutable: the constructor copies the lists. {@link BroadcastSchedule} explains how they are
 * turned into a sequence of clips.
 *
 * @param onAir             false when the station is off the air for the whole phase; the lists are then ignored
 * @param opening           clips played once, in order, when the phase begins
 * @param programs          the programme, an ordered cycle that repeats until the phase ends
 * @param interstitials     clips (adverts, notices) that are inserted into the programme, also an ordered cycle
 * @param interstitialEvery one interstitial after this many programme items; 0 means none
 */
public record PhaseDefinition(boolean onAir, List<String> opening, List<String> programs, List<String> interstitials,
	int interstitialEvery) {
	/** A phase in which the station is off the air. */
	public static final PhaseDefinition OFF_AIR = new PhaseDefinition(false, List.of(), List.of(), List.of(), 0);
	/** JSON codec with the keys {@code on_air}, {@code opening}, {@code programs}, {@code interstitials} and {@code interstitial_every}. */
	public static final Codec<PhaseDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.BOOL.fieldOf("on_air").forGetter(PhaseDefinition::onAir),
		Codec.STRING.listOf().fieldOf("opening").forGetter(PhaseDefinition::opening),
		Codec.STRING.listOf().fieldOf("programs").forGetter(PhaseDefinition::programs),
		Codec.STRING.listOf().fieldOf("interstitials").forGetter(PhaseDefinition::interstitials),
		Codec.INT.fieldOf("interstitial_every").forGetter(PhaseDefinition::interstitialEvery)
	).apply(instance, PhaseDefinition::new));

	public PhaseDefinition {
		opening = List.copyOf(opening);
		programs = List.copyOf(programs);
		interstitials = List.copyOf(interstitials);
	}

	/** Every clip id the phase refers to, in the order opening, programmes, interstitials; repeats are kept. */
	public List<String> clipIds() {
		List<String> ids = new ArrayList<>(opening.size() + programs.size() + interstitials.size());
		ids.addAll(opening);
		ids.addAll(programs);
		ids.addAll(interstitials);
		return ids;
	}
}
