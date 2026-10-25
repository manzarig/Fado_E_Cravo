package com.manzarig.fadoecravo.broadcast;

import net.minecraft.resources.ResourceLocation;

/**
 * A clip that is on the air, and how far into it the broadcast is.
 *
 * <p>{@link BroadcastSchedule#at} returns the slot for a moment in time and
 * {@link BroadcastSchedule#next} the one after it. The slot says which phase instance it came from
 * ({@code phase} and {@code day}) and its position in that instance's sequence, which is all
 * {@code next} needs to carry on. A slot from {@link BroadcastSchedule#breaking} belongs to no
 * schedule: its {@code sequenceIndex} is {@link #BREAKING_INDEX} and its phase and day describe the
 * moment the bulletin started.
 *
 * @param clipId        id of the clip in {@link StationDefinition#clips()}
 * @param sound         sound event id to play
 * @param kind          kind of the clip, see {@link ClipInfo#KINDS}
 * @param offsetSeconds seconds into the clip that the broadcast has reached; 0 when the clip is just beginning
 * @param phase         phase of the instance the slot belongs to
 * @param day           number of the phase instance, see {@link Phase#dayOf(long)}
 * @param sequenceIndex position in the phase instance's sequence, starting at 0 for the first opening clip
 */
public record Slot(String clipId, ResourceLocation sound, String kind, double offsetSeconds, Phase phase, long day,
	int sequenceIndex) {
	/** The {@code sequenceIndex} of a breaking-news slot. */
	public static final int BREAKING_INDEX = -1;

	/** The same slot with a different offset. */
	public Slot withOffset(double offsetSeconds) {
		return new Slot(clipId, sound, kind, offsetSeconds, phase, day, sequenceIndex);
	}
}
