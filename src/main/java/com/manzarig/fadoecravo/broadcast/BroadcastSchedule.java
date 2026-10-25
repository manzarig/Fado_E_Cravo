package com.manzarig.fadoecravo.broadcast;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Works out what a station is broadcasting at any moment. It is a pure function of the station file
 * and the time, so every client that asks gets the same answer and nothing has to be stored.
 *
 * <p><b>The sequence.</b> Each phase of each day is one <i>phase instance</i>, numbered by
 * {@link Phase#dayOf(long)}. Its clips form one sequence that starts when the phase starts:
 * <ol>
 *   <li>the {@code opening} clips, once, in order;
 *   <li>then the {@code programs} as an ordered cycle, starting at a rotated position, with one
 *       interstitial after every {@code interstitialEvery} programme items. The interstitials are
 *       also a cycle, taken in order from a seeded start. The programme list is never shuffled, so
 *       a station can rely on an item always following the one written before it.
 * </ol>
 * With no programmes the interstitials loop on their own, and with neither the sequence ends after
 * the opening. A phase that is off the air has no sequence.
 *
 * <p><b>The seed.</b> The programme starts at index {@code (hash mod (programs / step)) * step}
 * with {@code step = max(1, interstitialEvery)}, so it always begins at a multiple of the
 * interstitial spacing and pairs such as an announcement and the song it introduces stay together.
 * The interstitials start at index {@code (hash >>> 32) mod interstitials}. The hash comes from
 * {@link #hash(String, long, Phase)}.
 *
 * <p><b>Time.</b> The sequence is walked clip by clip, adding each clip's duration, until the time
 * that has passed since the phase began is reached. A clip counts for at least
 * {@link #MIN_CLIP_SECONDS}, which stops a clip with a zero duration from stalling the walk. A phase
 * lasts at most 550 s of this, so walking from the start every time is cheap. Clip ids that are not
 * in the station's {@code clips} are skipped as if they were not in the list.
 */
public final class BroadcastSchedule {
	/** Every clip is treated as at least one tick long, so a walk always moves forward. */
	public static final double MIN_CLIP_SECONDS = 0.05;
	private static final double TICKS_PER_SECOND = 20.0;
	/** Sums of durations are not exact; a clip counts as over when it is this close to its end, in seconds. */
	private static final double EPSILON = 1e-9;
	/** Longest run of unknown clip ids that is skipped before the sequence is given up as broken. */
	private static final int MAX_UNRESOLVED_RUN = 10_000;
	private static final long FNV_OFFSET_BASIS = 0xCBF29CE484222325L;
	private static final long FNV_PRIME = 0x100000001B3L;

	private BroadcastSchedule() {
	}

	/**
	 * What the station is broadcasting at {@code dayTime}, where {@code dayTime} is the world's
	 * continuing day time ({@code Level#getDayTime()}).
	 *
	 * @return the clip and the seconds already played of it, or empty when the station is off the
	 *         air: the phase is marked off the air, it is missing, or its sequence has ended
	 */
	public static Optional<Slot> at(StationDefinition station, long dayTime) {
		Phase phase = Phase.of(dayTime);
		Plan plan = Plan.of(station, phase, Phase.dayOf(dayTime));
		if (plan == null) {
			return Optional.empty();
		}
		double elapsed = phase.elapsedTicks(dayTime) / TICKS_PER_SECOND;
		double clipStart = 0.0;
		int index = 0;
		while (true) {
			Slot slot = plan.slotFrom(index);
			if (slot == null) {
				return Optional.empty();
			}
			double clipEnd = clipStart + effectiveDuration(station.clips().get(slot.clipId()));
			if (elapsed + EPSILON < clipEnd) {
				double offset = elapsed - clipStart;
				return Optional.of(slot.withOffset(offset < EPSILON ? 0.0 : offset));
			}
			clipStart = clipEnd;
			index = slot.sequenceIndex() + 1;
		}
	}

	/**
	 * The clip that follows {@code slot} in the same phase instance, from its beginning (offset 0).
	 * A radio uses it to carry on when a clip ends, without asking the clock, so playback goes on
	 * even when time stands still. It can lie past the end of the phase: the caller moves to
	 * {@link #at} when the phase changes.
	 *
	 * @return the following slot, or empty when the sequence ends there, the phase is off the air
	 *         or {@code slot} is a breaking-news slot
	 */
	public static Optional<Slot> next(StationDefinition station, Slot slot) {
		if (slot.sequenceIndex() < 0 || slot.sequenceIndex() == Integer.MAX_VALUE) {
			return Optional.empty();
		}
		Plan plan = Plan.of(station, slot.phase(), slot.day());
		if (plan == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(plan.slotFrom(slot.sequenceIndex() + 1));
	}

	/**
	 * The breaking-news bulletin that interrupts a station: one of its {@code breaking} clips,
	 * picked by {@code floorMod(seed, count)}, played from {@code startGameTime}.
	 *
	 * @param startGameTime game time in ticks at which the bulletin began
	 * @param seed          chosen once when the bulletin is announced, so everybody hears the same clip
	 * @param gameTime      the current game time in ticks
	 * @return the clip with offset {@code (gameTime - startGameTime) / 20} seconds, or empty before
	 *         the start, once the clip is over, or if the station has no breaking clip. The slot has
	 *         sequence index {@link Slot#BREAKING_INDEX}; its phase and day are those of
	 *         {@code startGameTime} read as a day time.
	 */
	public static Optional<Slot> breaking(StationDefinition station, long startGameTime, long seed, long gameTime) {
		List<String> ids = station.breaking();
		long elapsedTicks = gameTime - startGameTime;
		if (ids.isEmpty() || gameTime < startGameTime || elapsedTicks < 0) {
			return Optional.empty();
		}
		String id = ids.get((int) Math.floorMod(seed, (long) ids.size()));
		ClipInfo clip = station.clips().get(id);
		if (clip == null) {
			return Optional.empty();
		}
		double offset = elapsedTicks / TICKS_PER_SECOND;
		if (offset + EPSILON >= effectiveDuration(clip)) {
			return Optional.empty();
		}
		return Optional.of(new Slot(id, clip.sound(), clip.kind(), offset, Phase.of(startGameTime),
			Phase.dayOf(startGameTime), Slot.BREAKING_INDEX));
	}

	/**
	 * The clips of one phase instance in order, each with offset 0, produced as they are asked for.
	 * It is endless unless the phase has no programmes or interstitials (it then ends after the
	 * opening) and empty if the phase is off the air. Use {@code limit} on it.
	 */
	public static Stream<Slot> sequence(StationDefinition station, Phase phase, long day) {
		Plan plan = Plan.of(station, phase, day);
		if (plan == null) {
			return Stream.empty();
		}
		Iterator<Slot> iterator = new Iterator<>() {
			private Slot pending = plan.slotFrom(0);

			@Override
			public boolean hasNext() {
				return pending != null;
			}

			@Override
			public Slot next() {
				if (pending == null) {
					throw new NoSuchElementException();
				}
				Slot current = pending;
				pending = current.sequenceIndex() == Integer.MAX_VALUE ? null : plan.slotFrom(current.sequenceIndex() + 1);
				return current;
			}
		};
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL), false);
	}

	/**
	 * The 64-bit number that seeds the rotation of one phase instance. It is written out here
	 * instead of using {@code String.hashCode} or {@code Random} so that it cannot change between
	 * JVMs or Java versions: FNV-1a (64 bit) over the UTF-8 bytes of {@code stationId}, a zero
	 * byte, the UTF-8 bytes of the phase key, another zero byte and the eight bytes of
	 * {@code day} (big-endian), followed by the splitmix64 finalizer.
	 */
	public static long hash(String stationId, long day, Phase phase) {
		long h = FNV_OFFSET_BASIS;
		h = fnv(h, stationId.getBytes(StandardCharsets.UTF_8));
		h = fnv(h, new byte[] {0});
		h = fnv(h, phase.key().getBytes(StandardCharsets.UTF_8));
		h = fnv(h, new byte[] {0});
		for (int shift = 56; shift >= 0; shift -= 8) {
			h = fnv(h, new byte[] {(byte) (day >>> shift)});
		}
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		return h ^ (h >>> 31);
	}

	private static long fnv(long h, byte[] bytes) {
		for (byte b : bytes) {
			h ^= (b & 0xFFL);
			h *= FNV_PRIME;
		}
		return h;
	}

	/** Time a clip takes up in the walk. NaN, zero and negative durations count as {@link #MIN_CLIP_SECONDS}. */
	private static double effectiveDuration(ClipInfo clip) {
		double duration = clip.duration();
		return duration >= MIN_CLIP_SECONDS ? duration : MIN_CLIP_SECONDS;
	}

	/** The sequence of one phase instance, which can say which clip id sits at any index. */
	private record Plan(StationDefinition station, Phase phase, long day, PhaseDefinition definition, int programStart,
		int interstitialStart) {
		/** @return the plan, or null when the phase is missing or off the air */
		static Plan of(StationDefinition station, Phase phase, long day) {
			PhaseDefinition definition = station.phases().get(phase);
			if (definition == null || !definition.onAir()) {
				return null;
			}
			long hash = hash(station.id(), day, phase);
			int step = Math.max(1, definition.interstitialEvery());
			int groups = Math.max(1, definition.programs().size() / step);
			int programStart = (int) (Math.floorMod(hash, (long) groups) * step);
			int interstitialCount = definition.interstitials().size();
			int interstitialStart = interstitialCount == 0 ? 0 : (int) ((hash >>> 32) % interstitialCount);
			return new Plan(station, phase, day, definition, programStart, interstitialStart);
		}

		/** The first slot at {@code index} or later whose clip is known, with offset 0; null when the sequence ends first. */
		Slot slotFrom(int index) {
			int current = index;
			for (int skipped = 0; skipped <= MAX_UNRESOLVED_RUN && current >= 0; skipped++, current++) {
				String id = idAt(current);
				if (id == null) {
					return null;
				}
				ClipInfo clip = station.clips().get(id);
				if (clip != null) {
					return new Slot(id, clip.sound(), clip.kind(), 0.0, phase, day, current);
				}
			}
			return null;
		}

		/** The clip id at a position of the sequence, or null once a sequence without a loop has ended. */
		private String idAt(int index) {
			List<String> opening = definition.opening();
			if (index < opening.size()) {
				return opening.get(index);
			}
			long loop = (long) index - opening.size();
			List<String> programs = definition.programs();
			List<String> interstitials = definition.interstitials();
			int every = definition.interstitialEvery();
			if (programs.isEmpty()) {
				if (interstitials.isEmpty()) {
					return null;
				}
				return interstitials.get((int) ((interstitialStart + loop) % interstitials.size()));
			}
			if (every <= 0 || interstitials.isEmpty()) {
				return programs.get((int) ((programStart + loop) % programs.size()));
			}
			// The loop is blocks of `every` programme items followed by one interstitial.
			long block = loop / (every + 1L);
			long inBlock = loop % (every + 1L);
			if (inBlock < every) {
				return programs.get((int) ((programStart + block * every + inBlock) % programs.size()));
			}
			return interstitials.get((int) ((interstitialStart + block) % interstitials.size()));
		}
	}
}
