package com.jeicrafter.jei;

import com.jeicrafter.client.AutoCraftManager;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/**
 * Obtains {@link IJeiRuntime} via the official JEI plugin API, no longer accessing JEI's internal {@code Internal} classes.
 * <p>
 * Forge / Fabric each place a {@code @JeiPlugin} subclass in their respective module that extends this class,
 * letting JEI inject the corresponding loader's runtime instance at runtime (better than direct new).
 */
public class JeiCrafterPlugin implements IModPlugin {

	private static final ResourceLocation PLUGIN_UID = new ResourceLocation("jeicrafter", "jei_plugin");

	@Override
	public ResourceLocation getPluginUid() {
		return PLUGIN_UID;
	}

	/**
	 * Called when JEI runtime becomes available. Hands the runtime to {@link AutoCraftManager} for caching,
	 * for auto-crafting to query bookmarks, recipe layouts, and the transfer manager.
	 */
	@Override
	public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
		AutoCraftManager.jeicrafter$setRuntime(jeiRuntime);
	}

	@Override
	public void onRuntimeUnavailable() {
		AutoCraftManager.jeicrafter$setRuntime(null);
	}
}