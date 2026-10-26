package com.manzarig.fadoecravo.block;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * The hint under the name of the radio item. It lives apart from {@link RadioBlock} so that a test
 * can build it without starting the game's registries, which the block class needs.
 */
final class RadioTooltip {
	/** Translation key of the hint, in both language files. */
	static final String KEY = "block.fadoecravo.radio.tooltip";

	private RadioTooltip() {
	}

	/** The hint, grey like the hints of vanilla blocks. */
	static Component line() {
		return Component.translatable(KEY).withStyle(ChatFormatting.GRAY);
	}
}
