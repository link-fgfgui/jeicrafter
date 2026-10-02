package com.jeicrafter.mixin;

import mezz.jei.gui.recipes.IRecipeGuiLogic;
import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes JEI's internal {@link IRecipeGuiLogic} on {@link RecipesGui} to read the active recipe category
 * and its workstations for workstation highlighting.
 */
@Mixin(value = RecipesGui.class, remap = false)
public interface MixinJeiRecipesGui {

	@Accessor(value = "logic", remap = false)
	IRecipeGuiLogic jeicrafter$getLogic();
}
