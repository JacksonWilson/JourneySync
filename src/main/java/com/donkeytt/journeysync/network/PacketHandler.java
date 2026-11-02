package com.donkeytt.journeysync.network;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.entity.player.EntityPlayerMP;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.network.packets.BlobPacket;
import com.donkeytt.journeysync.network.packets.CellPatchPacket;
import com.donkeytt.journeysync.network.packets.ChunkRequestPacket;
import com.donkeytt.journeysync.network.packets.HelloPacket;
import com.donkeytt.journeysync.network.packets.ServerHelloPacket;
import com.donkeytt.journeysync.network.packets.SyncMarkPacket;
import com.donkeytt.journeysync.network.packets.WaypointChangePacket;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * The {@code journeysync} channel. {@link CellPatchPacket} travels both ways, with a handler per side.
 *
 * <p>
 * Packets are sent from several threads (the sync workers, the server thread for chunk snapshots, the render thread
 * for chunk requests). FML's channel isn't safe to write from more than one thread at once, so every send goes
 * through the synchronized methods here.
 * </p>
 */
public final class PacketHandler {

    public static final String CHANNEL = "journeysync";
    /** 4: one handshake, sent by the server at login. 3 was the first chunk-cell protocol. */
    public static final int PROTOCOL_VERSION = 4;

    public static final byte BLOB_WAYPOINTS = 1;
    public static final byte BLOB_CHUNKS = 2;

    private static SimpleNetworkWrapper wrapper;
    private static final Object SEND_LOCK = new Object();
    private static final AtomicInteger NEXT_BLOB_ID = new AtomicInteger();

    private PacketHandler() {}

    public static void init() {
        wrapper = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL);
        int id = 0;
        wrapper.registerMessage(ServerHelloPacket.Handler.class, ServerHelloPacket.class, id++, Side.CLIENT);
        wrapper.registerMessage(HelloPacket.Handler.class, HelloPacket.class, id++, Side.SERVER);
        wrapper.registerMessage(BlobPacket.ClientHandler.class, BlobPacket.class, id++, Side.CLIENT);
        wrapper.registerMessage(CellPatchPacket.ClientHandler.class, CellPatchPacket.class, id++, Side.CLIENT);
        wrapper.registerMessage(CellPatchPacket.ServerHandler.class, CellPatchPacket.class, id++, Side.SERVER);
        wrapper.registerMessage(SyncMarkPacket.Handler.class, SyncMarkPacket.class, id++, Side.CLIENT);
        wrapper.registerMessage(WaypointChangePacket.Handler.class, WaypointChangePacket.class, id++, Side.SERVER);
        wrapper.registerMessage(ChunkRequestPacket.Handler.class, ChunkRequestPacket.class, id++, Side.SERVER);
        JourneySync.LOG.info("Registered {} network packets on channel {}", id, CHANNEL);
    }

    public static void sendTo(IMessage message, EntityPlayerMP player) {
        synchronized (SEND_LOCK) {
            wrapper.sendTo(message, player);
        }
    }

    public static void sendToServer(IMessage message) {
        synchronized (SEND_LOCK) {
            wrapper.sendToServer(message);
        }
    }

    /** Splits a gzipped blob into packets, ready to send in order. */
    public static BlobPacket[] blobPackets(byte kind, byte[] gzipped) {
        List<byte[]> parts = Blobs.split(gzipped, Blobs.PART_SIZE);
        int id = NEXT_BLOB_ID.incrementAndGet();
        BlobPacket[] out = new BlobPacket[parts.size()];
        for (int i = 0; i < out.length; i++) out[i] = new BlobPacket(kind, id, i, out.length, parts.get(i));
        return out;
    }

    public static void sendBlob(EntityPlayerMP player, byte kind, byte[] gzipped) {
        for (BlobPacket p : blobPackets(kind, gzipped)) sendTo(p, player);
    }
}
