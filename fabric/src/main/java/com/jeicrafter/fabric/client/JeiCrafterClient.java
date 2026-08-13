package com.jeicrafter.fabric.client;

import com.jeicrafter.Constants;
import com.jeicrafter.client.JeiCrafterKeys;
import com.jeicrafter.client.RecipeWorkstationHighlight;
import com.jeicrafter.render.OutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionResult;

public class JeiCrafterClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		KeyBindingHelper.registerKeyBinding(JeiCrafterKeys.CRAFT);
		KeyBindingHelper.registerKeyBinding(JeiCrafterKeys.HIGHLIGHT_WORKSTATION);

		// The render pose stack is not camera-translated; translate by -camera so vertex coords
		// are in world space (mirrors the clientcommands reference implementation).
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			if (!RecipeWorkstationHighlight.isActive()) {
				return;
			}
			PoseStack poseStack = context.matrixStack();
			poseStack.pushPose();
			net.minecraft.world.phys.Vec3 camera = context.camera().getPosition();
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
		});

		// Right-clicking one of the highlighted workstation blocks dismisses the highlight.
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (player instanceof LocalPlayer
				&& RecipeWorkstationHighlight.isActive()
				&& RecipeWorkstationHighlight.positions().contains(hitResult.getBlockPos())) {
				Constants.LOG.info("[Highlight] cancelled by right-click at {}", hitResult.getBlockPos());
				RecipeWorkstationHighlight.clear();
			}
			return InteractionResult.PASS;
		});
	}
}
