package com.jeicrafter.render;

import com.jeicrafter.config.JeiCrafterConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;

/**
 * Renders thin cuboid wireframe outlines and filled boxes around block positions in the world.
 * <p>
 * The platform render hook supplies a {@link PoseStack} that is already translated by the
 * negative camera position (Forge {@code RenderLevelStageEvent} and Fabric
 * {@code WorldRenderEvents.AFTER_ENTITIES} both leave the stack camera-translated),
 * so vertex positions are written in absolute world coordinates.
 * <p>
 * When a block is far away from the camera, its rendered bounds are scaled up symmetrically so that
 * its apparent screen size does not drop below the threshold configured by
 * {@link JeiCrafterConfig#minHighlightDistance()}.
 */
public final class OutlineRenderer {
	private OutlineRenderer() {
	}

	/**
	 * Draws wireframe outlines and filled boxes around each of the given positions using the active camera.
	 */
	public static void renderOutlines(PoseStack poseStack, Collection<BlockPos> positions, float red, float green, float blue, float alpha) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 camera = mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null ? mc.gameRenderer.getMainCamera().getPosition() : null;
		renderOutlines(poseStack, camera, positions, red, green, blue, alpha);
	}

	/**
	 * Draws wireframe outlines and filled boxes around each of the given positions.
	 *
	 * @param poseStack camera-translated pose stack
	 * @param cameraPos camera world position (used to enforce minimum apparent screen size)
	 * @param positions block positions to outline (absolute world coords)
	 * @param red       color component 0..1
	 * @param green     color component 0..1
	 * @param blue      color component 0..1
	 * @param alpha     opacity 0..1
	 */
	public static void renderOutlines(PoseStack poseStack, Vec3 cameraPos, Collection<BlockPos> positions, float red, float green, float blue, float alpha) {
		if (positions.isEmpty()) {
			return;
		}

		double minDistance = JeiCrafterConfig.minHighlightDistance();
		int count = positions.size();
		double[] bounds = new double[count * 6];
		int idx = 0;

		for (BlockPos pos : positions) {
			double minX = pos.getX();
			double minY = pos.getY();
			double minZ = pos.getZ();
			double maxX = minX + 1.0D;
			double maxY = minY + 1.0D;
			double maxZ = minZ + 1.0D;

			if (cameraPos != null && minDistance > 0.0D) {
				double cx = minX + 0.5D;
				double cy = minY + 0.5D;
				double cz = minZ + 0.5D;
				double dx = cx - cameraPos.x;
				double dy = cy - cameraPos.y;
				double dz = cz - cameraPos.z;
				double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (dist > minDistance) {
					double half = 0.5D * (dist / minDistance);
					minX = cx - half;
					maxX = cx + half;
					minY = cy - half;
					maxY = cy + half;
					minZ = cz - half;
					maxZ = cz + half;
				}
			}

			bounds[idx++] = minX;
			bounds[idx++] = minY;
			bounds[idx++] = minZ;
			bounds[idx++] = maxX;
			bounds[idx++] = maxY;
			bounds[idx++] = maxZ;
		}

		RenderSystem.disableDepthTest();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.lineWidth(5.0F);

		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder builder = tesselator.getBuilder();

		// Translucent filled box first so the highlight is impossible to miss even at a distance
		// or behind the dim JEI recipe screen. Each box uses an isolated TRIANGLE_STRIP to avoid
		// connecting degenerate triangles across separate block positions in the world.
		for (int i = 0; i < count; i++) {
			int offset = i * 6;
			builder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
			LevelRenderer.addChainedFilledBoxVertices(
				poseStack,
				builder,
				bounds[offset], bounds[offset + 1], bounds[offset + 2],
				bounds[offset + 3], bounds[offset + 4], bounds[offset + 5],
				red, green, blue, alpha * 0.45F
			);
			tesselator.end();
		}

		// Crisp wireframe edges on top.
		builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);
		for (int i = 0; i < count; i++) {
			int offset = i * 6;
			LevelRenderer.renderLineBox(
				poseStack,
				builder,
				bounds[offset], bounds[offset + 1], bounds[offset + 2],
				bounds[offset + 3], bounds[offset + 4], bounds[offset + 5],
				red, green, blue, alpha
			);
		}
		tesselator.end();

		RenderSystem.lineWidth(1.0F);
		RenderSystem.disableBlend();
		RenderSystem.enableDepthTest();
	}
}
