package com.jeicrafter.client;

import com.jeicrafter.Constants;
import com.jeicrafter.mixin.KeyMappingAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Key bindings for this mod. Defaults to Z key, freely reconfigurable in the Controls screen.
 */
public final class JeiCrafterKeys {

	/**
	 * When holding this key and clicking an item in JEI, if that item is bookmarked as a recipe bookmark's representative item,
	 * auto-detect and transfer recipe materials, recursively craft missing materials, and complete the crafting.
	 */
	public static final KeyMapping CRAFT = new KeyMapping(
		"key.jeicrafter.craft",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_Z,
		"key.categories.jeicrafter"
	);

	/** Throttle counter for periodic key state output, prints once every 40 ticks (~2 seconds). */
	private static int tickCounter;

	private JeiCrafterKeys() {
	}

	/**
	 * Returns whether the configured craft key is physically held.
	 *
	 * <p>Minecraft does not keep ordinary {@link KeyMapping} pressed state updated while a screen is
	 * open, so {@link KeyMapping#isDown()} alone cannot detect this chord inside JEI.</p>
	 */
	public static boolean isCraftKeyDown() {
		if (CRAFT.isDown()) {
			return true;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.getWindow() == null) {
			return false;
		}

		InputConstants.Key bound = ((KeyMappingAccessor) CRAFT).jeicrafter$getKey();
		long window = minecraft.getWindow().getWindow();
		return switch (bound.getType()) {
			case KEYSYM -> InputConstants.isKeyDown(window, bound.getValue());
			case MOUSE -> GLFW.glfwGetMouseButton(window, bound.getValue()) == GLFW.GLFW_PRESS;
			case SCANCODE -> false;
		};
	}

	/**
	 * Debug: periodically prints the craft key's current state. Used to diagnose "holding the key but nothing triggers" —
	 * comparing {@code isDown()} with the GLFW physical key state distinguishes "key not pressed",
	 * "binding changed/lost" vs "KeyMapping state not updated".
	 */
	public static void logTickState() {
		if (++tickCounter % 40 != 0) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.getWindow() == null) {
			return;
		}
		InputConstants.Key bound = ((KeyMappingAccessor) CRAFT).jeicrafter$getKey();
		boolean rawHeld = isCraftKeyDown();
		Constants.LOG.info(
			"[AutoCraft] KEY BEACON isDown={} rawHeld={} bound='{}'(code={}) save='{}' unbound={}",
			CRAFT.isDown(),
			rawHeld,
			bound.getDisplayName().getString(),
			bound.getValue(),
			CRAFT.saveString(),
			CRAFT.isUnbound()
		);
	}
}
