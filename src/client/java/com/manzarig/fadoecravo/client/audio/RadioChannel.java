package com.manzarig.fadoecravo.client.audio;

import com.manzarig.fadoecravo.broadcast.BroadcastSchedule;
import com.manzarig.fadoecravo.broadcast.ClipInfo;
import com.manzarig.fadoecravo.broadcast.PhaseInstance;
import com.manzarig.fadoecravo.broadcast.Slot;
import com.manzarig.fadoecravo.broadcast.StationDefinition;
import com.manzarig.fadoecravo.broadcast.Tuning;
import com.manzarig.fadoecravo.client.audio.SyncPolicy.PlayState;
import com.manzarig.fadoecravo.net.BreakingNewsPayload;
import com.mojang.logging.LogUtils;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Everything one switched-on radio plays: the station's programme, the static under it and, when
 * nothing is on the air, the carrier hiss. {@link RadioAudioManager} keeps one channel for each of
 * the radios it is playing and calls {@link #tick} on it once per client tick.
 *
 * <p><b>The state.</b> A channel is in one of three situations, shown by what it plays:
 * <ul>
 *   <li><i>on the air</i>: a station is tuned and the schedule (or a breaking-news bulletin) has a
 *       clip for this moment. The programme plays at the signal strength {@code s}, the static at
 *       {@code (1 - s) * 0.6} and, for a weak station, a floor on top, see {@link RadioMix};
 *   <li><i>off the air</i>: a station is tuned but the schedule has nothing on, or the programme did
 *       not start. Static at 0.6 and the carrier hiss at 0.5;
 *   <li><i>no station</i>: nothing is tuned. The same as off the air.
 * </ul>
 *
 * <p><b>Staying in step.</b> Each tick the channel works out the strongest station for the radio's
 * frequency, whether a bulletin is running on it, and the phase instance that owns the day time
 * ({@link BroadcastSchedule#ownerAt}, which allows for a clip carried over a phase boundary). It starts
 * again from {@link BroadcastSchedule#at} (a <i>resync</i>) when the station, the bulletin or the
 * phase instance differs from what it last started, when it has not been synced yet, when the
 * playback is more than {@link SyncPolicy#DRIFT_LIMIT_SECONDS} from the schedule (only checked while
 * the clock runs, see {@link DayClockWatch}), or when it is off the air but the schedule has a clip.
 * A bulletin is played from {@link BroadcastSchedule#breaking} instead. When a clip ends on its own
 * it carries on with {@link BroadcastSchedule#next}, so the programme goes on even if the clock
 * stands still. Only a change of station counts, not a change of frequency: the schedule does not
 * depend on the dial, so turning it within one station's reach just moves the volumes.
 *
 * <p><b>Sounds.</b> At most one programme, one static loop and one carrier loop at a time. Replacing
 * a programme fades the old one out over half a second, except at a scheduled change of phase,
 * where every radio switches in the same tick and a fade would open twice as many of the engine's
 * few streaming channels. A clip joined part-way is faded in so the cut does not click. Volumes are
 * set as targets and glide, see {@link RadioMix}. A loop is only there while it is wanted: the static
 * of a station that comes in clearly is not kept running at volume 0, because the engine tells the
 * subtitles about every sound it plays, silent or not. It is started when its target rises above
 * {@link RadioMix#LOOP_START_VOLUME}, faded out and stopped when the target falls to
 * {@link RadioMix#LOOP_STOP_VOLUME}, and brought back instead of started anew if the target
 * rises while it is still fading out.
 *
 * <p><b>Other radios.</b> The manager tells each tick how many radios of the same station are near
 * the listener ({@link RadioCrowd}); all the volumes are turned down by {@link RadioMix#crowdGain}
 * of that, so that several radios in step do not clip.
 *
 * <p>Only the client main thread calls this class. The sound engine is reached through
 * {@link Output}, which a test replaces.
 */
final class RadioChannel {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final double TICKS_PER_SECOND = 20.0;

	/** The part of the sound engine a channel uses. */
	interface Output {
		/** Starts a sound. The engine does this synchronously, so {@link #isActive} is true on return if it started. */
		void play(SoundInstance sound);

		/** Whether the engine knows the sound as playing, see {@link SyncPolicy#playState}. */
		boolean isActive(SoundInstance sound);

		/** Stops a sound on the spot. */
		void stop(SoundInstance sound);
	}

	/**
	 * What one tick knows about the world.
	 *
	 * @param tick           count of manager ticks; it does not advance while the game is paused
	 * @param dayTime        {@code Level#getDayTime()}
	 * @param gameTime       {@code Level#getGameTime()}
	 * @param dayTimeFrozen  whether the day time has stopped, see {@link DayClockWatch}
	 * @param stations       the stations the client knows
	 * @param news           the last bulletin the server announced, possibly cleared
	 */
	record Frame(long tick, long dayTime, long gameTime, boolean dayTimeFrozen, Collection<StationDefinition> stations,
		BreakingNewsPayload news) {
	}

	/** The clip being played and what is needed to follow it. */
	private static final class Programme {
		final Slot slot;
		final RadioSoundInstance sound;
		final long startTick;
		final double duration;
		final long expectedEndTicks;
		boolean seenActive;

		Programme(Slot slot, RadioSoundInstance sound, long startTick, double duration) {
			this.slot = slot;
			this.sound = sound;
			this.startTick = startTick;
			this.duration = duration;
			this.expectedEndTicks = SyncPolicy.expectedEndTicks(duration, slot.offsetSeconds());
		}

		/** Seconds into the clip the playback has reached. */
		double position(long tick) {
			return slot.offsetSeconds() + (tick - startTick) / TICKS_PER_SECOND;
		}
	}

	/** A looping sound, when it was handed over and whether the engine has ever had it. */
	private static final class Loop {
		final StaticSoundInstance sound;
		final long startTick;
		boolean seenActive;
		/** It is fading out to a stop and is not wanted any more, unless its target rises again. */
		boolean fadingOut;

		Loop(StaticSoundInstance sound, long startTick) {
			this.sound = sound;
			this.startTick = startTick;
		}
	}

	private final BlockPos pos;
	private final Output output;
	private final SoundEvent staticEvent;
	private final SoundEvent carrierEvent;

	private int frequencyKhz;
	private long lastTick;
	/** The station the channel last started something for; null for none. Compared by identity, so a reload of the stations resyncs. */
	@Nullable
	private StationDefinition station;
	private double signal;
	/** The signal strength of the tuned station, 1 when none is tuned. */
	private double strength = 1.0;
	/** How many radios of the tuned station are near the listener, this one included. */
	private int crowd = 1;
	/** The phase instance of the last start; null before the first one and after a sound that did not start. */
	@Nullable
	private PhaseInstance syncedPhase;
	/** The bulletin being played; null when the programme is the scheduled one. */
	@Nullable
	private BreakingNewsPayload playingNews;
	/** A bulletin whose audio ended before the clock said it should, so it must not be started again. */
	private BreakingNewsPayload finishedNews = BreakingNewsPayload.CLEARED;
	private long retryTick;
	@Nullable
	private Programme programme;
	@Nullable
	private Loop staticLoop;
	@Nullable
	private Loop carrier;
	private RadioMix mix = RadioMix.offAir();

	RadioChannel(BlockPos pos, Output output, SoundEvent staticEvent, SoundEvent carrierEvent) {
		this.pos = pos.immutable();
		this.output = output;
		this.staticEvent = staticEvent;
		this.carrierEvent = carrierEvent;
	}

	/** Advances the channel of a radio that has no company; see {@link #tick(Frame, int, int)}. */
	void tick(Frame frame, int frequencyKhz) {
		tick(frame, frequencyKhz, 1);
	}

	/**
	 * Advances the channel by one client tick; see the class comment for what it does.
	 *
	 * @param crowd how many playing radios on the same station are near the listener, this one
	 *              included if it is near ({@link RadioCrowd}); the volumes are divided by its square root
	 */
	void tick(Frame frame, int frequencyKhz, int crowd) {
		long now = frame.tick();
		this.lastTick = now;
		this.frequencyKhz = frequencyKhz;
		this.crowd = Math.max(1, crowd);

		Optional<Tuning.Tuned> tuned = Tuning.best(frame.stations(), frequencyKhz);
		StationDefinition tunedStation = tuned.map(Tuning.Tuned::station).orElse(null);
		this.signal = tuned.map(Tuning.Tuned::signal).orElse(0.0);
		this.strength = tunedStation == null ? 1.0 : tunedStation.signalStrength();

		boolean ended = false;
		boolean neverStarted = false;
		if (programme != null) {
			PlayState state = playState(programme, now);
			ended = state == PlayState.ENDED;
			neverStarted = state == PlayState.NEVER_STARTED;
		}

		Optional<Slot> bulletin = bulletinFor(tunedStation, frame);
		if (ended && playingNews != null && playingNews.equals(frame.news())) {
			// The audio is over although the clock may say the bulletin still runs: do not start it again.
			finishedNews = playingNews;
			bulletin = Optional.empty();
		}
		BreakingNewsPayload newsNow = bulletin.isPresent() ? frame.news() : null;
		// The instance that owns the moment, not the clock's: a song carried over into the next phase
		// is not a change of phase until it is over.
		PhaseInstance phaseNow = tunedStation == null ? PhaseInstance.of(frame.dayTime())
			: BroadcastSchedule.ownerAt(tunedStation, frame.dayTime());

		boolean stationChanged = tunedStation != station;
		boolean bulletinChanged = !Objects.equals(newsNow, playingNews);
		boolean phaseChanged = newsNow == null && tunedStation != null && syncedPhase != null && !phaseNow.equals(syncedPhase);
		boolean unsynced = syncedPhase == null && now >= retryTick;
		boolean resync = unsynced || stationChanged || bulletinChanged || phaseChanged;

		Optional<Slot> scheduled = null;
		if (!resync && syncedPhase != null && tunedStation != null) {
			if (newsNow != null) {
				resync = programme != null && isDrifting(tunedStation, bulletin, now);
			} else if (!frame.dayTimeFrozen()) {
				scheduled = BroadcastSchedule.at(tunedStation, frame.dayTime());
				resync = programme == null ? scheduled.isPresent() : isDrifting(tunedStation, scheduled, now);
			}
		}

		if (resync) {
			Optional<Slot> target;
			if (newsNow != null) {
				target = bulletin;
			} else if (tunedStation == null) {
				target = Optional.empty();
			} else {
				target = scheduled != null ? scheduled : BroadcastSchedule.at(tunedStation, frame.dayTime());
			}
			boolean cut = phaseChanged && !stationChanged && !bulletinChanged;
			LOGGER.debug("Radio at {} resyncs ({}) to {}", pos.toShortString(),
				unsynced ? "start" : stationChanged ? "station" : bulletinChanged ? "bulletin" : phaseChanged ? "phase" : "drift",
				target.map(Slot::clipId).orElse("nothing"));
			begin(tunedStation, target, newsNow, phaseNow, now, cut);
		} else if (ended) {
			continueAfterEnd(frame, now);
		} else if (neverStarted) {
			LOGGER.debug("Radio at {} could not start {}, trying again in {} ticks", pos.toShortString(),
				programme.slot.clipId(), SyncPolicy.RETRY_TICKS);
			output.stop(programme.sound);
			programme = null;
			// playingNews stays, so the bulletin is not mistaken for a new one before the retry.
			syncedPhase = null;
			retryTick = now + SyncPolicy.RETRY_TICKS;
		}
		applyMix(now);
	}

	/** Fades everything out over half a second and forgets it. For a radio that is off, out of range or unloaded. */
	void release() {
		retireProgramme(false);
		fadeOut(staticLoop);
		fadeOut(carrier);
		staticLoop = null;
		carrier = null;
		station = null;
		syncedPhase = null;
		playingNews = null;
	}

	/** Stops everything on the spot, e.g. when the player leaves the world. */
	void stopNow() {
		if (programme != null) {
			output.stop(programme.sound);
		}
		if (staticLoop != null) {
			output.stop(staticLoop.sound);
		}
		if (carrier != null) {
			output.stop(carrier.sound);
		}
		programme = null;
		staticLoop = null;
		carrier = null;
	}

	/** The signal of the programme being played, or 0 when no programme is; see {@link RadioAudioManager#speakerLevel}. */
	float speakerLevel() {
		return programme == null ? 0.0f : (float) Math.max(0.0, Math.min(1.0, signal));
	}

	/** What the channel is doing now. */
	RadioDebugState debugState() {
		Programme current = programme;
		return new RadioDebugState(pos, frequencyKhz, station == null ? null : station.id(), signal,
			current == null ? null : current.slot.clipId(), current == null ? null : current.slot.kind(),
			current == null ? 0.0 : current.position(lastTick), current == null ? 0.0f : mix.programme(), mix.staticLoop(),
			current != null && playingNews != null, crowd);
	}

	/** The bulletin slot for the tuned station, if a bulletin is running on it and has not been played out. */
	private Optional<Slot> bulletinFor(@Nullable StationDefinition tunedStation, Frame frame) {
		BreakingNewsPayload news = frame.news();
		if (tunedStation == null || news.isCleared() || !news.stationId().equals(tunedStation.id()) || news.equals(finishedNews)) {
			return Optional.empty();
		}
		return BroadcastSchedule.breaking(tunedStation, news.startGameTime(), news.seed(), frame.gameTime());
	}

	private PlayState playState(Programme current, long now) {
		boolean active = output.isActive(current.sound);
		current.seenActive |= active;
		return SyncPolicy.playState(current.seenActive, active, now - current.startTick, current.expectedEndTicks);
	}

	/** Whether the programme is further from {@code reference} than the radio allows; nothing to compare with counts as far. */
	private boolean isDrifting(StationDefinition tunedStation, Optional<Slot> reference, long now) {
		if (reference.isEmpty() || programme == null) {
			return true;
		}
		double drift = SyncPolicy.driftSeconds(programme.slot, programme.position(now), programme.duration,
			reference.get(), durationOf(tunedStation, reference.get()));
		return drift > SyncPolicy.DRIFT_LIMIT_SECONDS;
	}

	/** Starts what follows the clip that has just ended. */
	private void continueAfterEnd(Frame frame, long now) {
		StationDefinition current = Objects.requireNonNull(station);
		Optional<Slot> next = BroadcastSchedule.next(current, programme.slot);
		Optional<Slot> scheduled = frame.dayTimeFrozen() ? Optional.empty() : BroadcastSchedule.at(current, frame.dayTime());
		Optional<Slot> target = SyncPolicy.continuation(next, scheduled,
			next.map(slot -> durationOf(current, slot)).orElse(0.0),
			scheduled.map(slot -> durationOf(current, slot)).orElse(0.0));
		begin(current, target, null, syncedPhase, now, false);
	}

	/** Replaces the programme with {@code target}, or with silence when it is empty. */
	private void begin(@Nullable StationDefinition tuned, Optional<Slot> target, @Nullable BreakingNewsPayload news,
		@Nullable PhaseInstance phase, long now, boolean cut) {
		retireProgramme(cut);
		station = tuned;
		playingNews = news;
		syncedPhase = phase;
		if (tuned != null && target.isPresent()) {
			Slot slot = target.get();
			// Joined part-way: start silent and let the volume glide up, so the cut does not click.
			float volume = slot.offsetSeconds() > 0.0 ? 0.0f : RadioMix.onAir(signal, strength).inCrowd(crowd).programme();
			RadioSoundInstance sound = new RadioSoundInstance(slot.sound(), pos, slot.offsetSeconds(), volume);
			output.play(sound);
			Programme started = new Programme(slot, sound, now, durationOf(tuned, slot));
			started.seenActive = output.isActive(sound);
			programme = started;
		}
	}

	private void retireProgramme(boolean cut) {
		if (programme == null) {
			return;
		}
		programme.sound.fadeOutAndStop();
		if (cut) {
			output.stop(programme.sound);
		}
		programme = null;
	}

	/** Sets the target volumes and starts or drops the loops that are wanted or no longer wanted. */
	private void applyMix(long now) {
		mix = (programme != null ? RadioMix.onAir(signal, strength) : RadioMix.offAir()).inCrowd(crowd);
		if (programme != null) {
			programme.sound.setTargetVolume(mix.programme());
		}
		staticLoop = tend(staticLoop, staticEvent, mix.staticLoop(), now);
		carrier = tend(carrier, carrierEvent, mix.carrier(), now);
	}

	/**
	 * Brings a loop to its target volume. It is started (silent, to fade in) when the target rises
	 * above {@link RadioMix#LOOP_START_VOLUME} and faded out when it falls to
	 * {@link RadioMix#LOOP_STOP_VOLUME}, see {@link RadioMix#loopWanted}; a loop that is fading out
	 * is taken back, not replaced, if it is wanted again before it is gone. A loop the engine
	 * dropped, or never took ({@link SyncPolicy#RETRY_TICKS} after it was handed over), is started
	 * again.
	 *
	 * @return the loop to keep for the next tick; one that is fading out is kept until it has stopped
	 */
	@Nullable
	private Loop tend(@Nullable Loop loop, SoundEvent event, float volume, long now) {
		if (loop != null) {
			boolean active = output.isActive(loop.sound);
			loop.seenActive |= active;
			boolean dropped = loop.seenActive && !active;
			boolean neverStarted = !loop.seenActive && now - loop.startTick >= SyncPolicy.RETRY_TICKS;
			if (dropped || neverStarted || loop.sound.isStopped()) {
				loop = null;
			}
		}
		if (!RadioMix.loopWanted(volume, loop != null && !loop.fadingOut)) {
			if (loop != null && !loop.fadingOut) {
				loop.sound.fadeOutAndStop();
				loop.fadingOut = true;
			}
			return loop;
		}
		if (loop == null) {
			loop = new Loop(new StaticSoundInstance(event, pos, 0.0f), now);
			output.play(loop.sound);
			loop.seenActive = output.isActive(loop.sound);
		}
		loop.fadingOut = false;
		loop.sound.setTargetVolume(volume);
		return loop;
	}

	private static void fadeOut(@Nullable Loop loop) {
		if (loop != null) {
			loop.sound.fadeOutAndStop();
		}
	}

	/** Length of a clip in seconds as the schedule counts it. */
	private static double durationOf(StationDefinition tuned, Slot slot) {
		ClipInfo clip = tuned.clips().get(slot.clipId());
		double duration = clip == null ? 0.0 : clip.duration();
		return duration >= BroadcastSchedule.MIN_CLIP_SECONDS ? duration : BroadcastSchedule.MIN_CLIP_SECONDS;
	}
}
