package com.jeicrafter.api;

import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.Objects;
import java.util.Optional;

/** Client state supplied to a {@link RecipeGraph} while resolving a {@link RecipeRequest}. */
public final class RecipeGraphContext {
	private final IJeiRuntime jeiRuntime;
	private final LocalPlayer player;
	private final AbstractContainerMenu container;

	public RecipeGraphContext(IJeiRuntime jeiRuntime, LocalPlayer player, AbstractContainerMenu container) {
		this.jeiRuntime = Objects.requireNonNull(jeiRuntime, "jeiRuntime");
		this.player = player;
		this.container = container;
	}

	public IJeiRuntime jeiRuntime() {
		return jeiRuntime;
	}

	/**
	 * The client player. Empty only for speculative lookups such as slot highlighting when the
	 * player is not yet available; runnable sessions always have a player.
	 */
	public Optional<LocalPlayer> player() {
		return Optional.ofNullable(player);
	}

	/** The player's currently open container, when a player is present. */
	public Optional<AbstractContainerMenu> container() {
		return Optional.ofNullable(container);
	}
}
