package com.jeicrafter;

import com.jeicrafter.platform.Services;

public class CommonClass {

    public static void init() {

        Constants.LOG.info("JEI Crafter initialized on {}! Environment: {}", Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());

        if (Services.PLATFORM.isModLoaded("jeicrafter")) {
            Constants.LOG.info("JEI Crafter is loaded!");
        }
    }
}