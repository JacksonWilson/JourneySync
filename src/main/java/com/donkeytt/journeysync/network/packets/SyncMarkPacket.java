package com.donkeytt.journeysync.network.packets;

import com.donkeytt.journeysync.client.ClientSync;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: every cell up to this revision has been sent. Packets on one connection arrive in order, so once
 * the client has applied everything before the mark it can store the revision and ask only for newer cells next time.
 */
public class SyncMarkPacket implements IMessage {

    public long rev;
    /** True for the mark that ends the catch-up after login. */
    public boolean catchUpDone;

    public SyncMarkPacket() {}

    public SyncMarkPacket(long rev, boolean catchUpDone) {
        this.rev = rev;
        this.catchUpDone = catchUpDone;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        rev = buf.readLong();
        catchUpDone = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(rev);
        buf.writeBoolean(catchUpDone);
    }

    public static class Handler implements IMessageHandler<SyncMarkPacket, IMessage> {

        @Override
        public IMessage onMessage(SyncMarkPacket message, MessageContext ctx) {
            ClientSync.instance()
                .onMark(message.rev, message.catchUpDone);
            return null;
        }
    }
}
