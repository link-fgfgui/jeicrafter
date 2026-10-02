package com.jeicrafter.mixin;

import com.jeicrafter.client.IBookmarkListAccessor;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.config.IBookmarkConfig;
import net.minecraft.core.RegistryAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Mixin on JEI's {@link BookmarkList} to allow retrieving and replacing the entire bookmark list.
 */
@Mixin(value = BookmarkList.class, remap = false)
public abstract class MixinJeiBookmarkList implements IBookmarkListAccessor {

	@Shadow
	@Final
	private List<IBookmark> bookmarksList;

	@Shadow
	@Final
	private Set<IBookmark> bookmarksSet;

	@Shadow
	@Final
	private IRecipeManager recipeManager;

	@Shadow
	@Final
	private IFocusFactory focusFactory;

	@Shadow
	@Final
	private IIngredientManager ingredientManager;

	@Shadow
	@Final
	private RegistryAccess registryAccess;

	@Shadow
	@Final
	private IBookmarkConfig bookmarkConfig;

	@Shadow
	@Final
	private IGuiHelper guiHelper;

	@Shadow
	public abstract void notifyListenersOfChange();

	@Override
	public List<IBookmark> jeicrafter$getBookmarksList() {
		return new ArrayList<>(this.bookmarksList);
	}

	@Override
	public void jeicrafter$setBookmarksList(List<IBookmark> newBookmarks) {
		this.bookmarksList.clear();
		this.bookmarksList.addAll(newBookmarks);
		this.bookmarksSet.clear();
		this.bookmarksSet.addAll(newBookmarks);
		this.notifyListenersOfChange();
		this.bookmarkConfig.saveBookmarks(
			this.recipeManager,
			this.focusFactory,
			this.guiHelper,
			this.ingredientManager,
			this.registryAccess,
			this.bookmarksList
		);
	}
}
