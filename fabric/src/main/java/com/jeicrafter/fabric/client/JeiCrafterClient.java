package com.jeicrafter.fabric.client;

import com.jeicrafter.client.JeiCrafterKeys;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

public class JeiCrafterClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		KeyBindingHelper.registerKeyBinding(JeiCrafterKeys.CRAFT);
	}
}
