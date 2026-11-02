package com.donkeytt.journeysync.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/** Connection events and the client tick, routed to {@link ClientSync}. */
public class ClientEventHandler {

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new ClientEventHandler());
    }

    @SubscribeEvent
    public void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        // A singleplayer world (not opened to LAN) has nobody to share with.
        ClientSync.instance()
            .onConnect(event.isLocal);
    }

    @SubscribeEvent
    public void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ClientSync.instance()
            .onDisconnect();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ClientSync.instance()
                .onClientTick();
        }
    }
}
