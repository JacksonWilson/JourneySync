package com.donkeytt.journeysync.network.packets;

import com.donkeytt.journeysync.client.ClientSync;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server to client, at login: this server has JourneySync, speaks this protocol, and its shared map has this id.
 * Players without JourneySync ignore it. Nothing is ever sent to a server that didn't send this.
 */
public class ServerHelloPacket implements IMessage {

    public int protocolVersion;
    public String worldId;

    public ServerHelloPacket() {}

    public ServerHelloPacket(int protocolVersion, String worldId) {
        this.protocolVersion = protocolVersion;
        this.worldId = worldId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = BufUtil.readVarInt(buf, 2);
        worldId = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        BufUtil.writeVarInt(buf, protocolVersion);
        ByteBufUtils.writeUTF8String(buf, worldId == null ? "" : worldId);
    }

    public static class Handler implements IMessageHandler<ServerHelloPacket, IMessage> {

        @Override
        public IMessage onMessage(ServerHelloPacket message, MessageContext ctx) {
            ClientSync.instance()
                .onServerHello(message.protocolVersion, message.worldId);
            return null;
        }
    }
}
