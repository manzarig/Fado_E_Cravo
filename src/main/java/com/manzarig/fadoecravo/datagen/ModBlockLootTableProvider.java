package com.manzarig.fadoecravo.datagen;

import com.manzarig.fadoecravo.registry.ModBlocks;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricBlockLootTableProvider;
import net.minecraft.core.HolderLookup;

/** Writes the loot tables of the mod's blocks. */
public class ModBlockLootTableProvider extends FabricBlockLootTableProvider {
	public ModBlockLootTableProvider(FabricDataOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries);
	}

	@Override
	public void generate() {
		dropSelf(ModBlocks.RADIO);
	}
}
