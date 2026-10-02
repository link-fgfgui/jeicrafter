package com.jeicrafter.forge.client;

import com.jeicrafter.Constants;
import com.jeicrafter.client.JeiCrafterKeys;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ForgeClientEvents {

	@SubscribeEvent
	public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
		event.register(JeiCrafterKeys.CRAFT);
		event.register(JeiCrafterKeys.HIGHLIGHT_WORKSTATION);
		event.register(JeiCrafterKeys.SORT_BOOKMARKS);
	}
}
