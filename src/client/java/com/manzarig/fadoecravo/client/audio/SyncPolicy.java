package com.manzarig.fadoecravo.client.audio;

import com.manzarig.fadoecravo.broadcast.Slot;
import java.util.Optional;

/**
 * The rules that keep a radio in step with the broadcast, as functions that need no game.
 *
 * <p><b>Why a radio needs rules at all.</b> Every client computes the same schedule from the world
 * time, so two players hear the same programme without a word being sent between them. But a radio
 * plays one clip after another by itself, and the audio and the clock drift apart: a clip that ends
 * a tenth of a second late, a time skip from sleeping or {@code /time set}, a server that lags.
 * The radio therefore follows the schedule's {@code next()} from clip to clip, and checks now and
 * then that it still agrees with {@code at()}:
 * <ul>
 *   <li>when a clip ends, {@link #continuation} starts the next one, from the exact position the
 *       schedule says if that is the same clip, so the small lateness of each clip does not add up
 *       over a night of ten-second chimes;
 *   <li>while a clip plays, {@link #driftSeconds} measures how far the playback is from the schedule,
 *       and more than {@link #DRIFT_LIMIT_SECONDS} means start again from {@code at()};
 *   <li>{@link #playState} decides from the sound engine whether the clip is still going, is over, or
 *       never started.
 * </ul>
 */
final class SyncPolicy {
	/** A radio that is further than this many seconds from the schedule starts again from it. */
	static final double DRIFT_LIMIT_SECONDS = 5.0;
	/** The day time is called frozen after it stood still for this many ticks (2 s). */
	static final int FROZEN_AFTER_TICKS = 40;
	/** A sound the engine has not reported active after this many ticks (1.5 s) did not start. */
	static final int START_GRACE_TICKS = 30;
	/** A clip still sounding this many seconds after it should have ended is replaced anyway. */
	static final double END_MARGIN_SECONDS = 2.0;
	/** Ticks (5 s) a radio waits before it tries again after a sound did not start. */
	static final int RETRY_TICKS = 100;
	private static final double TICKS_PER_SECOND = 20.0;

	private SyncPolicy() {
	}

	/** How a clip that was handed to the sound engine is getting on. */
	enum PlayState {
		/** Still sounding, or not yet reported by the engine but within the grace period. */
		PLAYING,
		/** Finished, whether it ran out by itself, was stopped by the engine or is overdue. */
		ENDED,
		/** The engine never reported it, e.g. because it had no channel free or the master volume is 0. */
		NEVER_STARTED
	}

	/**
	 * Decides how a clip is doing.
	 *
	 * <p>{@code SoundManager#isActive} is true from the moment {@code play} has set the sound up until
	 * the tick after its channel has stopped, so a sound that was active and no longer is has ended.
	 * One that never became active was skipped by the engine (no free streaming channel, master
	 * volume 0, an unknown sound id) and must not be taken for an ended clip, or the radio would
	 * race through the schedule. A sound that is still active well after its clip should have ended
	 * is given up on; {@code isActive} can stay true for a sound the engine dropped when the
	 * "Jukebox/Note Blocks" volume is 0 (the {@code RECORDS} category).
	 *
	 * @param seenActive       whether the engine has reported the sound active at any time
	 * @param active           whether it reports it active now
	 * @param ticksSincePlay   ticks since the sound was handed over
	 * @param expectedEndTicks see {@link #expectedEndTicks}
	 */
	static PlayState playState(boolean seenActive, boolean active, long ticksSincePlay, long expectedEndTicks) {
		if (active) {
			return ticksSincePlay >= expectedEndTicks ? PlayState.ENDED : PlayState.PLAYING;
		}
		if (seenActive) {
			return PlayState.ENDED;
		}
		return ticksSincePlay >= START_GRACE_TICKS ? PlayState.NEVER_STARTED : PlayState.PLAYING;
	}

