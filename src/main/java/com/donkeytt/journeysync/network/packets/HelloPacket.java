package com.donkeytt.journeysync.network.packets;

import com.donkeytt.journeysync.network.PacketHandler;
import com.donkeytt.journeysync.server.ServerSync;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Client to server, once the server has greeted the client and JourneyMap is mapping: start syncing the map with me.
 * {@code lastRev} is how much of the shared map {@code worldId} this client already has; the server sends the rest.
 */
public class HelloPacket implements IMessage {

    public int protocolVersion;
    public String modVersion;
    public String worldId;
    public long lastRev;

    public HelloPacket() {}

    public HelloPacket(String modVersion, String worldId, long lastRev) {
        this.protocolVersion = PacketHandler.PROTOCOL_VERSION;
        this.modVersion = modVersion;
        this.worldId = worldId;
        this.lastRev = lastRev;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = BufUtil.readVarInt(buf, 2);
        modVersion = ByteBufUtils.readUTF8String(buf);
        worldId = ByteBufUtils.readUTF8String(buf);
        lastRev = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        BufUtil.writeVarInt(buf, protocolVersion);
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
