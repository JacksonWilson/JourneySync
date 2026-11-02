package com.donkeytt.journeysync.network.packets;

import net.minecraft.entity.player.EntityPlayerMP;

import com.donkeytt.journeysync.network.MainThreadQueue;
import com.donkeytt.journeysync.server.ChunkSnapshots;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Client to server: send me the chunk loading and claims of the dimension I'm in. Carries nothing: the snapshot covers
 * the whole dimension, and the server uses the player's real dimension.
 */
public class ChunkRequestPacket implements IMessage {

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<ChunkRequestPacket, IMessage> {

        @Override
        public IMessage onMessage(ChunkRequestPacket msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            MainThreadQueue.SERVER.execute(() -> ChunkSnapshots.handle(player));
            return null;
        }
    }
}
