package com.jeicrafter.client;

import mezz.jei.gui.bookmarks.IBookmark;

import java.util.List;

/**
 * Accessor interface duck-typed onto JEI's {@link mezz.jei.gui.bookmarks.BookmarkList} via mixin.
 */
public interface IBookmarkListAccessor {

	List<IBookmark> jeicrafter$getBookmarksList();

	void jeicrafter$setBookmarksList(List<IBookmark> newBookmarks);
}
