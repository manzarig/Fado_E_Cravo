package com.manzarig.fadoecravo.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** The panel texture drawn by {@code tools/art/make_radio_gui.py} lines up with {@link RadioLayout}. */
class RadioPanelTextureTest {
	private static BufferedImage panel() throws Exception {
		try (InputStream in = RadioPanelTextureTest.class.getResourceAsStream("/assets/fadoecravo/textures/gui/radio.png")) {
			assertNotNull(in, "textures/gui/radio.png is missing");
			return ImageIO.read(in);
		}
	}

	private static double luminance(int argb) {
		return 0.2126 * ((argb >> 16) & 0xFF) + 0.7152 * ((argb >> 8) & 0xFF) + 0.0722 * (argb & 0xFF);
	}

	@Test
	void thePanelSitsAtTheTopLeftOfTheTexture() throws Exception {
		BufferedImage image = panel();
		assertEquals(RadioScreen.PANEL_TEXTURE_SIZE, image.getWidth());
		assertEquals(RadioScreen.PANEL_TEXTURE_SIZE, image.getHeight());
		assertEquals(0xFF, image.getRGB(RadioLayout.PANEL_W - 1, RadioLayout.PANEL_H - 1) >>> 24, "opaque to its last pixel");
		assertEquals(0, image.getRGB(RadioLayout.PANEL_W, 0) >>> 24, "transparent beyond the panel");
	}

	@Test
	void theDialStripIsPaperAndTheReadoutIsGlass() throws Exception {
		BufferedImage image = panel();
		RadioLayout.Rect strip = RadioLayout.STRIP;
		RadioLayout.Rect readout = RadioLayout.READOUT;
		double paper = luminance(image.getRGB(strip.x() + strip.w() / 2, strip.y() + strip.h() / 2));
		double glass = luminance(image.getRGB(readout.x() + readout.w() / 2, readout.y() + readout.h() / 2));
		double frame = luminance(image.getRGB(strip.x() - 1, strip.y() + strip.h() / 2));
		assertTrue(paper > 180, "dial strip is light: " + paper);
		assertTrue(glass < 60, "readout is dark: " + glass);
		assertTrue(frame < 60, "the strip has a dark frame: " + frame);
	}
}
