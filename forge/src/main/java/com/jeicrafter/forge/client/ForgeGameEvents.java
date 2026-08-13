package com.jeicrafter.forge.client;

import com.jeicrafter.Constants;
import com.jeicrafter.client.RecipeWorkstationHighlight;
import com.jeicrafter.render.OutlineRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.LogicalSide;

import java.util.Set;

/**
 * Renders the workstation highlight cuboids after block entities, when active.
 * <p>
 * Registering this listener is deterministic: the mod constructor explicitly attaches it to
 * {@code MinecraftForge.EVENT_BUS} (client side only), instead of relying on {@code @Mod.EventBusSubscriber}
 * annotation scanning of a classes-directory mod file in the dev environment.
 */
public final class ForgeGameEvents {

	/** Throttle for the diagnostic render log (render ticks). */
	private static final int LOG_EVERY_RENDER_TICKS = 200;
	private static int lastLoggedRenderTick;

	private ForgeGameEvents() {
	}

	/**
	 * Right-clicking one of the highlighted workstation blocks cancels the highlight. This is the
	 * designed dismissal: the highlight stays up (even after the recipe screen closes) until the
	 * player actually interacts with one of the highlighted blocks.
	 */
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		if (event.getSide() != LogicalSide.CLIENT) {
			return;
		}
		if (RecipeWorkstationHighlight.isActive() && RecipeWorkstationHighlight.positions().contains(event.getPos())) {
			Constants.LOG.info("[Highlight] cancelled by right-click at {}", event.getPos());
			RecipeWorkstationHighlight.clear();
		}
	}

	/**
	 * Renders the workstation highlight cuboids at the end of the level pass (after particles), when
	 * active. Rendering this late guarantees nothing left in the level pipeline overwrites the boxes:
	 * at AFTER_BLOCK_ENTITIES the subsequent translucent/particle passes can still overwrite them.
	 */
	public static void renderWorkstationHighlight(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
			return;
		}
		if (!RecipeWorkstationHighlight.isActive()) {
			return;
		}
		Set<BlockPos> positions = RecipeWorkstationHighlight.positions();
		if (!positions.isEmpty() && event.getRenderTick() - lastLoggedRenderTick >= LOG_EVERY_RENDER_TICKS) {
			lastLoggedRenderTick = event.getRenderTick();
			Constants.LOG.info("[Highlight] rendering {} outline(s)", positions.size());
		}
		PoseStack poseStack = event.getPoseStack();
		poseStack.pushPose();
		// The render-level pose stack is not camera-translated (vanilla outlines subtract the
		// camera per-vertex); translate by -camera so vertex coords are in world space.
		net.minecraft.world.phys.Vec3 camera = event.getCamera().getPosition();
		poseStack.translate(-camera.x, -camera.y, -camera.z);
		// The buffer is filled with already-transformed (view-space) vertices, so the GPU model-view
		// matrix must be identity for this draw; make it deterministic regardless of leftover state.
		RenderSystem.getModelViewStack().pushPose();
		RenderSystem.getModelViewStack().setIdentity();
		RenderSystem.applyModelViewMatrix();
		OutlineRenderer.renderOutlines(
			poseStack,
			positions,
			RecipeWorkstationHighlight.red(),
			RecipeWorkstationHighlight.green(),
			RecipeWorkstationHighlight.blue(),
			RecipeWorkstationHighlight.alpha()
		);
		RenderSystem.getModelViewStack().popPose();
		RenderSystem.applyModelViewMatrix();
		poseStack.popPose();
	}
}
