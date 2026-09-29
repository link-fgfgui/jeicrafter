package com.jeicrafter.api;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Convenient {@link RecipeStep} implementation for plugin graphs. */
public final class SimpleRecipeStep implements RecipeStep {
	private final IRecipeCategory<?> recipeCategory;
	private final Object recipe;
	private final IRecipeLayoutDrawable<?> recipeLayout;
	private final ItemStack output;
	private final Object identity;

	public SimpleRecipeStep(
		IRecipeCategory<?> recipeCategory,
		Object recipe,
		IRecipeLayoutDrawable<?> recipeLayout,
		ItemStack output
	) {
		this(recipeCategory, recipe, recipeLayout, output, null);
	}

	public SimpleRecipeStep(
		IRecipeCategory<?> recipeCategory,
		Object recipe,
		IRecipeLayoutDrawable<?> recipeLayout,
		ItemStack output,
		Object identity
	) {
		this.recipeCategory = Objects.requireNonNull(recipeCategory, "recipeCategory");
		this.recipe = Objects.requireNonNull(recipe, "recipe");
		this.recipeLayout = Objects.requireNonNull(recipeLayout, "recipeLayout");
		this.output = Objects.requireNonNull(output, "output").copy();
		this.identity = identity != null ? identity : new Identity(recipeCategory, recipe);
	}

	@Override
	public IRecipeCategory<?> recipeCategory() {
		return recipeCategory;
	}

	@Override
	public Object recipe() {
		return recipe;
	}

	@Override
	public IRecipeLayoutDrawable<?> recipeLayout() {
		return recipeLayout;
	}

	@Override
	public ItemStack output() {
		return output.copy();
	}

	@Override
	public Object identity() {
		return identity;
	}
}
