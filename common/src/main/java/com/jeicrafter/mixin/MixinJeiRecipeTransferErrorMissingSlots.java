package com.jeicrafter.mixin;

import com.jeicrafter.client.AutoCraftManager;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.library.transfer.RecipeTransferErrorMissingSlots;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Colors missing recipe inputs blue when they can be crafted from a recipe bookmark. */
@Mixin(value = RecipeTransferErrorMissingSlots.class, remap = false)
public abstract class MixinJeiRecipeTransferErrorMissingSlots {
	@Unique
	private static final int jeicrafter$BOOKMARKED_RECIPE_HIGHLIGHT_COLOR = 0x660000FF;

	@Redirect(
		method = "showError",
		at = @At(
			value = "INVOKE",
			target = "Lmezz/jei/api/gui/ingredient/IRecipeSlotView;drawHighlight(Lnet/minecraft/client/gui/GuiGraphics;I)V"
		),
		remap = false
	)
	private void jeicrafter$colorCraftableMissingSlot(
		IRecipeSlotView slot,
		GuiGraphics guiGraphics,
		int originalColor
	) {
		boolean hasBookmarkedRecipe = slot.getIngredients(VanillaTypes.ITEM_STACK)
			.anyMatch(AutoCraftManager::isBookmarked);
		int color = hasBookmarkedRecipe ? jeicrafter$BOOKMARKED_RECIPE_HIGHLIGHT_COLOR : originalColor;
		slot.drawHighlight(guiGraphics, color);
	}
}
