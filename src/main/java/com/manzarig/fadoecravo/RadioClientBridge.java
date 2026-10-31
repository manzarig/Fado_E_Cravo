package com.manzarig.fadoecravo;

import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;

/**
 * Lets the radio block, which lives in common code, open a screen that only exists on the client.
 *
 * <p>{@code RadioBlock} must not name a client class because a dedicated server cannot load it.
 * It calls {@link #openScreen(BlockPos)} instead, which does nothing until the client has set
 * {@link #screenOpener}, so on a dedicated server (or before the client has started) the call is
 * harmless.
 */
public final class RadioClientBridge {
	private static final Consumer<BlockPos> NO_OP = pos -> {
	};

	/**
	 * Opens the radio screen for the radio at the given position. The client replaces it when it
	 * starts, either by assigning this field or through {@link #setScreenOpener(Consumer)}.
	 */
	public static volatile Consumer<BlockPos> screenOpener = NO_OP;

	/**
	 * How strongly the radio at a position is sounding a programme, 0 to 1. The client sets it; on a
	 * dedicated server it stays 0. {@code RadioBlock} uses it for the music notes it gives off.
	 */
	public static volatile ToDoubleFunction<BlockPos> speakerLevel = pos -> 0.0;

	private RadioClientBridge() {
	}

	/** The level {@link #speakerLevel} reports for {@code pos}; 0 without a client. */
	public static double speakerLevel(BlockPos pos) {
		ToDoubleFunction<BlockPos> level = speakerLevel;
		return level == null ? 0.0 : level.applyAsDouble(pos);
	}

	/** Opens the radio screen for the radio at {@code pos}; does nothing without a client. */
	public static void openScreen(BlockPos pos) {
		Consumer<BlockPos> opener = screenOpener;
		if (opener != null) {
			opener.accept(pos);
		}
	}

	/** Sets {@link #screenOpener}; {@code null} restores the default, which does nothing. */
	public static void setScreenOpener(Consumer<BlockPos> opener) {
		screenOpener = opener == null ? NO_OP : opener;
	}
}
