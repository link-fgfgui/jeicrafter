package com.jeicrafter.api;

import com.jeicrafter.client.AutoCraftManager;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Public client-side entry points for bookmark-driven recursive actions. */
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
	 * Finds the target's recipe bookmark and starts a recursive request.
	 *
	 * @return {@code true} when the click/request was handled, including when another request is already running;
	 * {@code false} when no runnable recipe bookmark exists for the target
	 */
	public static boolean run(ItemStack target) {
		return AutoCraftManager.tryCraft(target);
	}

	/** Returns whether the target currently has a recipe bookmark backed by a registered action. */
	public static boolean canRun(ItemStack target) {
		return AutoCraftManager.isRunnable(target);
	}

	public static boolean isRunning() {
		return AutoCraftManager.isRunning();
	}

	public static void cancel() {
		AutoCraftManager.cancel();
	}
}
