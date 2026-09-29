package com.jeicrafter.api;

import com.jeicrafter.client.AutoCraftManager;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Public client-side entry points for recipe-graph-driven recursive actions. */
public final class JeiCrafterApi {
	private JeiCrafterApi() {
	}

	/**
	 * Registers an action integration. Register during client initialization.
	 * Actions with higher {@link BookmarkAction#priority()} values are selected first.
	 */
	public static void registerAction(BookmarkAction action) {
		AutoCraftManager.registerAction(Objects.requireNonNull(action, "action"));
	}

	/** Removes a previously registered action instance. */
	public static boolean unregisterAction(BookmarkAction action) {
		return AutoCraftManager.unregisterAction(Objects.requireNonNull(action, "action"));
	}

	/**
	 * Registers a recipe dependency graph. Register during client initialization.
	 * Graphs with higher {@link RecipeGraph#priority()} values are selected first. The first
	 * graph whose {@link RecipeGraph#supports} is true owns the request for the whole session
	 * and replaces the built-in JEI-bookmark graph for that request.
	 */
	public static void registerRecipeGraph(RecipeGraph graph) {
		AutoCraftManager.registerRecipeGraph(Objects.requireNonNull(graph, "graph"));
	}

	/** Removes a previously registered recipe graph instance. */
	public static boolean unregisterRecipeGraph(RecipeGraph graph) {
		return AutoCraftManager.unregisterRecipeGraph(Objects.requireNonNull(graph, "graph"));
	}

	/**
	 * Registers a material analyzer. Register during client initialization.
	 * Analyzers with higher {@link MaterialAnalyzer#priority()} values are selected first.
	 * The first analyzer whose {@link MaterialAnalyzer#supports} is true owns the step;
	 * otherwise the built-in inventory analyzer is used.
	 */
	public static void registerMaterialAnalyzer(MaterialAnalyzer analyzer) {
		AutoCraftManager.registerMaterialAnalyzer(Objects.requireNonNull(analyzer, "analyzer"));
	}

	/** Removes a previously registered material analyzer instance. */
	public static boolean unregisterMaterialAnalyzer(MaterialAnalyzer analyzer) {
		return AutoCraftManager.unregisterMaterialAnalyzer(Objects.requireNonNull(analyzer, "analyzer"));
	}

	/**
	 * The built-in JEI-bookmark graph. Plugin graphs that only override some items can
	 * delegate remaining lookups here.
	 */
	public static RecipeGraph builtinRecipeGraph() {
		return AutoCraftManager.builtinRecipeGraph();
	}

	/**
	 * The built-in inventory analyzer (player inventory + crafting grid). Plugin analyzers
	 * can delegate to this for steps they do not handle themselves.
	 */
	public static MaterialAnalyzer builtinMaterialAnalyzer() {
		return AutoCraftManager.builtinMaterialAnalyzer();
	}

	/**
	 * Finds a recipe that produces the target and starts a recursive request.
	 *
	 * @return {@code true} when the click/request was handled, including when another request is already running;
	 * {@code false} when no runnable recipe exists for the target
	 */
	public static boolean run(ItemStack target) {
		return AutoCraftManager.tryCraft(target);
	}

	/**
	 * Starts a recursive request from an explicit lookup (item, specific JEI recipe, or
	 * parent-step dependency).
	 */
	public static boolean run(RecipeRequest request) {
		return AutoCraftManager.tryCraft(Objects.requireNonNull(request, "request"));
	}

	/**
	 * Starts a recursive request from a pre-resolved root step, binding {@code graph} for
	 * child material lookups. Use this when the plugin already picked the root recipe.
	 */
	public static boolean run(RecipeStep root, RecipeGraph graph) {
		return AutoCraftManager.tryCraft(
			Objects.requireNonNull(root, "root"),
			Objects.requireNonNull(graph, "graph")
		);
	}

	/** Returns whether a registered recipe graph can produce the target with a registered action. */
	public static boolean canRun(ItemStack target) {
		return AutoCraftManager.isRunnable(target);
	}

	/** Returns whether a registered recipe graph can resolve the request to a runnable step. */
	public static boolean canRun(RecipeRequest request) {
		return AutoCraftManager.isRunnable(Objects.requireNonNull(request, "request"));
	}

	public static boolean isRunning() {
		return AutoCraftManager.isRunning();
	}

	public static void cancel() {
		AutoCraftManager.cancel();
	}
}