	/**
	 * Ticks after which a clip is surely over: what is left of it plus {@link #END_MARGIN_SECONDS}.
	 * It is the safety net for an engine that does not say when a sound has ended.
	 *
	 * @param durationSeconds length of the clip
	 * @param offsetSeconds   where in the clip it was started
	 */
	static long expectedEndTicks(double durationSeconds, double offsetSeconds) {
		double remaining = Math.max(0.0, durationSeconds - Math.max(0.0, offsetSeconds));
		return (long) Math.ceil((remaining + END_MARGIN_SECONDS) * TICKS_PER_SECOND);
	}

	/**
	 * How many seconds the playback is away from the schedule, either way.
	 *
	 * <p>When both are the same clip of the same phase instance this is the difference of the
	 * positions. When the schedule is one clip further on (the playback is late) or one clip back
	 * (it is early), the distance is measured across the boundary between the two clips. Anything
	 * else, such as another phase instance, a bulletin against a scheduled clip or clips more than one
	 * apart, cannot be compared and counts as infinitely far.
	 *
	 * @param playing           the slot the radio started
	 * @param position          seconds into that clip that the radio has reached
	 * @param playingDuration   length of that clip
	 * @param reference         what the schedule says is on the air now
	 * @param referenceDuration length of the reference clip
	 */
	static double driftSeconds(Slot playing, double position, double playingDuration, Slot reference, double referenceDuration) {
		if (sameSlot(playing, reference)) {
			return Math.abs(reference.offsetSeconds() - position);
		}
		boolean comparable = playing.sequenceIndex() >= 0 && reference.sequenceIndex() >= 0
			&& playing.phase() == reference.phase() && playing.day() == reference.day();
		if (!comparable) {
			return Double.POSITIVE_INFINITY;
		}
		int gap = reference.sequenceIndex() - playing.sequenceIndex();
		if (gap == 1) {
			return Math.max(0.0, playingDuration - position) + reference.offsetSeconds();
		}
		if (gap == -1) {
			return Math.max(0.0, referenceDuration - reference.offsetSeconds()) + position;
		}
		return Double.POSITIVE_INFINITY;
	}

	/** Whether two slots are the same clip in the same place of the same phase instance, whatever their offsets. */
	static boolean sameSlot(Slot a, Slot b) {
		return a.sequenceIndex() == b.sequenceIndex() && a.phase() == b.phase() && a.day() == b.day()
			&& a.clipId().equals(b.clipId());
	}

	/**
	 * What to play when a clip has ended. Normally that is {@code next}, the clip after it. Two
	 * refinements, both only possible while the clock runs (pass an empty {@code scheduled} when it
	 * is frozen):
	 * <ul>
	 *   <li>if the schedule is at that same clip, play the schedule's slot, whose offset is how late
	 *       this radio is;
	 *   <li>if {@code next} from its start is more than {@link #DRIFT_LIMIT_SECONDS} from the
	 *       schedule, the radio was stopped from outside or the clock jumped, and the schedule wins.
	 * </ul>
	 *
	 * @param next              the clip after the one that ended, from its start; empty when the sequence is over
	 * @param scheduled         what the schedule says is on the air now; empty when it is off the air or the clock is frozen
	 * @param nextDuration      length of the {@code next} clip, if present
	 * @param scheduledDuration length of the {@code scheduled} clip, if present
	 * @return the slot to play, or empty for silence
	 */
	static Optional<Slot> continuation(Optional<Slot> next, Optional<Slot> scheduled, double nextDuration,
		double scheduledDuration) {
		if (next.isEmpty()) {
			return scheduled;
		}
		if (scheduled.isEmpty()) {
			return next;
		}
		if (sameSlot(next.get(), scheduled.get())) {
			return scheduled;
		}
		double drift = driftSeconds(next.get(), next.get().offsetSeconds(), nextDuration, scheduled.get(), scheduledDuration);
		return drift <= DRIFT_LIMIT_SECONDS ? next : scheduled;
	}
}
