package com.manzarig.fadoecravo.client.audio;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * What one active radio is doing at the moment, as {@link RadioAudioManager#snapshot()} reports it
 * for tests and debugging tools.
 *
 * @param pos             the radio block
 * @param frequencyKhz    the dial setting the radio was last ticked with
 * @param stationId       id of the station it picks up best, or null when none comes in
 * @param signal          reception strength in {@code [0, 1]}, 0 when no station comes in
 * @param clipId          id of the clip on the air, or null when there is no programme (static only)
 * @param kind            kind of that clip, e.g. {@code music} or {@code breaking}, or null
 * @param offsetSeconds   where in the clip the playback is now, 0 when there is no programme
 * @param programmeVolume the volume the programme is being faded to (the signal times the crowd gain), 0
 *                        without a programme; the sound's own volume glides towards it
 * @param staticVolume    the volume the static loop is being faded to: {@code (1 - signal) * 0.6} plus the
 *                        floor of a weak station on the air, 0.6 off the air, all times the crowd gain;
 *                        the loop itself only plays while this is above a small threshold
 * @param breaking        whether the programme is a breaking-news bulletin
 * @param crowd           how many playing radios on the same station are near the listener, this one
 *                        included (1 for a radio alone); the volumes are divided by its square root
 */
public record RadioDebugState(BlockPos pos, int frequencyKhz, @Nullable String stationId, double signal,
	@Nullable String clipId, @Nullable String kind, double offsetSeconds, float programmeVolume, float staticVolume,
	boolean breaking, int crowd) {
}
