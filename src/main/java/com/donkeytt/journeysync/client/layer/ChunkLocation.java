package com.donkeytt.journeysync.client.layer;

import com.donkeytt.journeysync.data.ChunkSnapshot;
import com.gtnewhorizons.navigator.api.model.locations.ILocationProvider;

/** One chunk from a snapshot. Its block position is the chunk's north-west corner. */
public final class ChunkLocation implements ILocationProvider {

    final ChunkSnapshot snapshot;
    final ChunkSnapshot.Chunk chunk;
    /** {@link com.donkeytt.journeysync.data.ChunkEdges} side masks: outer edges of force-loaded and claimed areas. */
    final int forcedEdges, teamEdges;

    ChunkLocation(ChunkSnapshot snapshot, ChunkSnapshot.Chunk chunk, int forcedEdges, int teamEdges) {
        this.snapshot = snapshot;
        this.chunk = chunk;
        this.forcedEdges = forcedEdges;
        this.teamEdges = teamEdges;
    }

    @Override
    public int getDimensionId() {
        return snapshot.dimension;
    }

    @Override
    public double getBlockX() {
        return chunk.x << 4;
    }

    @Override
    public double getBlockZ() {
        return chunk.z << 4;
    }

    @Override
    public int getChunkX() {
        return chunk.x;
    }

    @Override
    public int getChunkZ() {
        return chunk.z;
    }

    ChunkSnapshot.Team team() {
        return chunk.team >= 0 ? snapshot.teams.get(chunk.team) : null;
    }
}
