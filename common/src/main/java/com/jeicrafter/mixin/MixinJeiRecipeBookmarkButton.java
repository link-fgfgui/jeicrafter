package com.jeicrafter.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.Internal;
import mezz.jei.common.transfer.RecipeTransferUtil;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.recipes.RecipeBookmarkButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shows JEI's recipe-transfer error overlay while the recipe bookmark button is hovered. */
@Mixin(value = RecipeBookmarkButton.class, remap = false)
public abstract class MixinJeiRecipeBookmarkButton implements RecipeBookmarkButtonExtension {

	@Unique
	private IRecipeLayoutDrawable<?> jeicrafter$recipeLayout;

	@Override
	public void jeicrafter$setRecipeLayout(IRecipeLayoutDrawable<?> recipeLayout) {
		this.jeicrafter$recipeLayout = recipeLayout;
	}

	@Inject(method = "create", at = @At("RETURN"), remap = false)
	private static void jeicrafter$captureRecipeLayout(
		IRecipeLayoutDrawable<?> recipeLayout,
		IIngredientManager ingredientManager,
		BookmarkList bookmarks,
		CallbackInfoReturnable<RecipeBookmarkButton> cir
	) {
		RecipeBookmarkButton button = cir.getReturnValue();
		((RecipeBookmarkButtonExtension) button).jeicrafter$setRecipeLayout(recipeLayout);
	}

	@Inject(method = "draw", at = @At("TAIL"), remap = false)
	private void jeicrafter$drawTransferPreview(
		GuiGraphics guiGraphics,
		int mouseX,
		int mouseY,
		float partialTicks,
		CallbackInfo ci
	) {
		RecipeBookmarkButton self = (RecipeBookmarkButton) (Object) this;
		if (jeicrafter$recipeLayout == null || !self.isMouseOver(mouseX, mouseY)) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		Screen screen = minecraft.screen;
		if (player == null || !(screen instanceof AbstractContainerScreen<?> containerScreen)) {
			return;
		}

		AbstractContainerMenu container = containerScreen.getMenu();
		IRecipeTransferManager transferManager = Internal.getJeiRuntime().getRecipeTransferManager();
		IRecipeTransferError transferError = RecipeTransferUtil.getTransferRecipeError(
			transferManager,
			container,
			jeicrafter$recipeLayout,
			player
		).orElse(null);
		if (transferError == null) {
			return;
		}

		Rect2i recipeRect = jeicrafter$recipeLayout.getRect();
		PoseStack poseStack = guiGraphics.pose();
		poseStack.pushPose();
		try {
			transferError.showError(
				guiGraphics,
				mouseX,
				mouseY,
				jeicrafter$recipeLayout.getRecipeSlotsView(),
				recipeRect.getX(),
				recipeRect.getY()
			);
		} finally {
			poseStack.popPose();
		}
	}
}
