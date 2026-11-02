package com.donkeytt.journeysync.network.packets;

import com.donkeytt.journeysync.client.ClientSync;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Server to client: the server's protocol and the id of its shared map. */
public class HelloAckPacket implements IMessage {

    public int protocolVersion;
    public String worldId;

    public HelloAckPacket() {}

    public HelloAckPacket(int protocolVersion, String worldId) {
        this.protocolVersion = protocolVersion;
        this.worldId = worldId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = BufUtil.readVarInt(buf, 1);
        worldId = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        BufUtil.writeVarInt(buf, protocolVersion);
        ByteBufUtils.writeUTF8String(buf, worldId == null ? "" : worldId);
    }

    public static class Handler implements IMessageHandler<HelloAckPacket, IMessage> {

        @Override
        public IMessage onMessage(HelloAckPacket message, MessageContext ctx) {
            ClientSync.instance()
                .onHelloAck(message);
            return null;
        }
    }
}
