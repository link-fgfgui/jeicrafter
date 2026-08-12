package com.jeicrafter.mixin;

import com.jeicrafter.client.AutoCraftManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    
	@Inject(at = @At("TAIL"), method = "tick")
	private void jeicrafter$tickAutoCraft(CallbackInfo info) {
		AutoCraftManager.tick();
	}
}
