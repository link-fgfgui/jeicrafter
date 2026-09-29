package com.jeicrafter.client;

import com.jeicrafter.api.RecipeGraph;
import com.jeicrafter.api.RecipeGraphContext;
import com.jeicrafter.api.RecipeRequest;
import com.jeicrafter.api.RecipeStep;
import com.jeicrafter.api.SimpleRecipeStep;
import com.jeicrafter.mixin.MixinJeiBookmarkOverlay;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.library.plugins.jei.tags.ITagInfoRecipe;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in recipe tree: JEI recipe bookmarks, with tag bookmarks unwrapped into a crafting recipe.
 */
final class BuiltinRecipeGraph implements RecipeGraph {
	static final BuiltinRecipeGraph INSTANCE = new BuiltinRecipeGraph();

	private BuiltinRecipeGraph() {
	}

	@Override
	public int priority() {
		return Integer.MIN_VALUE;
	}

	@Override
	public boolean supports(RecipeRequest request, RecipeGraphContext context) {
		return true;
	}

	@Override
	public Optional<RecipeStep> resolve(RecipeRequest request, RecipeGraphContext context) {
		IJeiRuntime runtime = context.jeiRuntime();
		LocalPlayer player = context.player().orElse(null);
		if (request.recipe().isPresent() && request.recipeCategory().isPresent()) {
			Object recipe = request.recipe().get();
			IRecipeCategory<?> category = request.recipeCategory().get();
			if (recipe instanceof ITagInfoRecipe) {
				return resolveCraftableStep(request.target(), runtime, player);
			}
			return createExecutableStep(category, recipe, request.target(), runtime, player);
		}
		return findRunnableStep(request.target(), runtime, player);
	}

	static ItemStack outputOf(RecipeBookmark<?, ?> bookmark, IJeiRuntime runtime) {
		Optional<ItemStack> bookmarkOutput = bookmark.getRecipeOutput().getIngredient(VanillaTypes.ITEM_STACK);
		if (bookmarkOutput.isPresent()) {
			return bookmarkOutput.get().copy();
		}
		IRecipeLayoutDrawable<?> layout = createLayout(bookmark.getRecipeCategory(), bookmark.getRecipe(), runtime);
		if (layout == null) {
			return ItemStack.EMPTY;
		}
		return firstOutput(layout);
	}

	private static Optional<RecipeStep> findRunnableStep(ItemStack target, IJeiRuntime runtime, LocalPlayer player) {
		for (RecipeBookmark<?, ?> bookmark : findMatchingBookmarks(target, runtime)) {
			Optional<RecipeStep> step;
			if (bookmark.getRecipe() instanceof ITagInfoRecipe) {
				step = resolveCraftableStep(target, runtime, player);
			} else {
				step = createExecutableStep(bookmark.getRecipeCategory(), bookmark.getRecipe(), outputOf(bookmark, runtime), runtime, player);
			}
			if (step.isPresent()) {
				return step;
			}
		}
		return Optional.empty();
	}

	/**
	 * Unwraps a tag bookmark into the first crafting recipe whose output matches {@code item}.
	 */
	private static Optional<RecipeStep> resolveCraftableStep(ItemStack item, IJeiRuntime runtime, LocalPlayer player) {
		if (item.isEmpty()) {
			return Optional.empty();
		}
		IFocus<ItemStack> focus = runtime.getJeiHelpers().getFocusFactory()
			.createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, item);
		IRecipeManager recipeManager = runtime.getRecipeManager();
		IRecipeCategory<CraftingRecipe> category = recipeManager.getRecipeCategory(RecipeTypes.CRAFTING);
		return recipeManager.createRecipeLookup(RecipeTypes.CRAFTING)
			.limitFocus(List.of(focus))
			.get()
			.map(recipe -> createExecutableStep(category, recipe, item, runtime, player).orElse(null))
			.filter(Objects::nonNull)
			.findFirst();
	}

	private static Optional<RecipeStep> createExecutableStep(
		IRecipeCategory<?> category,
		Object recipe,
		ItemStack output,
		IJeiRuntime runtime,
		LocalPlayer player
	) {
		IRecipeLayoutDrawable<?> layout = createLayout(category, recipe, runtime);
		if (layout == null) {
			return Optional.empty();
		}
		ItemStack resolvedOutput = output.isEmpty() ? firstOutput(layout) : output.copy();
		RecipeStep step = new SimpleRecipeStep(category, recipe, layout, resolvedOutput);
		return AutoCraftManager.hasAction(step, player) ? Optional.of(step) : Optional.empty();
	}

	private static IRecipeLayoutDrawable<?> createLayout(IRecipeCategory<?> category, Object recipe, IJeiRuntime runtime) {
		@SuppressWarnings({"unchecked", "rawtypes"})
		Optional<IRecipeLayoutDrawable<?>> layout = (Optional) runtime.getRecipeManager().createRecipeLayoutDrawable(
			(IRecipeCategory) category,
			recipe,
			runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()
		);
		return layout.orElse(null);
	}

	private static ItemStack firstOutput(IRecipeLayoutDrawable<?> layout) {
		return layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT).stream()
			.findFirst()
			.flatMap(IRecipeSlotView::getDisplayedItemStack)
			.map(ItemStack::copy)
			.orElse(ItemStack.EMPTY);
	}

	private static List<RecipeBookmark<?, ?>> findMatchingBookmarks(ItemStack target, IJeiRuntime runtime) {
		if (target.isEmpty()) {
			return List.of();
		}
		List<RecipeBookmark<?, ?>> matches = new ArrayList<>();
		BookmarkList bookmarkList = ((MixinJeiBookmarkOverlay) runtime.getBookmarkOverlay()).jeicrafter$getBookmarkList();
		IStackHelper stackHelper = runtime.getJeiHelpers().getStackHelper();
		for (IElement<?> element : bookmarkList.getElements()) {
			Optional<IBookmark> bookmark = element.getBookmark();
			if (bookmark.isPresent() && bookmark.get() instanceof RecipeBookmark<?, ?> recipeBookmark) {
				Optional<ItemStack> output = recipeBookmark.getRecipeOutput().getIngredient(VanillaTypes.ITEM_STACK);
				if (output.isPresent() && stackHelper.isEquivalent(output.get(), target, UidContext.Ingredient)) {
					matches.add(recipeBookmark);
				}
			}
		}
		return matches;
	}
}
