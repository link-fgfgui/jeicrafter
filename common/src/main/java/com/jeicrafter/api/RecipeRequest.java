package com.jeicrafter.api;

import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Optional;

/**
 * A lookup into a {@link RecipeGraph}: either an item the player asked to produce, a specific
 * JEI recipe (bookmark-bar click), or a missing material of a parent step.
 */
public final class RecipeRequest {
	private final ItemStack target;
	private final IRecipeCategory<?> recipeCategory;
	private final Object recipe;
	private final RecipeStep parent;

	private RecipeRequest(ItemStack target, IRecipeCategory<?> recipeCategory, Object recipe, RecipeStep parent) {
		this.target = Objects.requireNonNull(target, "target").copy();
		this.recipeCategory = recipeCategory;
		this.recipe = recipe;
		this.parent = parent;
	}

	/** Resolve a recipe that produces {@code target}. */
	public static RecipeRequest forItem(ItemStack target) {
		return new RecipeRequest(target, null, null, null);
	}

	/**
	 * Resolve a specific JEI recipe. Used when the player clicks a recipe bookmark so the graph
	 * can honour that recipe instead of picking an arbitrary producer of the same output.
	 */
	public static RecipeRequest forRecipe(ItemStack output, IRecipeCategory<?> recipeCategory, Object recipe) {
		return new RecipeRequest(
			output,
			Objects.requireNonNull(recipeCategory, "recipeCategory"),
			Objects.requireNonNull(recipe, "recipe"),
			null
		);
	}

	/** Resolve a child recipe that should produce a missing material of {@code parent}. */
	public static RecipeRequest forDependency(ItemStack material, RecipeStep parent) {
		return new RecipeRequest(material, null, null, Objects.requireNonNull(parent, "parent"));
	}

	public ItemStack target() {
		return target.copy();
	}

	/** Present when the request came from a specific JEI recipe (bookmark click). */
	public Optional<IRecipeCategory<?>> recipeCategory() {
		return Optional.ofNullable(recipeCategory);
	}

	/** Present when the request came from a specific JEI recipe (bookmark click). */
	public Optional<Object> recipe() {
		return Optional.ofNullable(recipe);
	}

	/** Present when resolving a missing material of an already-selected parent step. */
	public Optional<RecipeStep> parent() {
		return Optional.ofNullable(parent);
	}
}
