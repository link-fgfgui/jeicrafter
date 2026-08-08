package com.jeicrafter.jei;

import mezz.jei.api.JeiPlugin;

/**
 * Fabric-side JEI plugin entry point: loaded via the {@code jei_mod_plugin} entrypoint in {@code fabric.mod.json},
 * all logic is delegated to the {@link JeiCrafterPlugin} base class.
 */
@JeiPlugin
public final class FabricJeiPlugin extends JeiCrafterPlugin {
}