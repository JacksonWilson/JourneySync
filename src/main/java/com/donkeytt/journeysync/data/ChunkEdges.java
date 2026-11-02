package com.donkeytt.journeysync.data;

import java.util.HashMap;
import java.util.Map;

/**
 * Which sides of each chunk lie on the outer edge of an area, so the map draws one outline around a block of
 * force-loaded chunks or a team's claims instead of a border around every chunk.
 */
public final class ChunkEdges {

    /** Side bits. North is -z, which is up on the map. */
    public static final int NORTH = 1, SOUTH = 2, WEST = 4, EAST = 8;

    private ChunkEdges() {}

    /**
     * Returns two side masks per chunk, in the order of {@code snapshot.chunks}: {@code [i][0]} has the sides where
     * a force-loaded chunk meets one that isn't, {@code [i][1]} the sides where a claim meets a chunk not claimed by
     * the same team. Chunks that are neither get 0.
     */
    public static int[][] compute(ChunkSnapshot snapshot) {
        Map<Long, ChunkSnapshot.Chunk> byPos = new HashMap<>(snapshot.chunks.size() * 2);
        for (ChunkSnapshot.Chunk c : snapshot.chunks) byPos.put(key(c.x, c.z), c);

        int[][] edges = new int[snapshot.chunks.size()][2];
        for (int i = 0; i < edges.length; i++) {
            ChunkSnapshot.Chunk c = snapshot.chunks.get(i);
            boolean forced = c.has(ChunkSnapshot.FORCED);
            boolean claimed = c.team >= 0;
            if (!forced && !claimed) continue;
            int f = 0, t = 0;
            int[][] sides = { { NORTH, 0, -1 }, { SOUTH, 0, 1 }, { WEST, -1, 0 }, { EAST, 1, 0 } };
            for (int[] s : sides) {
                ChunkSnapshot.Chunk n = byPos.get(key(c.x + s[1], c.z + s[2]));
                if (forced && (n == null || !n.has(ChunkSnapshot.FORCED))) f |= s[0];
                if (claimed && (n == null || n.team != c.team)) t |= s[0];
            }
            edges[i][0] = f;
            edges[i][1] = t;
        }
        return edges;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
