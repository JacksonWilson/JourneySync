package com.donkeytt.journeysync;

import java.io.File;

import com.donkeytt.journeysync.server.AdminCommand;
import com.donkeytt.journeysync.server.ServerSync;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

/**
 * Common (server-side) proxy. Holds nothing client-specific.
 */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {
        File worldDir = event.getServer()
            .worldServerForDimension(0)
            .getSaveHandler()
            .getWorldDirectory();
        ServerSync.instance()
            .start(worldDir);
        event.registerServerCommand(new AdminCommand());
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        ServerSync.instance()
            .stop();
    }
}
