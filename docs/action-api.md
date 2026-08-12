# Bookmark action API

The client-side API is in `com.jeicrafter.api`. It resolves the recipe bookmark graph, crafts
missing bookmarked inputs first, and invokes one registered action when a recipe's inputs are
available. Public API types only expose Minecraft and JEI API classes; callers do not need JEI's
internal `RecipeBookmark` type.

Two built-in actions are always registered: the crafting-table action (for `RecipeTypes.CRAFTING`)
and the workstation action (for any other recipe category that has a JEI recipe catalyst). The
workstation action opens the nearest matching workstation block in the loaded world, transfers the
recipe's required inputs (and fuel for furnace-family menus), waits for the output, and extracts it.
Both the "open workstation" and "transfer items" steps are toggleable via the mod's config file.

## Starting a request

Call this on the Minecraft client thread:

```java
ItemStack target = ...;
if (JeiCrafterApi.canRun(target)) {
	JeiCrafterApi.run(target);
}
```

Only one request runs at a time. `JeiCrafterApi.isRunning()` exposes that state and
`JeiCrafterApi.cancel()` cancels the active action and dependency chain.

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

`supports` must be side-effect free because dependency lookup may call it more than once. Return
`SUCCESS` only after the produced item is visible in the client inventory; the core re-analyzes the
parent bookmark on the next tick. Higher `priority()` values win when multiple actions support the
same recipe. Every registered action takes precedence over the built-in crafting-table action.

Keep the action instance if it may need to be removed later:

```java
JeiCrafterApi.unregisterAction(action);
```
