package com.jeicrafter.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * 本模组的按键绑定。默认 Z 键,可在"控制"界面中自由修改(可指定按键)。
 */
public final class JeiCrafterKeys {

	/**
	 * 按住该键点击 JEI 中的物品时,若该物品被收藏为某个 recipe bookmark 的代表物品,
	 * 则自动检测并转移配方材料、递归补齐缺失材料并完成合成。
	 */
	public static final KeyMapping CRAFT = new KeyMapping(
		"key.jeicrafter.craft",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_Z,
		"key.categories.jeicrafter"
	);

	private JeiCrafterKeys() {
	}
}
