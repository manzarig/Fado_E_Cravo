package com.manzarig.fadoecravo.datagen;

import com.manzarig.fadoecravo.FadoECravo;
import com.manzarig.fadoecravo.block.RadioBlock;
import com.manzarig.fadoecravo.registry.ModBlocks;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricModelProvider;
import net.minecraft.core.Direction;
import net.minecraft.data.models.BlockModelGenerators;
import net.minecraft.data.models.ItemModelGenerators;
import net.minecraft.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.data.models.blockstates.PropertyDispatch;
import net.minecraft.data.models.blockstates.Variant;
import net.minecraft.data.models.blockstates.VariantProperties;
import net.minecraft.resources.ResourceLocation;

/**
 * Writes the blockstate of the radio and the model of its item. The block models themselves,
 * {@code block/radio} and {@code block/radio_on}, are written by hand and only referred to here.
 */
public class ModModelProvider extends FabricModelProvider {
	private static final ResourceLocation RADIO_OFF_MODEL = FadoECravo.id("block/radio");
	private static final ResourceLocation RADIO_ON_MODEL = FadoECravo.id("block/radio_on");

	public ModModelProvider(FabricDataOutput output) {
		super(output);
	}

	@Override
	public void generateBlockStateModels(BlockModelGenerators generators) {
		// One variant for every combination of facing and on. POWERED is left out, so each variant
		// applies to both of its values: the model does not change with it.
		generators.blockStateOutput.accept(MultiVariantGenerator.multiVariant(ModBlocks.RADIO)
				.with(PropertyDispatch.properties(RadioBlock.FACING, RadioBlock.ON)
						.generate((facing, on) -> Variant.variant()
								.with(VariantProperties.MODEL, on ? RADIO_ON_MODEL : RADIO_OFF_MODEL)
								.with(VariantProperties.Y_ROT, rotationOf(facing)))));

		// The item looks like the radio that is switched off.
		generators.delegateItemModel(ModBlocks.RADIO, RADIO_OFF_MODEL);
	}

	@Override
	public void generateItemModels(ItemModelGenerators generators) {
		// The radio is a block item, whose model is written with the block above.
	}

	/** The models have their front on the north face, so a radio facing east is turned 90 degrees clockwise, and so on. */
	private static VariantProperties.Rotation rotationOf(Direction facing) {
		return switch (facing) {
			case EAST -> VariantProperties.Rotation.R90;
			case SOUTH -> VariantProperties.Rotation.R180;
			case WEST -> VariantProperties.Rotation.R270;
			default -> VariantProperties.Rotation.R0;
		};
	}
}
