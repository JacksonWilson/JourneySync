package com.donkeytt.journeysync.client;

import com.donkeytt.journeysync.network.MainThreadQueue;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/** Connection events and the client tick, for map sync and the chunk layer. */
public class ClientEventHandler {

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new ClientEventHandler());
    }

    @SubscribeEvent
    public void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        // A singleplayer world (not opened to LAN) has nobody to share a map with.
        ClientSync.instance()
            .onConnect(event.isLocal);
    }

    /** Fired on the network thread. */
    @SubscribeEvent
    public void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ServerInfo.clear();
        ClientSync.instance()
            .onDisconnect();
        MainThreadQueue.CLIENT.execute(ChunkLayerState::onDisconnect);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            MainThreadQueue.CLIENT.drain();
        } else {
            ClientSync.instance()
                .onClientTick();
        }
    }
}
