package com.manzarig.fadoecravo.registry;

import com.manzarig.fadoecravo.FadoECravo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

/** The mod's items. */
public final class ModItems {
	public static final BlockItem RADIO = register("radio", new BlockItem(ModBlocks.RADIO, new Item.Properties()));

	private ModItems() {
	}

	private static <T extends Item> T register(String name, T item) {
		return Registry.register(BuiltInRegistries.ITEM, FadoECravo.id(name), item);
	}

	/** Loads the class, which registers the items. */
	static void init() {
	}
}
