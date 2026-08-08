package com.jeicrafter.mixin;

import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the JEI bookmark list instance (BookmarkOverlay holds BookmarkList),
 * for auto-crafting to look up recipes by "representative item bookmarked as a recipe bookmark".
 */
@Mixin(value = BookmarkOverlay.class, remap = false)
public interface MixinJeiBookmarkOverlay {

	@Accessor(value = "bookmarkList", remap = false)
	BookmarkList jeicrafter$getBookmarkList();
}
