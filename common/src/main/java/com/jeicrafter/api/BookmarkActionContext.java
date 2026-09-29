package com.jeicrafter.api;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Read-only data supplied to a {@link BookmarkAction}. */
public final class BookmarkActionContext {
	private final long sessionId;
	private final int depth;
	private final IJeiRuntime jeiRuntime;
	private final LocalPlayer player;
	private final AbstractContainerMenu container;
	private final IRecipeCategory<?> recipeCategory;
	private final Object recipe;
	private final IRecipeLayoutDrawable<?> recipeLayout;
	private final ItemStack output;
	private final RecipeStep step;

	public BookmarkActionContext(
		long sessionId,
		int depth,
		IJeiRuntime jeiRuntime,
		LocalPlayer player,
		AbstractContainerMenu container,
		IRecipeCategory<?> recipeCategory,
		Object recipe,
		IRecipeLayoutDrawable<?> recipeLayout,
		ItemStack output
	) {
		this(
			sessionId,
			depth,
			jeiRuntime,
			player,
			container,
			new SimpleRecipeStep(recipeCategory, recipe, recipeLayout, output)
		);
	}

	public BookmarkActionContext(
		long sessionId,
		int depth,
		IJeiRuntime jeiRuntime,
		LocalPlayer player,
		AbstractContainerMenu container,
		RecipeStep step
	) {
		this.sessionId = sessionId;
		this.depth = depth;
		this.jeiRuntime = Objects.requireNonNull(jeiRuntime, "jeiRuntime");
		this.player = Objects.requireNonNull(player, "player");
		this.container = Objects.requireNonNull(container, "container");
		this.step = Objects.requireNonNull(step, "step");
		this.recipeCategory = step.recipeCategory();
		this.recipe = step.recipe();
		this.recipeLayout = step.recipeLayout();
		this.output = step.output();
	}

	public long sessionId() {
		return sessionId;
	}

	public int depth() {
		return depth;
	}

	public IJeiRuntime jeiRuntime() {
		return jeiRuntime;
	}

	public LocalPlayer player() {
		return player;
	}

	public AbstractContainerMenu container() {
		return container;
	}

	public IRecipeCategory<?> recipeCategory() {
		return recipeCategory;
	}

	public Object recipe() {
		return recipe;
	}

	public IRecipeLayoutDrawable<?> recipeLayout() {
		return recipeLayout;
	}

	public ItemStack output() {
		return output.copy();
	}

	/** The recipe-graph step this action (or material analyzer) is operating on. */
	public RecipeStep step() {
		return step;
	}
}
