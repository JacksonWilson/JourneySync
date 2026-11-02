package com.donkeytt.journeysync.network;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.network.packets.BlobPacket;
import com.donkeytt.journeysync.network.packets.CellPatchPacket;
import com.donkeytt.journeysync.network.packets.HelloAckPacket;
import com.donkeytt.journeysync.network.packets.HelloPacket;
import com.donkeytt.journeysync.network.packets.SyncMarkPacket;
import com.donkeytt.journeysync.network.packets.WaypointChangePacket;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * Channel registration. All packets use discriminator-based SimpleNetworkWrapper on channel {@code "journeysync"}.
 * {@link CellPatchPacket} travels both ways, with a handler per side.
 *
 * <p>
 * Each side sends only from its sync worker thread, because FML's channel isn't safe to write from several threads.
 * </p>
 */
public final class PacketHandler {

    public static final String CHANNEL = "journeysync";
    /** 3: chunk-cell sync. Versions 1 and 2 were upstream's whole-tile file sync. */
    public static final int PROTOCOL_VERSION = 3;

    public static final byte BLOB_WAYPOINTS_FULL = 2;

    public static SimpleNetworkWrapper wrapper;

    private PacketHandler() {}

    public static void init() {
        wrapper = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL);
        int id = 0;
        wrapper.registerMessage(HelloPacket.Handler.class, HelloPacket.class, id++, Side.SERVER);
        wrapper.registerMessage(HelloAckPacket.Handler.class, HelloAckPacket.class, id++, Side.CLIENT);
        wrapper.registerMessage(BlobPacket.ClientHandler.class, BlobPacket.class, id++, Side.CLIENT);
        wrapper.registerMessage(CellPatchPacket.ClientHandler.class, CellPatchPacket.class, id++, Side.CLIENT);
        wrapper.registerMessage(CellPatchPacket.ServerHandler.class, CellPatchPacket.class, id++, Side.SERVER);
        wrapper.registerMessage(SyncMarkPacket.Handler.class, SyncMarkPacket.class, id++, Side.CLIENT);
        wrapper.registerMessage(WaypointChangePacket.Handler.class, WaypointChangePacket.class, id++, Side.SERVER);
        JourneySync.LOG.info("Registered {} network packets on channel {}", id, CHANNEL);
    }
}
