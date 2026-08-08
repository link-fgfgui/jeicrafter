package com.jeicrafter.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the raw key currently bound to a KeyMapping. KeyMapping in 1.20.1 has no public
 * getKey() getter; when diagnosing "holding the designated key but auto-craft not triggered",
 * the actual bound key is needed to compare isDown() with the GLFW physical key state.
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

	@Accessor("key")
	InputConstants.Key jeicrafter$getKey();
}