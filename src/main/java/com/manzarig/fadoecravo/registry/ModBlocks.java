package com.manzarig.fadoecravo.registry;

import com.manzarig.fadoecravo.FadoECravo;
import com.manzarig.fadoecravo.block.RadioBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;

/** The mod's blocks. */
public final class ModBlocks {
	/**
	 * A wooden cabinet that is not a full cube, so it must not occlude neighbouring faces. It is
	 * mined with an axe through the {@code minecraft:mineable/axe} tag and drops itself.
	 */
	public static final RadioBlock RADIO = register("radio", new RadioBlock(BlockBehaviour.Properties.of()
			.mapColor(MapColor.WOOD)
			.instrument(NoteBlockInstrument.BASS)
			.sound(SoundType.WOOD)
			.strength(2.0f)
			.noOcclusion()));

	private ModBlocks() {
	}

	private static <T extends Block> T register(String name, T block) {
		return Registry.register(BuiltInRegistries.BLOCK, FadoECravo.id(name), block);
	}

	/** Loads the class, which registers the blocks. */
	static void init() {
	}
}
