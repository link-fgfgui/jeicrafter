package com.jeicrafter.api;

import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Session-bound view of the active {@link RecipeGraph}, given to {@link MaterialAnalyzer}
 * so it can prefer ingredient variations the graph can actually produce.
 */
public interface RecipeGraphAccess {

	/** Whether {@link #resolve(ItemStack)} would return a step the runner can execute. */
	boolean canProduce(ItemStack stack);

	/** Resolves a child step for {@code stack} as a missing material of the step being analysed. */
	Optional<RecipeStep> resolve(ItemStack stack);
}
