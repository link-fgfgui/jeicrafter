package com.jeicrafter.client;

import com.jeicrafter.mixin.KeyMappingAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Key bindings for this mod. Defaults to Z key for auto-craft and H for workstation highlight,
 * freely reconfigurable in the Controls screen.
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

	/**
	 * On a JEI recipe screen, pressing this key highlights every workstation block for the current
	 * recipe across the loaded world. Only meaningful for non-crafting recipe categories.
	 */
	public static final KeyMapping HIGHLIGHT_WORKSTATION = new KeyMapping(
		"key.jeicrafter.highlight_workstation",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_H,
		"key.categories.jeicrafter"
	);

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
		return isBoundKeyDown(bound, window);
	}

	/**
	 * Returns whether the given GLFW key press corresponds to the configured highlight key.
	 * <p>
	 * This is used by the {@code RecipesGui} key mixin: a screen's {@code keyPressed} receives the
	 * raw key/scanCode, which we compare against the {@link InputConstants.Type#KEYSYM} binding.
	 */
	public static boolean isHighlightKey(int keyCode, int scanCode) {
		InputConstants.Key bound = ((KeyMappingAccessor) HIGHLIGHT_WORKSTATION).jeicrafter$getKey();
		InputConstants.Key pressed = InputConstants.getKey(keyCode, scanCode);
		return bound.equals(pressed);
	}

	private static boolean isBoundKeyDown(InputConstants.Key bound, long window) {
		return switch (bound.getType()) {
			case KEYSYM -> InputConstants.isKeyDown(window, bound.getValue());
			case MOUSE -> GLFW.glfwGetMouseButton(window, bound.getValue()) == GLFW.GLFW_PRESS;
			case SCANCODE -> false;
		};
	}
}
