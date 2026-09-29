# Bookmark action API

The client-side API is in `com.jeicrafter.api`. Auto-craft is three independently
replaceable pieces:

1. A **recipe graph** that turns a request (an item, a specific JEI recipe, or a
   missing material) into a `RecipeStep`.
2. A **material analyzer** that decides whether that step's inputs are already
   available, and which stacks the graph should produce first if they are not.
3. A **bookmark action** that executes a step once its inputs are available.

The runner binds the graph that resolved the **root** request for the whole
session: child materials are resolved through that same instance. Public API
types only expose Minecraft and JEI API classes; callers do not need JEI's
internal `RecipeBookmark` type.

Two built-in actions are always registered: the crafting-table action (for
`RecipeTypes.CRAFTING`) and the workstation action (for any other recipe
category that has a JEI recipe catalyst). The workstation action reads **all** of
the recipe's catalyst blocks. When the corresponding workstation is **already
open** (its container belongs to one of those blocks) or the open container has a
**valid JEI recipe transfer handler**, the action starts directly at the transfer
step; otherwise (and when `autoOpenWorkstation` is enabled) it first opens the
nearest matching workstation block in the loaded world and then continues into
the same step. The transfer step inserts the recipe's required inputs into the
open workstation — through JEI's standard recipe transfer when a handler is
registered, otherwise by split-clicking exactly the required input counts into
the workstation's empty slots — and then stops. Inserting follows the
insert-and-stop design for non-crafting recipes: the mod never waits for the
machine to produce and never extracts the output. Whether the insert happens is
controlled by the `autoTransferItems` config option (when disabled the action
only opens/acknowledges the workstation and stops). The path is terminal (it ends
the auto-craft session), and the open-and-hand-over part is gated by the
`autoOpenWorkstation` config option.

The built-in recipe graph walks JEI recipe bookmarks (tag bookmarks unwrap into a
crafting recipe). The built-in material analyzer counts the player inventory plus
the open crafting grid. Plugins can replace either, or both.

## Starting a request

Call this on the Minecraft client thread:

```java
ItemStack target = ...;
if (JeiCrafterApi.canRun(target)) {
	JeiCrafterApi.run(target);
}
```

Only one request runs at a time. `JeiCrafterApi.isRunning()` exposes that state
and `JeiCrafterApi.cancel()` cancels the active action and dependency chain.

To honour a specific JEI recipe (instead of "any producer of this output"):

```java
JeiCrafterApi.run(RecipeRequest.forRecipe(output, recipeCategory, recipe));
```

To start from a step the plugin already resolved, binding that graph for child
lookups:

```java
JeiCrafterApi.run(rootStep, myGraph);
```

## Replacing the recipe tree

Register a `RecipeGraph` during client initialization. Higher `priority()`
values win. The first graph whose `supports` is true **owns** the request: the
runner does not fall through to another graph (including the built-in bookmark
graph), and every missing-material lookup in that session goes to the same
instance.

`supports` must be side-effect free because lookup may call it more than once.
Return `true` for every request to fully replace the bookmark tree; return
`true` only for selected items to override those roots while leaving other
bookmark chains on the built-in graph.

To reuse bookmark lookup for some items while owning the session (so children
also come back to the plugin), delegate:

```java
public final class PlannerGraph implements RecipeGraph {
	@Override
	public boolean supports(RecipeRequest request, RecipeGraphContext context) {
		return true; // own every request, including children
	}

	@Override
	public Optional<RecipeStep> resolve(RecipeRequest request, RecipeGraphContext context) {
		Optional<RecipeStep> planned = plan(request, context);
		if (planned.isPresent()) {
			return planned;
		}
		return JeiCrafterApi.builtinRecipeGraph().resolve(request, context);
	}

	private Optional<RecipeStep> plan(RecipeRequest request, RecipeGraphContext context) {
		// Return SimpleRecipeStep for recipes this planner knows.
		return Optional.empty();
	}
}

PlannerGraph graph = new PlannerGraph();
JeiCrafterApi.registerRecipeGraph(graph);
```

Keep the instance if it may need to be removed later:

```java
JeiCrafterApi.unregisterRecipeGraph(graph);
```

A `RecipeStep` is a JEI recipe category + recipe object + layout + output.
`SimpleRecipeStep` is the convenient implementation. Override
`RecipeStep.identity()` when two instances represent the same recipe — the
runner uses identity for cycle detection on the session stack.

## Replacing the material check

Register a `MaterialAnalyzer` during client initialization. Higher
`priority()` values win. The first analyzer whose `supports` is true owns
**that step**; other steps still use the built-in inventory analyzer (or another
plugin analyzer). Unlike the recipe graph, analyzers are selected per step, not
bound for the session.

Return an empty map from `findMissing` when materials are sufficient. Otherwise
return representative stacks → missing counts; iteration order is the order the
runner tries to satisfy (the first entry is pushed as the next graph child).
`RecipeGraphAccess` tells the analyzer which variations the session graph can
actually produce, so OR-ingredients can prefer a craftable variant.

```java
public final class StorageAnalyzer implements MaterialAnalyzer {
	@Override
	public boolean supports(BookmarkActionContext context) {
		return true; // replace the inventory check for every step
	}

	@Override
	public Map<ItemStack, Integer> findMissing(BookmarkActionContext context, RecipeGraphAccess graph) {
		Map<ItemStack, Integer> fromInventory =
			JeiCrafterApi.builtinMaterialAnalyzer().findMissing(context, graph);
		if (fromInventory.isEmpty()) {
			return fromInventory;
		}
		// Subtract stacks that are already in a nearby storage network, or
		// return fromInventory unchanged to keep default behaviour.
		return fromInventory;
	}
}

StorageAnalyzer analyzer = new StorageAnalyzer();
JeiCrafterApi.registerMaterialAnalyzer(analyzer);
```

A full replacement that ignores the player inventory (for example a crafting
buffer the plugin already filled) just returns its own missing map and does not
delegate.

## Registering an action

Register a `BookmarkAction` during client initialization:

```java
public final class MachineBookmarkAction implements BookmarkAction {
	@Override
	public boolean supports(BookmarkActionContext context) {
		return context.recipeCategory().getRecipeType().equals(MyRecipeTypes.MACHINE);
	}

	@Override
	public BookmarkActionExecution start(BookmarkActionContext context) {
		MyRecipe recipe = (MyRecipe) context.recipe();
		long requestId = MyPackets.startMachineAction(recipe);

		return new BookmarkActionExecution() {
			@Override
			public BookmarkActionResult tick() {
				if (MyPackets.failed(requestId)) {
					return BookmarkActionResult.FAILURE;
				}
				return MyPackets.completed(requestId)
					? BookmarkActionResult.SUCCESS
					: BookmarkActionResult.RUNNING;
			}

			@Override
			public void cancel() {
				MyPackets.cancel(requestId);
			}
		};
	}
}

MachineBookmarkAction action = new MachineBookmarkAction();
JeiCrafterApi.registerAction(action);
```

`supports` must be side-effect free because dependency lookup may call it more
than once. Return `SUCCESS` only after the produced item is visible in the
client inventory; the core re-analyzes the parent step on the next tick. Higher
`priority()` values win when multiple actions support the same recipe. Every
registered action takes precedence over the built-in crafting-table action.

The context also exposes `context.step()` when the action needs the graph node
(custom identity, extra plugin data on a `RecipeStep` implementation, …).

Keep the action instance if it may need to be removed later:

```java
JeiCrafterApi.unregisterAction(action);
```
