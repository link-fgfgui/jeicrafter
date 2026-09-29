package com.jeicrafter.mixin;

import com.jeicrafter.client.JeiCrafterKeys;
import com.jeicrafter.client.RecipeWorkstationHighlight;
import com.jeicrafter.config.JeiCrafterConfig;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.gui.recipes.IRecipeGuiLogic;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Integrates the workstation-highlight feature into JEI's recipe screen.
 * <p>
 * Exposes the private {@link RecipesGui#logic} so the current recipe category (and its workstations)
 * can be read, and intercepts the configured highlight key in {@link RecipesGui#keyPressed} to
 * start the scan. The highlight stays up after the screen closes and is dismissed by right-clicking
 * one of the highlighted blocks.
 * <p>
 * Only non-crafting categories trigger the highlight; the crafting category's workstation is a
 * crafting table, which is out of scope for this feature.
 */
@Mixin(value = RecipesGui.class, remap = false)
public abstract class MixinJeiRecipesGui {

	@Shadow
	@Final
	@SuppressWarnings("unused")
	private IRecipeGuiLogic logic;

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeicrafter$handleHighlightKey(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (!JeiCrafterKeys.isHighlightKey(keyCode, scanCode) || !JeiCrafterConfig.enableWorkstationHighlight()) {
			return;
		}
		if (jeicrafter$tryHighlight()) {
			cir.setReturnValue(true);
		}
	}

	@Unique
	private boolean jeicrafter$tryHighlight() {
		IRecipeCategory<?> category = logic.getSelectedRecipeCategory();
		if (category == null || RecipeTypes.CRAFTING.equals(category.getRecipeType())) {
			// Crafting recipes use the (bookmark-driven) crafting table; the highlight is for
			// workstation-based (non-crafting) recipes only.
			return false;
		}
		List<ItemStack> workstations = logic.getRecipeCatalysts(category)
			.map(ITypedIngredient::getIngredient)
			.filter(ItemStack.class::isInstance)
			.map(ItemStack.class::cast)
			.toList();
		if (workstations.isEmpty()) {
			return false;
		}
		RecipeWorkstationHighlight.trigger(workstations, category);
		return true;
	}
}
