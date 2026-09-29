package com.jeicrafter.api;

import java.util.Optional;

/**
 * A recipe dependency tree. The built-in graph walks JEI recipe bookmarks; plugins can replace
 * that with their own planner.
 * <p>
 * The graph that resolves the root request is bound for the whole auto-craft session: child
 * materials are resolved through the same instance. To reuse bookmark lookup for some items,
 * delegate to {@link JeiCrafterApi#builtinRecipeGraph()}.
 * <p>
 * {@link #supports} must be side-effect free because lookup may call it more than once.
 */
public interface RecipeGraph {

	/**
	 * Higher-priority graphs are considered first. The built-in bookmark graph uses
	 * {@link Integer#MIN_VALUE}, so any registered graph that supports a request wins.
	 */
	default int priority() {
		return 0;
	}

	/**
	 * Cheap filter before {@link #resolve}. Return {@code true} only for requests this graph
	 * should own; the built-in bookmark graph supports every request as a fallback.
	 * A full replacement graph can return {@code true} for every request.
	 */
	default boolean supports(RecipeRequest request, RecipeGraphContext context) {
		return false;
	}

	/**
	 * Resolves a runnable recipe step for the request. Empty means this graph has no recipe.
	 * When {@link #supports} is true the runner binds this graph for the whole session and
	 * does not fall through to another graph — including the built-in bookmark graph.
	 * Child lookups stay on that same instance.
	 */
	Optional<RecipeStep> resolve(RecipeRequest request, RecipeGraphContext context);
}
