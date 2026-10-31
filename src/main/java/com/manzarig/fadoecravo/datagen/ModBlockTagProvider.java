package com.manzarig.fadoecravo.datagen;

import com.manzarig.fadoecravo.registry.ModBlocks;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.tags.BlockTags;

/** Writes the block tags the mod's blocks belong to. */
public class ModBlockTagProvider extends FabricTagProvider.BlockTagProvider {
	public ModBlockTagProvider(FabricDataOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries);
	}

	@Override
	protected void addTags(HolderLookup.Provider registries) {
		getOrCreateTagBuilder(BlockTags.MINEABLE_WITH_AXE).add(ModBlocks.RADIO);
	}
}
