package com.manzarig.fadoecravo.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.manzarig.fadoecravo.client.gui.RadioLayout.Rect;
import java.util.List;
import org.junit.jupiter.api.Test;

class RadioLayoutTest {
	@Test
	void thePanelIsAboutTheContractedSizeAndFitsASmallWindow() {
		assertEquals(248, RadioLayout.PANEL_W);
		assertEquals(150, RadioLayout.PANEL_H);
		// 320 x 180 is the smallest window at GUI scale 4 on a 720p screen.
		assertTrue(RadioLayout.PANEL_W <= 320);
		assertTrue(RadioLayout.PANEL_H <= 180);
	}

	@Test
	void everyBlockIsInsideTheBorder() {
		for (Rect rect : blocks()) {
			assertTrue(rect.isInside(RadioLayout.INTERIOR), rect + " must be inside " + RadioLayout.INTERIOR);
		}
		// The strip has a 1 px outline.
		assertTrue(RadioLayout.STRIP.inset(-1).isInside(RadioLayout.INTERIOR));
	}

	@Test
	void theBlocksDoNotOverlap() {
		List<Rect> blocks = blocks();
		for (int i = 0; i < blocks.size(); i++) {
			for (int j = i + 1; j < blocks.size(); j++) {
				assertFalse(blocks.get(i).intersects(blocks.get(j)), blocks.get(i) + " overlaps " + blocks.get(j));
			}
		}
		// The strip's outline must not touch the readout either.
		assertFalse(RadioLayout.STRIP.inset(-1).intersects(RadioLayout.READOUT));
	}

	@Test
	void theTitleLeavesRoomAboveTheStrip() {
		int fontHeight = 9;
		assertTrue(RadioLayout.TITLE_Y >= RadioLayout.BORDER + 1);
		assertTrue(RadioLayout.TITLE_Y + fontHeight < RadioLayout.STRIP.y() - 1);
	}

	@Test
	void theDialIsInsideTheStripWithRoomForTheLastLabel() {
		Rect strip = RadioLayout.STRIP;
		int last = RadioLayout.DIAL_LEFT + RadioLayout.DIAL_WIDTH;
		assertTrue(RadioLayout.DIAL_LEFT - strip.x() >= 8);
		assertTrue(strip.right() - last >= 8);
		// A 4 digit number is at most 24 px wide, and gets 3 px from the strip's edge at the end.
		assertTrue(strip.right() - RadioLayout.LABEL_EDGE_PAD - 24 > RadioLayout.DIAL_LEFT);
	}

	@Test
	void theStripHoldsNeedleNumbersMarksAndTicksInOrderFromTheTop() {
		Rect strip = RadioLayout.STRIP;
		int numberHeight = 9;
		assertTrue(RadioLayout.NEEDLE_TOP >= strip.y());
		assertTrue(RadioLayout.NEEDLE_TOP + RadioLayout.NEEDLE_CAP_HEIGHT <= RadioLayout.LABEL_Y, "cap above the numbers");
		assertTrue(RadioLayout.LABEL_Y + numberHeight <= RadioLayout.MARK_Y + 2, "numbers above the marks (the font's descent is empty for digits)");
		assertTrue(RadioLayout.MARK_Y + RadioLayout.MARK_H <= RadioLayout.TICK_BASE_Y - RadioLayout.TICK_MAJOR_H,
			"marks above the tall ticks");
		assertTrue(RadioLayout.TICK_BASE_Y + 1 <= strip.bottom());
		assertTrue(RadioLayout.NEEDLE_BOTTOM <= strip.bottom());
		assertTrue(RadioLayout.TICK_MAJOR_H > RadioLayout.TICK_MINOR_H);
	}

