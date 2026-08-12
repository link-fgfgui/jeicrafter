package com.jeicrafter.api;

/** One stateful invocation of a {@link BookmarkAction}. */
public interface BookmarkActionExecution {

	/**
	 * Called once per client tick until a terminal result is returned. Return {@code SUCCESS} only after
	 * consumed inputs and produced outputs are visible in the client state, because dependency analysis
	 * resumes on the next tick.
	 */
	BookmarkActionResult tick();

	/** Called when the whole recursive request is aborted while this execution is active. */
	default void cancel() {
	}
}
