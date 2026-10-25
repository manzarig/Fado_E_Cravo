package com.manzarig.fadoecravo.registry;

import com.manzarig.fadoecravo.FadoECravo;
import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** The mod's block entity types. */
public final class ModBlockEntities {
	// The argument of build() is the data fixer type, which a mod without a datafixer passes as null.
	public static final BlockEntityType<RadioBlockEntity> RADIO = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			FadoECravo.id("radio"),
			BlockEntityType.Builder.of(RadioBlockEntity::new, ModBlocks.RADIO).build(null));

	private ModBlockEntities() {
	}

	/** Loads the class, which registers the block entity types. */
	static void init() {
	}
}
