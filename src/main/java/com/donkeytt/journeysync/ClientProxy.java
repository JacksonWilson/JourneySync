package com.donkeytt.journeysync;

import net.minecraftforge.client.ClientCommandHandler;

import com.donkeytt.journeysync.client.ClientEventHandler;
import com.donkeytt.journeysync.client.JourneySyncCommand;
import com.donkeytt.journeysync.client.layer.ChunkLayer;
import com.donkeytt.journeysync.client.layer.LayerSmokeTest;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;

/**
 * Client proxy: client events, the {@code /jsync} command, and the chunk layer on the map.
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientEventHandler.register();
        ClientCommandHandler.instance.registerCommand(new JourneySyncCommand());
        if (Loader.isModLoaded("navigator")) {
            // Separate classes, so Navigator's API only loads when Navigator is installed.
            ChunkLayer.register();
            if (LayerSmokeTest.mode() != null) LayerSmokeTest.start();
        } else {
            JourneySync.LOG.info("Navigator isn't installed, so there's no chunk layer to draw on the map");
        }
    }
}
