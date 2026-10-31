package com.manzarig.fadoecravo.client.gui;

/**
 * Where everything on the radio screen sits, in GUI pixels from the top left corner of the panel.
 * The numbers live here, apart from the drawing code, so that a test can check that nothing overlaps
 * or sticks out of the panel.
 *
 * <pre>
 *   y   0  frame (2 px charcoal border)
 *       5  title
 *      17  dial strip (alabaster), 40 px: needle cap, numbers, station marks, ticks
 *      62  readout (charcoal), 46 px: frequency / station / signal bar
 *     112  hint line (small text)
 *     124  power: label, lamp and button, 20 px
 *     150  end of panel
 * </pre>
 *
 * The panel is 248 px wide, so it fits a 320 px wide window at any GUI scale from 2 up, and 150 px
 * tall, which fits a 180 px high window (a 720p screen at GUI scale 4).
 */
public final class RadioLayout {
	/** A rectangle in panel coordinates. */
	public record Rect(int x, int y, int w, int h) {
		public int right() {
			return x + w;
		}

		public int bottom() {
			return y + h;
		}

		/** Whether the point is inside; the right and bottom edges are outside, as in {@code GuiGraphics#fill}. */
		public boolean contains(double px, double py) {
			return px >= x && px < right() && py >= y && py < bottom();
		}

		public boolean intersects(Rect other) {
			return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
		}

		/** Whether this rectangle lies completely within {@code outer}. */
		public boolean isInside(Rect outer) {
			return x >= outer.x && y >= outer.y && right() <= outer.right() && bottom() <= outer.bottom();
		}

		/** The rectangle {@code margin} pixels smaller on every side. */
		public Rect inset(int margin) {
			return new Rect(x + margin, y + margin, w - 2 * margin, h - 2 * margin);
		}
	}

	public static final int PANEL_W = 248;
	public static final int PANEL_H = 150;
	/** Width of the charcoal border. */
	public static final int BORDER = 2;
	public static final Rect PANEL = new Rect(0, 0, PANEL_W, PANEL_H);
	/** What is left of the panel inside the border. */
	public static final Rect INTERIOR = PANEL.inset(BORDER);

	public static final int TITLE_Y = 5;

	/** The alabaster dial strip. A charcoal outline of 1 px is drawn around it. */
	public static final Rect STRIP = new Rect(6, 17, 236, 40);
	/** Where the first tick column is and how wide the ticks spread; 2 px for every 10 kHz. */
	public static final int DIAL_LEFT = 17;
	public static final int DIAL_WIDTH = 214;
	/** Needle: a bar from the top to the bottom of the strip with a cap on top. */
	public static final int NEEDLE_TOP = STRIP.y() + 1;
	public static final int NEEDLE_BOTTOM = STRIP.bottom() - 2;
	public static final int NEEDLE_HALF_WIDTH = 0;
	public static final int NEEDLE_CAP_HALF_WIDTH = 2;
	public static final int NEEDLE_CAP_HEIGHT = 3;
	/** Top of the numbers above the dial. */
	public static final int LABEL_Y = 22;
	/** Station pin: 7 px tall, 5 px wide at the top. */
	public static final int MARK_Y = 31;
	public static final int MARK_H = 7;
	public static final int MARK_HALF_WIDTH = 2;
	/** The line the ticks stand on; 1 px thick. */
	public static final int TICK_BASE_Y = 53;
	public static final int TICK_MAJOR_H = 11;
	public static final int TICK_MINOR_H = 6;
	/** Gap between the strip's edge and a number that would otherwise stick out of it. */
	public static final int LABEL_EDGE_PAD = 3;

	/** The charcoal display. */
	public static final Rect READOUT = new Rect(6, 62, 236, 46);
	public static final int LEFT_COL_X = 16;
	public static final int LEFT_COL_W = 116;
	public static final int RIGHT_COL_X = 140;
	public static final int RIGHT_COL_W = 92;
	/** Frequency, drawn at twice the size of the normal font. */
	public static final int FREQ_Y = 66;
	public static final float FREQ_SCALE = 2.0f;
	public static final int NAME_Y = 88;
	public static final int SUB_Y = 98;
	public static final float SUB_SCALE = 0.75f;
	public static final int SIGNAL_LABEL_Y = 66;
	/** Signal meter: 10 segments of 8 px with a gap of 1 px, inside a 1 px frame. */
	public static final Rect SIGNAL_BAR = new Rect(RIGHT_COL_X, 77, 91, 8);
	public static final int SIGNAL_SEGMENT_W = 8;
	public static final int SIGNAL_SEGMENTS = 10;
	public static final int LOCKED_Y = 88;

	/** The small text at the bottom of the readout. */
	public static final Rect HINT = new Rect(16, 112, 216, 8);
	public static final float HINT_SCALE = 0.75f;

	public static final Rect POWER_BUTTON = new Rect(82, 124, 84, 20);
	/** The pilot lamp to the left of the button. */
	public static final Rect LAMP = new Rect(68, 130, 8, 8);
	/** The label "Power" ends here. */
	public static final int POWER_LABEL_RIGHT = 62;

	private RadioLayout() {
	}
}
