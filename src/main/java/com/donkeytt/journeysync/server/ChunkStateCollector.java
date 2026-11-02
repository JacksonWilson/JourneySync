package com.donkeytt.journeysync.server;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.ForgeChunkManager;

import com.donkeytt.journeysync.data.ChunkStateBuilder;

/** Reads the chunk state of one dimension on the server thread. */
public final class ChunkStateCollector {

    private ChunkStateCollector() {}

    public static ChunkStateBuilder collect(WorldServer world, Claims claims) {
        ChunkStateBuilder builder = new ChunkStateBuilder(world.provider.dimensionId);

        for (Object o : world.theChunkProviderServer.loadedChunks) {
            Chunk chunk = (Chunk) o;
            builder.loaded(chunk.xPosition, chunk.zPosition);
        }

        Map<ForgeChunkManager.Ticket, Integer> ids = new IdentityHashMap<>();
        for (Map.Entry<ChunkCoordIntPair, ForgeChunkManager.Ticket> e : ForgeChunkManager.getPersistentChunksFor(world)
            .entries()) {
            ForgeChunkManager.Ticket ticket = e.getValue();
            Integer id = ids.get(ticket);
            if (id == null) {
                TicketDescriber.Description d = TicketDescriber.describe(ticket, world, claims);
                id = builder.addTicket(d.mod, d.owner, d.detail, d.ownerPlayer, d.ownerTeam);
                ids.put(ticket, id);
            }
            builder.forced(e.getKey().chunkXPos, e.getKey().chunkZPos, id);
        }

        claims.addClaims(builder, world.provider.dimensionId);
        return builder;
    }
}
