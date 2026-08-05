package com.jeicrafter.mixin;

import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 JEI 书签列表实例(BookmarkOverlay 持有 BookmarkList),
 * 供自动合成按"被收藏为 recipe bookmark 的代表物品"查找配方。
 */
@Mixin(value = BookmarkOverlay.class, remap = false)
public interface MixinJeiBookmarkOverlay {

	@Accessor(value = "bookmarkList", remap = false)
	BookmarkList jeicrafter$getBookmarkList();
}
