package com.donkeytt.journeysync.server;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/** Drops a player's sync session when they log out. */
public class ServerEventHandler {

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new ServerEventHandler());
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            ServerSync.instance()
                .onLogout((EntityPlayerMP) event.player);
        }
    }
}
