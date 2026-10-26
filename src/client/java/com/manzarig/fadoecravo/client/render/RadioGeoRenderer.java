package com.manzarig.fadoecravo.client.render;

import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;
import com.manzarig.fadoecravo.registry.ModBlockEntities;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/** Draws the radio block: the {@link RadioGeoModel} turned to the block's facing, and its {@link RadioLitLayer}. */
public class RadioGeoRenderer extends GeoBlockRenderer<RadioBlockEntity> {
	public RadioGeoRenderer() {
		super(new RadioGeoModel());
		addRenderLayer(new RadioLitLayer(this));
	}

	/** Registers the renderer for the radio block entity. Call once from the client initializer. */
	public static void register() {
		BlockEntityRenderers.register(ModBlockEntities.RADIO, context -> new RadioGeoRenderer());
	}
}
