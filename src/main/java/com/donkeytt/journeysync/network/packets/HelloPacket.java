package com.donkeytt.journeysync.network.packets;

import com.donkeytt.journeysync.network.PacketHandler;
import com.donkeytt.journeysync.server.ServerSync;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Client to server, once the player is in the world: the client's protocol, and how much of this server's map it
 * already has ({@code lastRev} for the map {@code worldId} it last synced with).
 */
public class HelloPacket implements IMessage {

    public int protocolVersion;
    public boolean hasJourneyMap;
    public String modVersion;
    public String worldId;
    public long lastRev;

    public HelloPacket() {}

    public HelloPacket(boolean hasJourneyMap, String modVersion, String worldId, long lastRev) {
        this.protocolVersion = PacketHandler.PROTOCOL_VERSION;
        this.hasJourneyMap = hasJourneyMap;
        this.modVersion = modVersion;
        this.worldId = worldId;
        this.lastRev = lastRev;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = BufUtil.readVarInt(buf, 1);
        hasJourneyMap = buf.readBoolean();
        modVersion = ByteBufUtils.readUTF8String(buf);
        // Older protocols end here; the server answers them with a version mismatch.
        if (protocolVersion >= 3) {
            worldId = ByteBufUtils.readUTF8String(buf);
            lastRev = buf.readLong();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        BufUtil.writeVarInt(buf, protocolVersion);
        buf.writeBoolean(hasJourneyMap);
        ByteBufUtils.writeUTF8String(buf, modVersion == null ? "" : modVersion);
        ByteBufUtils.writeUTF8String(buf, worldId == null ? "" : worldId);
        buf.writeLong(lastRev);
    }

    public static class Handler implements IMessageHandler<HelloPacket, IMessage> {

        @Override
        public IMessage onMessage(HelloPacket message, MessageContext ctx) {
            ServerSync.instance()
                .onHello(ctx.getServerHandler().playerEntity, message);
            return null;
        }
    }
}
