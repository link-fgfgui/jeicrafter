package com.jeicrafter.forge.client;

import com.jeicrafter.Constants;
import com.jeicrafter.client.JeiCrafterKeys;
import com.jeicrafter.client.RecipeWorkstationHighlight;
import com.jeicrafter.render.OutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ForgeClientEvents {

	@SubscribeEvent
	public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
		event.register(JeiCrafterKeys.CRAFT);
		event.register(JeiCrafterKeys.HIGHLIGHT_WORKSTATION);
	}

	/** Renders the workstation highlight cuboids after block entities, when active. */
	@SubscribeEvent
	public static void renderWorkstationHighlight(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
			return;
		}
		if (!RecipeWorkstationHighlight.isActive()) {
			return;
		}
		PoseStack poseStack = event.getPoseStack();
		poseStack.pushPose();
		// The render-level pose stack is not camera-translated (vanilla outlines subtract the
		// camera per-vertex); translate by -camera so vertex coords are in world space.
		net.minecraft.world.phys.Vec3 camera = event.getCamera().getPosition();
		poseStack.translate(-camera.x, -camera.y, -camera.z);
		OutlineRenderer.renderOutlines(
			poseStack,
			RecipeWorkstationHighlight.positions(),
			RecipeWorkstationHighlight.red(),
			RecipeWorkstationHighlight.green(),
			RecipeWorkstationHighlight.blue(),
			RecipeWorkstationHighlight.alpha()
		);
		poseStack.popPose();
	}
}
