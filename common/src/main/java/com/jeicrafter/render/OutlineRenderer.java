package com.jeicrafter.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;

import java.util.Collection;

/**
 * Renders thin cuboid wireframe outlines around block positions in the world.
 * <p>
 * The platform render hook supplies a {@link PoseStack} that is already translated by the
 * negative camera position (Forge {@code RenderLevelStageEvent} and Fabric
 * {@code WorldRenderEvents.AFTER_ENTITIES} both leave the stack camera-translated),
 * so vertex positions are written in absolute world coordinates.
 */
public final class OutlineRenderer {
	private OutlineRenderer() {
	}

	/**
	 * Draws a 1×1×1 wireframe outline around each of the given positions.
	 *
	 * @param poseStack camera-translated pose stack
	 * @param positions block positions to outline (absolute world coords)
	 * @param red       color component 0..1
	 * @param green     color component 0..1
	 * @param blue      color component 0..1
	 * @param alpha     opacity 0..1
	 */
	public static void renderOutlines(PoseStack poseStack, Collection<BlockPos> positions, float red, float green, float blue, float alpha) {
		if (positions.isEmpty()) {
			return;
		}
		RenderSystem.disableDepthTest();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.lineWidth(5.0F);

		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder builder = tesselator.getBuilder();

		// Translucent filled box first so the highlight is impossible to miss even at a distance
		// or behind the dim JEI recipe screen. addChainedFilledBoxVertices emits a triangle strip
		// (vanilla's debugFilledBox uses TRIANGLE_STRIP) - feeding it as QUADS splits each face
		// into a single diagonal triangle.
		builder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
		for (BlockPos pos : positions) {
			LevelRenderer.addChainedFilledBoxVertices(
				poseStack,
				builder,
				pos.getX(), pos.getY(), pos.getZ(),
				pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D,
				red, green, blue, alpha * 0.45F
			);
		}
		tesselator.end();

		// Crisp wireframe edges on top.
		builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);
		for (BlockPos pos : positions) {
			LevelRenderer.renderLineBox(
				poseStack,
				builder,
				pos.getX(), pos.getY(), pos.getZ(),
				pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D,
				red, green, blue, alpha
			);
		}
		tesselator.end();

		RenderSystem.lineWidth(1.0F);
		RenderSystem.disableBlend();
		RenderSystem.enableDepthTest();
	}
}
