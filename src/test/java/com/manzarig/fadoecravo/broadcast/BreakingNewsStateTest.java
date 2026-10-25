package com.manzarig.fadoecravo.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.manzarig.fadoecravo.net.BreakingNewsPayload;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** Saving and loading of the bulletin on the air; the registry lookup is left to the game. */
class BreakingNewsStateTest {
	private static BreakingNewsState saveAndLoad(BreakingNewsState state) {
		// The registry lookup is not used by this state, so a test does not need one.
		return BreakingNewsState.load(state.save(new CompoundTag(), null), null);
	}

	@Test
	void startsWithNoBulletin() {
		assertSame(BreakingNewsPayload.CLEARED, new BreakingNewsState().current());
		assertTrue(new BreakingNewsState().current().isCleared());
	}

	@Test
	void aBulletinSurvivesSavingAndLoading() {
		BreakingNewsState state = new BreakingNewsState();
		state.start("radio_tejo", 123_456L, -4_611_686_018_427_387_904L);

		BreakingNewsPayload loaded = saveAndLoad(state).current();

		assertEquals(new BreakingNewsPayload("radio_tejo", 123_456L, -4_611_686_018_427_387_904L), loaded);
		assertFalse(loaded.isCleared());
	}

	@Test
	void aNewBulletinReplacesTheOldOne() {
		BreakingNewsState state = new BreakingNewsState();
		state.start("radio_tejo", 100L, 1L);
		state.start("emissora_lusitana", 200L, 2L);

		assertEquals(new BreakingNewsPayload("emissora_lusitana", 200L, 2L), state.current());
	}

	@Test
	void clearingTakesTheBulletinOffTheAirAndStaysClearedWhenSaved() {
		BreakingNewsState state = new BreakingNewsState();
		state.start("radio_tejo", 100L, 1L);
		state.clear();

		assertTrue(state.current().isCleared());
		assertTrue(saveAndLoad(state).current().isCleared());
	}

	@Test
	void startAndClearMarkTheDataAsNeedingASave() {
		BreakingNewsState state = new BreakingNewsState();
		assertFalse(state.isDirty());
		state.start("radio_tejo", 100L, 1L);
		assertTrue(state.isDirty());
		state.setDirty(false);
		state.clear();
		assertTrue(state.isDirty());
	}

	@Test
	void anEmptyTagMeansNoBulletin() {
		assertTrue(BreakingNewsState.load(new CompoundTag(), null).current().isCleared());
	}
}
