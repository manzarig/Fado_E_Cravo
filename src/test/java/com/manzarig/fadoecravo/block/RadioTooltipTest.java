package com.manzarig.fadoecravo.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/** The hint under the name of the radio item. */
class RadioTooltipTest {
	@Test
	void theHintIsTheTranslatedTooltipKeyOfTheBlock() {
		Component line = RadioTooltip.line();
		TranslatableContents contents = assertInstanceOf(TranslatableContents.class, line.getContents());
		assertEquals("block.fadoecravo.radio.tooltip", contents.getKey());
	}

	@Test
	void theHintIsGrey() {
		assertNotNull(RadioTooltip.line().getStyle().getColor());
		assertEquals((int) ChatFormatting.GRAY.getColor(), RadioTooltip.line().getStyle().getColor().getValue());
	}
}
