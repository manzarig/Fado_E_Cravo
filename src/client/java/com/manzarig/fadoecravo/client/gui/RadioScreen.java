package com.manzarig.fadoecravo.client.gui;

import com.manzarig.fadoecravo.RadioClientBridge;
import com.manzarig.fadoecravo.block.RadioBlock;
import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;
import com.manzarig.fadoecravo.broadcast.StationDefinition;
import com.manzarig.fadoecravo.broadcast.Tuning;
import com.manzarig.fadoecravo.client.station.StationRegistry;
import com.manzarig.fadoecravo.net.ModNetworking;
import com.manzarig.fadoecravo.net.SetFrequencyPayload;
import com.manzarig.fadoecravo.net.TogglePowerPayload;
import com.manzarig.fadoecravo.registry.ModBlocks;
import com.manzarig.fadoecravo.registry.ModSounds;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

/**
 * The tuning screen of a radio: a dial with a needle, the frequency and station that are tuned in, a
 * signal meter and a power button. It has no container and does not pause the game, so the radio
 * keeps playing while the player tunes it.
 *
 * <p>Everything is drawn with {@link GuiGraphics#fill} in the Terracotta palette; textures come
 * later. Where things go is in {@link RadioLayout}, the dial arithmetic in {@link DialGeometry} and
 * the bookkeeping that keeps the needle from jumping in {@link FrequencySync}.
 *
 * <p>The frequency can be changed with the mouse wheel (1 kHz, 10 kHz with Shift), the left and
 * right arrow keys (the same) and by clicking or dragging on the dial. The change shows at once and
 * is sent to the server 250 ms after the last one, and when the screen closes.
 */
public class RadioScreen extends Screen {
	private static final String KEY_TITLE = "gui.fadoecravo.radio.title";
	private static final String KEY_POWER_ON = "gui.fadoecravo.radio.power_on";
	private static final String KEY_POWER_OFF = "gui.fadoecravo.radio.power_off";
	private static final String KEY_FREQUENCY = "gui.fadoecravo.radio.frequency";
	private static final String KEY_STATIC = "gui.fadoecravo.radio.static";
	private static final String KEY_SIGNAL_LOCKED = "gui.fadoecravo.radio.signal_locked";
	private static final String KEY_SIGNAL_WEAK = "gui.fadoecravo.radio.signal_weak";
	private static final String KEY_NO_STATION = "gui.fadoecravo.radio.no_station";
	private static final String KEY_TUNE_HINT = "gui.fadoecravo.radio.tune_hint";
	private static final String KEY_SIGNAL = "gui.fadoecravo.radio.signal";
	private static final String KEY_OFF_AIR = "gui.fadoecravo.radio.off_air";
	private static final String KEY_POWER = "gui.fadoecravo.radio.power";
	private static final String KEY_SWITCH_ON = "gui.fadoecravo.radio.switch_on";
	private static final String KEY_SWITCH_OFF = "gui.fadoecravo.radio.switch_off";

	// Terracotta palette (CLAUDE.md), as opaque ARGB.
	private static final int TERRACOTTA = 0xFFBD5D37;
	private static final int UMBER = 0xFF663A29;
	private static final int ALABASTER = 0xFFEDDFDA;
	private static final int CHARCOAL = 0xFF262322;
	private static final int SAFFRON = 0xFFD1C42E;
	private static final int GARNET = 0xFFA8190F;
	/** Alabaster, a little dimmed: 5:1 against umber and 11:1 against charcoal, for the small text. */
	private static final int ALABASTER_DIM = 0xFFC9B8AF;

	/** Wheel and arrow keys: 1 kHz, or 10 kHz with Shift. */
	private static final int STEP_KHZ = 1;
	private static final int BIG_STEP_KHZ = 10;
	private static final float WHISTLE_VOLUME = 0.7f;
	private static final long WHISTLE_COOLDOWN_MS = 300L;
	/** Smallest scale text is squeezed to before it is cut short. */
	private static final float MIN_TEXT_SCALE = 0.6f;

	private final BlockPos pos;
	private final DialGeometry dial = new DialGeometry(RadioLayout.DIAL_LEFT, RadioLayout.DIAL_WIDTH);
	private final FrequencySync sync;
	private final Cooldown whistleCooldown = new Cooldown(WHISTLE_COOLDOWN_MS);
	private Button powerButton;
	/** Top left corner of the panel on the screen. */
	private int panelX;
	private int panelY;
	private boolean draggingDial;

