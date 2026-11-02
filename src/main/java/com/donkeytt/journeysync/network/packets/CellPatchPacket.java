package com.donkeytt.journeysync.network.packets;

import com.donkeytt.journeysync.client.ClientSync;
import com.donkeytt.journeysync.data.CellPatch;
import com.donkeytt.journeysync.data.TileId;
import com.donkeytt.journeysync.server.ServerSync;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Cells of one region tile. Client to server: freshly rendered cells. Server to client: merged cells to apply. */
public class CellPatchPacket implements IMessage {

    private static final int MAX_PNG_BYTES = 64 * 1024;

    public CellPatch patch;

    public CellPatchPacket() {}

    public CellPatchPacket(CellPatch patch) {
        this.patch = patch;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        TileId tile = new TileId(buf.readInt(), buf.readByte(), buf.readInt(), buf.readInt());
        boolean fillOnly = buf.readBoolean();
        byte[] mask = new byte[CellPatch.MASK_BYTES];
        buf.readBytes(mask);
        int length = BufUtil.readVarInt(buf, 3);
        if (length < 0 || length > MAX_PNG_BYTES || length > buf.readableBytes()) {
            throw new IllegalArgumentException("bad cell patch length " + length);
        }
        byte[] png = new byte[length];
        buf.readBytes(png);
        patch = new CellPatch(tile, fillOnly, CellPatch.indicesFromMask(mask), png);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(patch.tile.dimension);
        buf.writeByte(patch.tile.type);
        buf.writeInt(patch.tile.regionX);
        buf.writeInt(patch.tile.regionZ);
        buf.writeBoolean(patch.fillOnly);
        buf.writeBytes(patch.mask());
        BufUtil.writeVarInt(buf, patch.png.length);
        buf.writeBytes(patch.png);
    }

    /** Bytes this packet takes on the wire, for rate limiting. */
    public int size() {
        return 4 + 1 + 8 + 1 + CellPatch.MASK_BYTES + 3 + patch.png.length;
    }

    public static class ServerHandler implements IMessageHandler<CellPatchPacket, IMessage> {

        @Override
        public IMessage onMessage(CellPatchPacket message, MessageContext ctx) {
            ServerSync.instance()
                .onUpload(ctx.getServerHandler().playerEntity, message.patch);
            return null;
        }
    }

    public static class ClientHandler implements IMessageHandler<CellPatchPacket, IMessage> {

        @Override
        public IMessage onMessage(CellPatchPacket message, MessageContext ctx) {
            ClientSync.instance()
                .onPatch(message.patch);
            return null;
        }
    }
}
