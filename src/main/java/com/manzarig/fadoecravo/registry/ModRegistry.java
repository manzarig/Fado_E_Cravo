package com.manzarig.fadoecravo.registry;

/**
 * Single entry point for everything the mod registers. {@link #init()} is called once from
 * {@code FadoECravo#onInitialize}; each registry class registers its entries in static
 * initialisers, so touching the class is what registers them.
 *
 * <p>The order matters: the block entity type needs the block, the item needs the block and the
 * creative tab needs the item.
 */
public final class ModRegistry {
	private ModRegistry() {
	}

	public static void init() {
		ModSounds.init();
		ModBlocks.init();
		ModItems.init();
		ModBlockEntities.init();
		ModCreativeTab.init();
	}
}