	/** Sets {@link RadioClientBridge} so that using a radio block opens this screen. Call once from the client initializer. */
	public static void register() {
		RadioClientBridge.setScreenOpener(pos -> Minecraft.getInstance().setScreen(new RadioScreen(pos)));
	}

	public RadioScreen(BlockPos pos) {
		super(Component.translatable(KEY_TITLE));
		this.pos = pos.immutable();
		int initial = RadioBlockEntity.DEFAULT_KHZ;
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null && level.getBlockEntity(this.pos) instanceof RadioBlockEntity radio) {
			initial = radio.getFrequencyKhz();
		}
		this.sync = new FrequencySync(initial, RadioBlockEntity.MIN_KHZ, RadioBlockEntity.MAX_KHZ);
	}

	// ------------------------------------------------------------------ screen lifecycle

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		panelX = Math.max(0, (width - RadioLayout.PANEL_W) / 2);
		panelY = Math.max(0, (height - RadioLayout.PANEL_H) / 2);
		RadioLayout.Rect b = RadioLayout.POWER_BUTTON;
		powerButton = addRenderableWidget(Button.builder(powerLabel(isOn()), button -> sendTogglePower())
			.bounds(panelX + b.x(), panelY + b.y(), b.w(), b.h())
			.tooltip(Tooltip.create(Component.translatable(KEY_POWER)))
			.build());
	}

	@Override
	public void tick() {
		super.tick();
		ClientLevel level = Minecraft.getInstance().level;
		LocalPlayer player = Minecraft.getInstance().player;
		if (level == null || player == null) {
			closeWithoutSending();
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (!state.is(ModBlocks.RADIO) || !(level.getBlockEntity(pos) instanceof RadioBlockEntity radio)) {
			closeWithoutSending();
			return;
		}
		// The same measure the server applies to the packets, so that the screen goes away
		// before the server would start ignoring it.
		if (!ModNetworking.isInReach(player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5))) {
			closeWithoutSending();
			return;
		}
		long now = Util.getMillis();
		sync.onServerValue(radio.getFrequencyKhz(), now);
		sync.pollSend(now).ifPresent(this::sendFrequency);
		powerButton.setMessage(powerLabel(state.getValue(RadioBlock.ON)));
	}

	/** Called whenever the screen is replaced or closed, so an unsent frequency goes out here. */
	@Override
	public void removed() {
		super.removed();
		sync.flush(Util.getMillis()).ifPresent(this::sendFrequency);
	}

	private void closeWithoutSending() {
		sync.discardPending();
		onClose();
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		// With Shift held some platforms turn the wheel into a sideways scroll.
		double amount = scrollY != 0.0 ? scrollY : scrollX;
		if (amount == 0.0) {
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		}
		nudge((int) Math.signum(amount) * (hasShiftDown() ? BIG_STEP_KHZ : STEP_KHZ));
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && RadioLayout.STRIP.contains(mouseX - panelX, mouseY - panelY)) {
			draggingDial = true;
			setFocused(null);
			setFromMouse(mouseX);
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (draggingDial && button == 0) {
			setFromMouse(mouseX);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button == 0 && draggingDial) {
			draggingDial = false;
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		// Before the default handling, which would use the arrow keys to move the focus.
		if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
			int step = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? BIG_STEP_KHZ : STEP_KHZ;
			nudge(keyCode == GLFW.GLFW_KEY_RIGHT ? step : -step);
			return true;
		}
		if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
			onClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private void setFromMouse(double mouseX) {
		applyChange(dial.khzForX(mouseX - panelX));
	}

	private void nudge(int deltaKhz) {
		applyChange(sync.shown() + deltaKhz);
	}

	/** Shows a new frequency now, whistles if the needle came into a station's window, and queues the send. */
	private void applyChange(int khz) {
		int previous = sync.shown();
		long now = Util.getMillis();
		if (!sync.set(khz, now)) {
			return;
		}
		// A radio that is switched off is silent, so it does not whistle either.
		if (isOn() && DialGeometry.entersAnyWindow(previous, sync.shown(), stationFrequencies(), Tuning.CLEAR_KHZ)
			&& whistleCooldown.tryAcquire(now)) {
			playWhistle();
		}
	}

	private static int[] stationFrequencies() {
		return StationRegistry.all().stream().mapToInt(StationDefinition::frequencyKhz).toArray();
	}

	private void playWhistle() {
		Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(ModSounds.TUNING_WHISTLE,
			SoundSource.RECORDS, WHISTLE_VOLUME, 1.0f, RandomSource.create(),
			pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
	}

	// ------------------------------------------------------------------ networking

	private void sendFrequency(int khz) {
		if (canSend(SetFrequencyPayload.TYPE)) {
			ClientPlayNetworking.send(new SetFrequencyPayload(pos, khz));
		}
	}

	private void sendTogglePower() {
		if (canSend(TogglePowerPayload.TYPE)) {
			ClientPlayNetworking.send(new TogglePowerPayload(pos));
		}
	}

	/** False when there is no connection (the screen is being torn down) or the server cannot take the packet. */
	private static boolean canSend(CustomPacketPayload.Type<?> type) {
		return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(type);
	}

	// ------------------------------------------------------------------ state

	private boolean isOn() {
		ClientLevel level = Minecraft.getInstance().level;
		return level != null && level.getBlockState(pos).getOptionalValue(RadioBlock.ON).orElse(false);
	}

	/** The button says what it will do: switch off a radio that is on, switch on one that is off. */
	private static Component powerLabel(boolean on) {
		return Component.translatable(on ? KEY_SWITCH_OFF : KEY_SWITCH_ON);
	}

	private RadioReadout readout() {
		ClientLevel level = Minecraft.getInstance().level;
		long dayTime = level == null ? 0L : level.getDayTime();
		return RadioReadout.of(StationRegistry.all(), sync.shown(), dayTime, isOn());
	}

	/** "Weak signal", with an English fallback for a language file that does not have the key yet. */
	private static Component signalWeakText() {
		return Component.translatableWithFallback(KEY_SIGNAL_WEAK, "Weak signal");
	}

	private static Component stationName(StationDefinition station) {
		return Component.translatable(station.name());
	}

	private static Component frequencyText(int khz) {
		return Component.translatable(KEY_FREQUENCY, khz);
	}

	// ------------------------------------------------------------------ narration

	@Override
	protected void updateNarratedWidget(NarrationElementOutput output) {
		output.add(NarratedElementType.POSITION, describe());
		super.updateNarratedWidget(output);
	}

	/** The frequency and what is on it, for the narrator. */
	private Component describe() {
		RadioReadout readout = readout();
		MutableComponent text = frequencyText(sync.shown()).copy();
		switch (readout.status()) {
			case POWER_OFF -> text.append(", ").append(Component.translatable(KEY_POWER_OFF));
			case NO_STATION -> text.append(", ").append(Component.translatable(KEY_NO_STATION));
			case OFF_AIR -> text.append(", ").append(stationName(readout.station()))
				.append(", ").append(Component.translatable(KEY_OFF_AIR));
			case ON_AIR -> {
				text.append(", ").append(stationName(readout.station()));
				if (readout.locked()) {
					text.append(", ").append(Component.translatable(KEY_SIGNAL_LOCKED));
				} else if (readout.weak()) {
					text.append(", ").append(signalWeakText());
				}
			}
		}
		return text;
	}

	// ------------------------------------------------------------------ drawing

	/**
	 * The world is dimmed a little instead of blurred, and the whole panel is drawn here, because
	 * {@link Screen#render} calls this first and draws the button afterwards, on top.
	 */
	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderTransparentBackground(graphics);
		drawFrame(graphics);
		drawDial(graphics);
		drawReadout(graphics);
		drawPowerRow(graphics);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		drawStationTooltip(graphics, mouseX, mouseY);
	}

	private void drawFrame(GuiGraphics g) {
		int x = panelX;
		int y = panelY;
		int w = RadioLayout.PANEL_W;
		int h = RadioLayout.PANEL_H;
		int b = RadioLayout.BORDER;
		g.fill(x, y, x + w, y + h, CHARCOAL);
		g.fill(x + b, y + b, x + w - b, y + h - b, UMBER);
		// A lit top and left edge and a shaded bottom and right edge.
		g.fill(x + b, y + b, x + w - b, y + b + 1, TERRACOTTA);
		g.fill(x + b, y + b, x + b + 1, y + h - b, TERRACOTTA);
		g.fill(x + b, y + h - b - 1, x + w - b, y + h - b, CHARCOAL);
		g.fill(x + w - b - 1, y + b, x + w - b, y + h - b, CHARCOAL);
		// Rivets in the corners.
		int rivet = 2;
		int[] rx = {x + 6, x + w - 6 - rivet};
		int[] ry = {y + 6, y + h - 6 - rivet};
		for (int cx : rx) {
			for (int cy : ry) {
				g.fill(cx, cy, cx + rivet, cy + rivet, TERRACOTTA);
			}
		}
		g.drawCenteredString(font, title, x + w / 2, y + RadioLayout.TITLE_Y, ALABASTER);
	}

	private void drawDial(GuiGraphics g) {
		RadioLayout.Rect strip = RadioLayout.STRIP;
		int sx = panelX + strip.x();
		int sy = panelY + strip.y();
		g.fill(sx - 1, sy - 1, sx + strip.w() + 1, sy + strip.h() + 1, CHARCOAL);
		g.fill(sx, sy, sx + strip.w(), sy + strip.h(), ALABASTER);

		// Ticks, standing on a base line.
		int baseY = panelY + RadioLayout.TICK_BASE_Y;
		g.fill(panelX + dial.left(), baseY, panelX + dial.left() + dial.width() + 1, baseY + 1, CHARCOAL);
		for (int khz = dial.firstTickKhz(); khz <= dial.lastTickKhz(); khz += DialGeometry.TICK_STEP_KHZ) {
			int tx = panelX + dial.tickX(khz);
			boolean major = DialGeometry.isMajor(khz);
			int tickH = major ? RadioLayout.TICK_MAJOR_H : RadioLayout.TICK_MINOR_H;
			g.fill(tx, baseY - tickH, tx + 1, baseY, major ? CHARCOAL : UMBER);
		}

		// Numbers. The last one would stick out of the strip if it were centred on its tick.
		for (int khz = DialGeometry.FIRST_LABEL_KHZ; khz <= dial.lastTickKhz(); khz += DialGeometry.LABEL_STEP_KHZ) {
			String label = Integer.toString(khz);
			int labelW = font.width(label);
			double centre = panelX + dial.centerX(khz);
			int lx = (int) Math.round(centre - (labelW - 1) / 2.0);
			lx = Math.max(sx + RadioLayout.LABEL_EDGE_PAD, Math.min(sx + strip.w() - RadioLayout.LABEL_EDGE_PAD - labelW, lx));
			g.drawString(font, label, lx, panelY + RadioLayout.LABEL_Y, CHARCOAL, false);
		}

		// Station marks: a terracotta pin pointing at the tick.
		for (StationDefinition station : StationRegistry.all()) {
			int tx = panelX + dial.tickX(station.frequencyKhz());
			int top = panelY + RadioLayout.MARK_Y;
			int half = RadioLayout.MARK_HALF_WIDTH;
			g.fill(tx - half, top, tx + half + 1, top + 1, TERRACOTTA);
			g.fill(tx - half + 1, top + 1, tx + half, top + 2, TERRACOTTA);
			g.fill(tx, top + 2, tx + 1, top + RadioLayout.MARK_H, TERRACOTTA);
		}

		// Needle.
		int nx = panelX + dial.tickX(sync.shown());
		int half = RadioLayout.NEEDLE_HALF_WIDTH;
		int cap = RadioLayout.NEEDLE_CAP_HALF_WIDTH;
		g.fill(nx - half, panelY + RadioLayout.NEEDLE_TOP, nx + half + 1, panelY + RadioLayout.NEEDLE_BOTTOM, GARNET);
		g.fill(nx - cap, panelY + RadioLayout.NEEDLE_TOP, nx + cap + 1,
			panelY + RadioLayout.NEEDLE_TOP + RadioLayout.NEEDLE_CAP_HEIGHT, GARNET);
	}

	private void drawReadout(GuiGraphics g) {
		RadioLayout.Rect box = RadioLayout.READOUT;
		g.fill(panelX + box.x(), panelY + box.y(), panelX + box.right(), panelY + box.bottom(), CHARCOAL);

		RadioReadout readout = readout();
		int leftX = panelX + RadioLayout.LEFT_COL_X;
		int rightX = panelX + RadioLayout.RIGHT_COL_X;

		// Left column: frequency, then the station or what the dial is picking up instead.
		drawFitted(g, frequencyText(sync.shown()), leftX, panelY + RadioLayout.FREQ_Y, RadioLayout.LEFT_COL_W,
			RadioLayout.FREQ_SCALE, SAFFRON, true, false);
		switch (readout.status()) {
			case POWER_OFF -> {
				// Off: the dial still works, so the station under the needle is named, dimmed, for pre-tuning.
				drawFitted(g, Component.translatable(KEY_POWER_OFF), leftX, panelY + RadioLayout.NAME_Y,
					RadioLayout.LEFT_COL_W, 1.0f, ALABASTER_DIM, true, false);
				if (readout.station() != null) {
					drawFitted(g, stationName(readout.station()), leftX, panelY + RadioLayout.SUB_Y,
						RadioLayout.LEFT_COL_W, RadioLayout.SUB_SCALE, ALABASTER_DIM, false, false);
				}
			}
			case NO_STATION -> {
				drawFitted(g, Component.translatable(KEY_STATIC), leftX, panelY + RadioLayout.NAME_Y,
					RadioLayout.LEFT_COL_W, 1.0f, ALABASTER, true, false);
				drawFitted(g, Component.translatable(KEY_NO_STATION), leftX, panelY + RadioLayout.SUB_Y,
					RadioLayout.LEFT_COL_W, RadioLayout.SUB_SCALE, ALABASTER_DIM, false, false);
			}
			case OFF_AIR -> {
				drawFitted(g, stationName(readout.station()), leftX, panelY + RadioLayout.NAME_Y,
					RadioLayout.LEFT_COL_W, 1.0f, ALABASTER, true, false);
				drawFitted(g, Component.translatable(KEY_OFF_AIR), leftX, panelY + RadioLayout.SUB_Y,
					RadioLayout.LEFT_COL_W, RadioLayout.SUB_SCALE, ALABASTER_DIM, false, false);
			}
			case ON_AIR -> drawFitted(g, stationName(readout.station()), leftX, panelY + RadioLayout.NAME_Y,
				RadioLayout.LEFT_COL_W, 1.0f, ALABASTER, true, false);
		}

		// Right column: signal meter (empty and dimmed when the radio is off), "locked" when the
		// programme comes in at full strength, "weak" when the station is on the air but cannot.
		boolean off = readout.status() == RadioReadout.Status.POWER_OFF;
		drawFitted(g, Component.translatable(KEY_SIGNAL), rightX, panelY + RadioLayout.SIGNAL_LABEL_Y,
			RadioLayout.RIGHT_COL_W, 1.0f, off ? ALABASTER_DIM : ALABASTER, true, false);
		drawSignalBar(g, readout.signal());
		if (readout.locked()) {
			drawFitted(g, Component.translatable(KEY_SIGNAL_LOCKED), rightX, panelY + RadioLayout.LOCKED_Y,
				RadioLayout.RIGHT_COL_W, 1.0f, SAFFRON, true, false);
		} else if (readout.weak()) {
			drawFitted(g, signalWeakText(), rightX, panelY + RadioLayout.LOCKED_Y,
				RadioLayout.RIGHT_COL_W, 1.0f, ALABASTER_DIM, true, false);
		}

		// Hint, centred under the readout.
		RadioLayout.Rect hint = RadioLayout.HINT;
		drawFitted(g, Component.translatable(KEY_TUNE_HINT), panelX + hint.x(), panelY + hint.y(), hint.w(),
			RadioLayout.HINT_SCALE, ALABASTER_DIM, false, true);
	}

	/** A meter of 10 segments: a terracotta frame, an umber track and saffron filling it up to {@code signal}. */
	private void drawSignalBar(GuiGraphics g, double signal) {
		RadioLayout.Rect bar = RadioLayout.SIGNAL_BAR;
		int x = panelX + bar.x();
		int y = panelY + bar.y();
		g.fill(x, y, x + bar.w(), y + bar.h(), TERRACOTTA);
		RadioLayout.Rect inner = bar.inset(1);
		int ix = panelX + inner.x();
		int iy = panelY + inner.y();
		g.fill(ix, iy, ix + inner.w(), iy + inner.h(), UMBER);
		int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, signal)) * inner.w());
		if (filled > 0) {
			g.fill(ix, iy, ix + filled, iy + inner.h(), SAFFRON);
		}
		int pitch = RadioLayout.SIGNAL_SEGMENT_W + 1;
		for (int i = 1; i < RadioLayout.SIGNAL_SEGMENTS; i++) {
			int gx = ix + i * pitch - 1;
			g.fill(gx, iy, gx + 1, iy + inner.h(), CHARCOAL);
		}
	}

	private void drawPowerRow(GuiGraphics g) {
		RadioLayout.Rect lamp = RadioLayout.LAMP;
		int lx = panelX + lamp.x();
		int ly = panelY + lamp.y();
		boolean on = isOn();
		// A round lamp: a charcoal ring around a saffron (on) or umber (off) glass.
		g.fill(lx + 1, ly, lx + lamp.w() - 1, ly + lamp.h(), CHARCOAL);
		g.fill(lx, ly + 1, lx + lamp.w(), ly + lamp.h() - 1, CHARCOAL);
		g.fill(lx + 2, ly + 1, lx + lamp.w() - 2, ly + lamp.h() - 1, on ? SAFFRON : UMBER);
		g.fill(lx + 1, ly + 2, lx + lamp.w() - 1, ly + lamp.h() - 2, on ? SAFFRON : UMBER);
		if (on) {
			g.fill(lx + 2, ly + 2, lx + 4, ly + 4, ALABASTER);
		}
		// Beside the lamp, the state; the button next to it names the action.
		String label = Component.translatable(on ? KEY_POWER_ON : KEY_POWER_OFF).getString();
		int labelX = Math.max(panelX + RadioLayout.INTERIOR.x() + 4, panelX + RadioLayout.POWER_LABEL_RIGHT - font.width(label));
		g.drawString(font, label, labelX, panelY + RadioLayout.POWER_BUTTON.y() + (RadioLayout.POWER_BUTTON.h() - font.lineHeight) / 2 + 1,
			ALABASTER, true);
	}

	/** Names a station when the mouse rests on its pin on the dial. */
	private void drawStationTooltip(GuiGraphics g, int mouseX, int mouseY) {
		int top = panelY + RadioLayout.MARK_Y - 1;
		if (mouseY < top || mouseY >= top + RadioLayout.MARK_H + 2) {
			return;
		}
		for (StationDefinition station : StationRegistry.all()) {
			int tx = panelX + dial.tickX(station.frequencyKhz());
			if (mouseX >= tx - RadioLayout.MARK_HALF_WIDTH - 1 && mouseX < tx + RadioLayout.MARK_HALF_WIDTH + 2) {
				Component text = Component.empty().append(stationName(station)).append(" - ")
					.append(frequencyText(station.frequencyKhz()));
				g.renderTooltip(font, text, mouseX, mouseY);
				return;
			}
		}
	}

	/**
	 * Draws text at {@code scale}, squeezed (down to {@link #MIN_TEXT_SCALE}) and then cut short with
	 * dots so that it fits in {@code maxWidth}; some translations are long.
	 *
	 * @param centered whether to centre the text in {@code maxWidth} instead of starting at {@code x}
	 */
	private void drawFitted(GuiGraphics g, Component text, int x, int y, int maxWidth, float scale, int color,
		boolean shadow, boolean centered) {
		String s = text.getString();
		int natural = font.width(s);
		if (natural == 0) {
			return;
		}
		float used = scale;
		if (natural * used > maxWidth) {
			used = Math.max(MIN_TEXT_SCALE, Math.min(scale, maxWidth / (float) natural));
		}
		int room = (int) (maxWidth / used);
		if (natural > room) {
			s = font.plainSubstrByWidth(s, room - font.width("...")) + "...";
			natural = font.width(s);
		}
		int dx = centered ? Math.round((maxWidth - natural * used) / 2f) : 0;
		if (used == 1.0f) {
			g.drawString(font, s, x + dx, y, color, shadow);
			return;
		}
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(x + dx, y, 0f);
		pose.scale(used, used, 1f);
		g.drawString(font, s, 0, 0, color, shadow);
		pose.popPose();
	}
}
