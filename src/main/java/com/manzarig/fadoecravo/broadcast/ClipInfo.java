package com.manzarig.fadoecravo.broadcast;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * One entry of a station file's {@code clips} map: which sound to play, what sort of clip it is and
 * how long the final OGG lasts.
 *
 * @param sound    id of an entry in sounds.json, e.g. {@code fadoecravo:radio.radio_tejo.ident_1}
 * @param kind     one of {@link #KINDS}
 * @param duration length in seconds, measured from the final OGG
 */
public record ClipInfo(ResourceLocation sound, String kind, double duration) {
	/** The kind of a breaking-news bulletin, which {@link BroadcastSchedule#breaking} plays outside the schedule. */
	public static final String KIND_BREAKING = "breaking";
	/** Every kind that schema v1 allows. */
	public static final Set<String> KINDS = Set.of("music", "ident", "news", "notice", "ad", "announce", "request",
		"personal_messages", "signoff", KIND_BREAKING, "interval");
	/** JSON codec with the keys {@code sound}, {@code kind} and {@code duration}. */
	public static final Codec<ClipInfo> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		ResourceLocation.CODEC.fieldOf("sound").forGetter(ClipInfo::sound),
		Codec.STRING.fieldOf("kind").forGetter(ClipInfo::kind),
		Codec.DOUBLE.fieldOf("duration").forGetter(ClipInfo::duration)
	).apply(instance, ClipInfo::new));
}
