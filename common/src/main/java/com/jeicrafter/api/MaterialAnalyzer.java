package com.jeicrafter.api;

import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * Decides whether a recipe step's inputs are already available, and which missing stacks the
 * recipe graph should produce first.
 * <p>
 * The built-in analyzer counts the player inventory plus the open crafting grid. Plugins can
 * replace that (nearby chests, a storage network, a custom crafting buffer, …).
 * <p>
 * {@link #supports} must be side-effect free because lookup may call it more than once.
 */
public interface MaterialAnalyzer {

	/**
	 * Higher-priority analyzers are considered first. The built-in analyzer uses
	 * {@link Integer#MIN_VALUE}, so any registered analyzer that supports the step wins.
	 */
	default int priority() {
		return 0;
	}

	/**
	 * Cheap filter before {@link #findMissing}. Return {@code true} only for steps this
	 * analyzer should own; the built-in inventory analyzer supports every step as a fallback.
	 * A full replacement analyzer can return {@code true} for every step.
	 */
	default boolean supports(BookmarkActionContext context) {
		return false;
	}

	/**
	 * Missing inputs that must be obtained before the matching {@link BookmarkAction} can run.
	 * An empty map means materials are sufficient.
	 * <p>
	 * Keys are representative item stacks (the runner copies them); values are the missing
	 * counts. Iteration order is the order the runner tries to satisfy — the first entry is
	 * pushed as the next graph child.
	 */
	Map<ItemStack, Integer> findMissing(BookmarkActionContext context, RecipeGraphAccess graph);
}
