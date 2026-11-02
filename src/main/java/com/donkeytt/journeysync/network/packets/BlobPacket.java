package com.donkeytt.journeysync.network.packets;

import java.io.DataInputStream;
import java.io.IOException;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.client.ChunkLayerState;
import com.donkeytt.journeysync.client.ClientSync;
import com.donkeytt.journeysync.data.ChunkSnapshot;
import com.donkeytt.journeysync.network.Blobs;
import com.donkeytt.journeysync.network.MainThreadQueue;
import com.donkeytt.journeysync.network.PacketHandler;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Server to client: one part of a gzipped blob (see {@link Blobs}), such as the waypoint list or a chunk snapshot. */
public class BlobPacket implements IMessage {

    public byte kind;
    public int id;
    public int part;
    public int total;
    public byte[] data;

    public BlobPacket() {}

    public BlobPacket(byte kind, int id, int part, int total, byte[] data) {
        this.kind = kind;
        this.id = id;
        this.part = part;
        this.total = total;
        this.data = data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        kind = buf.readByte();
        id = buf.readInt();
        part = BufUtil.readVarInt(buf, 3);
        total = BufUtil.readVarInt(buf, 3);
        int length = BufUtil.readVarInt(buf, 3);
        if (length < 0 || length > buf.readableBytes()) throw new IllegalArgumentException("bad blob part length");
        data = new byte[length];
        buf.readBytes(data);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(kind);
        buf.writeInt(id);
        BufUtil.writeVarInt(buf, part);
        BufUtil.writeVarInt(buf, total);
        BufUtil.writeVarInt(buf, data.length);
        buf.writeBytes(data);
    }

    public int size() {
        return data.length + 16;
    }

    public static class ClientHandler implements IMessageHandler<BlobPacket, IMessage> {

        private static final Blobs.Assembler WAYPOINTS = new Blobs.Assembler();
        private static final Blobs.Assembler CHUNKS = new Blobs.Assembler();

        @Override
        public IMessage onMessage(BlobPacket msg, MessageContext ctx) {
            try {
                if (msg.kind == PacketHandler.BLOB_WAYPOINTS) {
                    byte[] blob = WAYPOINTS.add(msg.id, msg.part, msg.total, msg.data);
                    if (blob != null) ClientSync.instance()
                        .onWaypointBlob(Blobs.gunzipString(blob));
                } else if (msg.kind == PacketHandler.BLOB_CHUNKS) {
                    byte[] blob = CHUNKS.add(msg.id, msg.part, msg.total, msg.data);
                    if (blob == null) return null;
                    ChunkSnapshot snapshot;
                    try (DataInputStream in = new DataInputStream(Blobs.gunzip(blob))) {
                        snapshot = ChunkSnapshot.read(in);
                    }
                    MainThreadQueue.CLIENT.execute(() -> ChunkLayerState.onSnapshot(snapshot));
                }
            } catch (IOException e) {
                (msg.kind == PacketHandler.BLOB_CHUNKS ? CHUNKS : WAYPOINTS).clear();
                JourneySync.LOG.warn("Dropped a bad blob from the server: {}", e.getMessage());
            }
            return null;
        }
    }
}
