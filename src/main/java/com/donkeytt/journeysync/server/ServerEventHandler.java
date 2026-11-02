package com.donkeytt.journeysync.server;

import net.minecraft.entity.player.EntityPlayerMP;

import com.donkeytt.journeysync.network.MainThreadQueue;
import com.donkeytt.journeysync.network.PacketHandler;
import com.donkeytt.journeysync.network.packets.ServerHelloPacket;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Greets players at login, forgets them at logout, and runs work queued for the server thread. */
public class ServerEventHandler {

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new ServerEventHandler());
    }

    /** Tells the client this server has JourneySync. A client without it ignores the packet. */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            PacketHandler.sendTo(
                new ServerHelloPacket(
                    PacketHandler.PROTOCOL_VERSION,
                    ServerSync.instance()
                        .worldId()),
                player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            ServerSync.instance()
                .onLogout(player);
            ChunkSnapshots.forget(player);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) MainThreadQueue.SERVER.drain();
    }
}
