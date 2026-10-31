package com.manzarig.fadoecravo.datagen;

import com.manzarig.fadoecravo.registry.ModItems;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;

/** Writes the crafting recipes of the mod's items. */
public class ModRecipeProvider extends FabricRecipeProvider {
	public ModRecipeProvider(FabricDataOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries);
	}

	@Override
	public void buildRecipes(RecipeOutput output) {
		// Planks make the cabinet, a note block is the loudspeaker, copper is the wiring and redstone
		// stands in for the valves. The recipe appears in the recipe book once the player has a note block.
		ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.RADIO)
				.pattern("PPP")
				.pattern("PNP")
				.pattern("CRC")
				.define('P', ItemTags.PLANKS)
				.define('N', Items.NOTE_BLOCK)
				.define('C', Items.COPPER_INGOT)
				.define('R', Items.REDSTONE)
				.unlockedBy(getHasName(Items.NOTE_BLOCK), has(Items.NOTE_BLOCK))
				.save(output);
	}
}
