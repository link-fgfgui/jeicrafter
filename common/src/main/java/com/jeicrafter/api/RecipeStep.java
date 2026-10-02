package com.jeicrafter.api;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.Objects;

/**
 * One recipe in a dependency graph. Plugin graphs return these instead of JEI recipe bookmarks.
 * <p>
 * Public API types only expose Minecraft and JEI API classes.
 */
public interface RecipeStep {

	IRecipeCategory<?> recipeCategory();

	/** The JEI recipe object for {@link #recipeCategory()}. */
	Object recipe();

	IRecipeLayoutDrawable<?> recipeLayout();

	/** Representative output of this step. The returned stack must not be mutated. */
	ItemStack output();

	/**
	 * Identity used for cycle detection on the session stack. Two steps for the same recipe
	 * should compare equal even when they are different instances.
	 */
	default Object identity() {
		return new Identity(recipeCategory(), recipe());
	}

	/** Value identity: recipe type plus the recipe key/instance. */
	final class Identity {
		private final Object recipeType;
		private final Object recipeKey;

		public Identity(IRecipeCategory<?> recipeCategory, Object recipe) {
			this.recipeType = Objects.requireNonNull(recipeCategory, "recipeCategory").getRecipeType();
			Objects.requireNonNull(recipe, "recipe");
			this.recipeKey = recipe instanceof Recipe<?> mcRecipe ? mcRecipe.getId() : recipe;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Identity identity
				&& Objects.equals(recipeType, identity.recipeType)
				&& Objects.equals(recipeKey, identity.recipeKey);
		}

		@Override
		public int hashCode() {
			return Objects.hash(recipeType, recipeKey);
		}
	}
}
