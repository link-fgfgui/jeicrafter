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

		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder builder = tesselator.getBuilder();
		builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);
		VertexConsumer consumer = builder;

		for (BlockPos pos : positions) {
			LevelRenderer.renderLineBox(
				poseStack,
				consumer,
				pos.getX(), pos.getY(), pos.getZ(),
				pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D,
				red, green, blue, alpha
			);
		}

		tesselator.end();

		RenderSystem.disableBlend();
		RenderSystem.enableDepthTest();
	}
}
