package com.jeicrafter;

import com.jeicrafter.forge.client.ForgeGameEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLLoader;

@Mod(Constants.MOD_ID)
public class JeiCrafter {

    public JeiCrafter() {

        // This method is invoked by the Forge mod loader when it is ready
        // to load your mod. You can access Forge and Common code in this
        // project.

        // Use Forge to bootstrap the Common mod.
//        Constants.LOG.info("Hello Forge world!");
        CommonClass.init();

        // Attach the render hook and the highlight-dismissal hook explicitly. Both events are
        // client-only and the handlers reference client classes, so only register when running on
        // the physical client.
        if (FMLLoader.getDist() == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.addListener(ForgeGameEvents::renderWorkstationHighlight);
            MinecraftForge.EVENT_BUS.addListener(ForgeGameEvents::onRightClickBlock);
            MinecraftForge.EVENT_BUS.addListener(ForgeGameEvents::onScreenKeyPressed);
        }

    }
}
