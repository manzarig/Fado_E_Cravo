package com.manzarig.fadoecravo.registry;

import com.manzarig.fadoecravo.FadoECravo;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/** The creative tab that holds the mod's items. */
public final class ModCreativeTab {
	public static final CreativeModeTab TAB = Registry.register(
			BuiltInRegistries.CREATIVE_MODE_TAB,
			FadoECravo.id("main"),
			FabricItemGroup.builder()
					.title(Component.translatable("itemGroup." + FadoECravo.MOD_ID))
					.icon(() -> new ItemStack(ModItems.RADIO))
					.displayItems((parameters, output) -> output.accept(ModItems.RADIO))
					.build());

	private ModCreativeTab() {
	}

	/** Loads the class, which registers the tab. */
	static void init() {
	}
}
