package com.manzarig.fadoecravo.client.render;

import com.manzarig.fadoecravo.FadoECravo;
import com.manzarig.fadoecravo.block.RadioBlock;
import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * What glows on a radio that is on: the dial scale, the pilot lamp and the valves behind the vents
 * in the back and the crown. It draws the model again, full bright, with {@code radio_lit.png}, which
 * has the texture's layout but only the lit texels.
 *
 * <p>GeckoLib's own {@code AutoGlowingGeoLayer} is not used: it cuts the glowing texels out of the
 * base texture, which would leave holes in a radio that is off. While the valves warm up
 * ({@link RadioBlockEntity#warmupTicksLeft}) the glow fades in with a flicker.
 */
public class RadioLitLayer extends GeoRenderLayer<RadioBlockEntity> {
	private static final ResourceLocation LIT = FadoECravo.id("textures/block/radio_lit.png");

	public RadioLitLayer(GeoRenderer<RadioBlockEntity> renderer) {
		super(renderer);
	}

	@Override
	public void render(PoseStack poseStack, RadioBlockEntity radio, BakedGeoModel bakedModel, @Nullable RenderType renderType,
		MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
		BlockState state = radio.getBlockState();
		Level level = radio.getLevel();
		if (level == null || !state.hasProperty(RadioBlock.ON) || !state.getValue(RadioBlock.ON)) {
			return;
		}
		float glow = WarmGlow.brightness(radio.warmupTicksLeft(level.getGameTime()), level.getGameTime() + partialTick);
		if (glow <= 0.0f) {
			return;
		}
		RenderType lit = RenderType.entityTranslucentEmissive(LIT);
		int colour = FastColor.ARGB32.color(Math.round(glow * 255), 255, 255, 255);
		getRenderer().reRender(bakedModel, poseStack, bufferSource, radio, lit, bufferSource.getBuffer(lit), partialTick,
			LightTexture.FULL_BRIGHT, packedOverlay, colour);
	}
}
