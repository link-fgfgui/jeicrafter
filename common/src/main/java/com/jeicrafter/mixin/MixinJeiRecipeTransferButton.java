package com.jeicrafter.mixin;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.gui.recipes.RecipeTransferButton;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Lets JEI's transfer preview treat the recipe bookmark button as part of its hover area. */
@Mixin(value = RecipeTransferButton.class, remap = false)
public abstract class MixinJeiRecipeTransferButton {

	@Shadow
	@Final
	private IRecipeLayoutDrawable<?> recipeLayout;

	@Redirect(
		method = "draw",
		at = @At(
			value = "INVOKE",
			target = "Lmezz/jei/gui/recipes/RecipeTransferButton;isMouseOver(DD)Z"
		),
		remap = false
	)
	private boolean jeicrafter$includeBookmarkButtonHover(
		RecipeTransferButton transferButton,
		double mouseX,
		double mouseY
	) {
		if (transferButton.isMouseOver(mouseX, mouseY)) {
			return true;
		}

		Rect2i layoutArea = recipeLayout.getRect();
		Rect2i bookmarkArea = recipeLayout.getRecipeBookmarkButtonArea();
		int bookmarkX = layoutArea.getX() + bookmarkArea.getX();
		int bookmarkY = layoutArea.getY() + bookmarkArea.getY();
		return mouseX >= bookmarkX && mouseY >= bookmarkY
			&& mouseX < bookmarkX + bookmarkArea.getWidth()
			&& mouseY < bookmarkY + bookmarkArea.getHeight();
	}
}