	@Test
	void theReadoutColumnsFitInTheReadout() {
		Rect readout = RadioLayout.READOUT;
		assertTrue(RadioLayout.LEFT_COL_X >= readout.x() + 8);
		assertTrue(RadioLayout.LEFT_COL_X + RadioLayout.LEFT_COL_W < RadioLayout.RIGHT_COL_X);
		assertTrue(RadioLayout.RIGHT_COL_X + RadioLayout.RIGHT_COL_W <= readout.right() - 8);
		// Rows: frequency (2x font, 18 px), name (9 px), sub line (0.75x font, 7 px).
		int freqBottom = RadioLayout.FREQ_Y + (int) Math.ceil(9 * RadioLayout.FREQ_SCALE);
		assertTrue(RadioLayout.FREQ_Y >= readout.y());
		assertTrue(freqBottom <= RadioLayout.NAME_Y);
		assertTrue(RadioLayout.NAME_Y + 9 <= RadioLayout.SUB_Y + 1);
		assertTrue(RadioLayout.SUB_Y + (int) Math.ceil(9 * RadioLayout.SUB_SCALE) <= readout.bottom());
		// Right column: label, bar, locked.
		assertTrue(RadioLayout.SIGNAL_LABEL_Y + 9 <= RadioLayout.SIGNAL_BAR.y());
		assertTrue(RadioLayout.SIGNAL_BAR.bottom() <= RadioLayout.LOCKED_Y);
		assertTrue(RadioLayout.LOCKED_Y + 9 <= readout.bottom());
	}

	@Test
	void theSignalBarIsTenSegments() {
		Rect inner = RadioLayout.SIGNAL_BAR.inset(1);
		int pitch = RadioLayout.SIGNAL_SEGMENT_W + 1;
		assertEquals(RadioLayout.SIGNAL_SEGMENTS * RadioLayout.SIGNAL_SEGMENT_W + (RadioLayout.SIGNAL_SEGMENTS - 1), inner.w());
		assertEquals(RadioLayout.SIGNAL_SEGMENTS * pitch - 1, inner.w());
		assertTrue(RadioLayout.SIGNAL_BAR.right() <= RadioLayout.RIGHT_COL_X + RadioLayout.RIGHT_COL_W);
	}

	@Test
	void theHintFitsBelowTheReadout() {
		assertTrue(RadioLayout.HINT.y() >= RadioLayout.READOUT.bottom());
		assertTrue(RadioLayout.HINT.h() >= (int) Math.ceil(9 * RadioLayout.HINT_SCALE));
	}

	@Test
	void theButtonIsCentredAndTheLampAndLabelSitToItsLeft() {
		Rect button = RadioLayout.POWER_BUTTON;
		assertEquals(RadioLayout.PANEL_W, 2 * button.x() + button.w(), "centred");
		assertEquals(20, button.h(), "a vanilla button height");
		assertTrue(RadioLayout.LAMP.right() < button.x());
		assertTrue(RadioLayout.POWER_LABEL_RIGHT < RadioLayout.LAMP.x());
		// The lamp is centred on the button's height.
		assertEquals(button.y() + button.h() / 2, RadioLayout.LAMP.y() + RadioLayout.LAMP.h() / 2);
	}

	@Test
	void rectsKnowTheirEdges() {
		Rect rect = new Rect(10, 20, 30, 40);
		assertTrue(rect.contains(10, 20));
		assertTrue(rect.contains(39.9, 59.9));
		assertFalse(rect.contains(40, 30), "the right edge is outside");
		assertFalse(rect.contains(20, 60), "the bottom edge is outside");
		assertFalse(rect.contains(9.9, 30));
		assertEquals(new Rect(11, 21, 28, 38), rect.inset(1));
		assertTrue(rect.intersects(new Rect(39, 59, 5, 5)));
		assertFalse(rect.intersects(new Rect(40, 20, 5, 5)));
		assertTrue(new Rect(11, 21, 28, 38).isInside(rect));
		assertFalse(rect.isInside(new Rect(11, 21, 28, 38)));
	}

	private static List<Rect> blocks() {
		return List.of(RadioLayout.STRIP, RadioLayout.READOUT, RadioLayout.HINT, RadioLayout.POWER_BUTTON, RadioLayout.LAMP);
	}
}
