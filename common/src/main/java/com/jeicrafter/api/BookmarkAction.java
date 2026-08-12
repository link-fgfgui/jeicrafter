package com.jeicrafter.api;

/**
 * An operation that can execute one recipe in a bookmark dependency chain.
 * Implementations are selected by priority after all inputs for the recipe are available.
 */
public interface BookmarkAction {

	/**
	 * Higher-priority actions are considered first. The built-in crafting-table action uses
	 * {@link Integer#MIN_VALUE}, so registered integrations can replace it when needed.
	 */
	default int priority() {
		return 0;
	}

	/** Returns whether this action can execute the recipe represented by the context. */
	boolean supports(BookmarkActionContext context);

	/**
	 * Starts one execution. The returned object belongs to this invocation and may safely keep
	 * per-execution state while JEI Crafter polls it on subsequent client ticks.
	 */
	BookmarkActionExecution start(BookmarkActionContext context);
}
