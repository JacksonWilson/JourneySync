package com.donkeytt.journeysync.data;

import static com.donkeytt.journeysync.data.ChunkEdges.EAST;
import static com.donkeytt.journeysync.data.ChunkEdges.NORTH;
import static com.donkeytt.journeysync.data.ChunkEdges.SOUTH;
import static com.donkeytt.journeysync.data.ChunkEdges.WEST;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunkEdgesTest {

    private static int[] edgesAt(ChunkSnapshot s, int[][] edges, int x, int z) {
        for (int i = 0; i < s.chunks.size(); i++) {
            if (s.chunks.get(i).x == x && s.chunks.get(i).z == z) return edges[i];
        }
        throw new AssertionError("no chunk at " + x + ", " + z);
    }

    @Test
    void blockOfForcedChunksIsOutlinedOnlyOnTheOutside() {
        // A 3x3 block of force-loaded chunks, with an extra loaded chunk east of the middle.
        ChunkStateBuilder b = new ChunkStateBuilder(0);
        int t = b.addTicket("GregTech", null, null, null, null);
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) b.forced(x, z, t);
        b.loaded(3, 1);
        ChunkSnapshot s = b.build(new ChunkStateBuilder.Viewer(true, "Op", null));
        int[][] e = ChunkEdges.compute(s);

        assertEquals(0, edgesAt(s, e, 1, 1)[0], "middle has no outline");
        assertEquals(NORTH | WEST, edgesAt(s, e, 0, 0)[0]);
        assertEquals(NORTH, edgesAt(s, e, 1, 0)[0]);
        assertEquals(SOUTH | EAST, edgesAt(s, e, 2, 2)[0]);
        assertEquals(EAST, edgesAt(s, e, 2, 1)[0], "a loaded neighbour that isn't forced still gets an edge");
        assertEquals(0, edgesAt(s, e, 3, 1)[0]);
        assertEquals(0, edgesAt(s, e, 1, 1)[1], "no claims, no team edges");
    }

    @Test
    void teamEdgesSeparateTeams() {
        ChunkStateBuilder b = new ChunkStateBuilder(0);
        b.addTeam("red", "Red", 0xFF0000);
        b.addTeam("blue", "Blue", 0x0000FF);
        b.claimed(0, 0, "red");
        b.claimed(1, 0, "red");
        b.claimed(2, 0, "blue");
        ChunkSnapshot s = b.build(new ChunkStateBuilder.Viewer(true, "Op", null));
        int[][] e = ChunkEdges.compute(s);

        assertEquals(NORTH | SOUTH | WEST, edgesAt(s, e, 0, 0)[1]);
        assertEquals(NORTH | SOUTH | EAST, edgesAt(s, e, 1, 0)[1], "edge where red meets blue");
        assertEquals(NORTH | SOUTH | WEST | EAST, edgesAt(s, e, 2, 0)[1]);
        assertEquals(0, edgesAt(s, e, 0, 0)[0], "not forced");
    }

    @Test
    void negativeCoordinatesAreNeighboursToo() {
        ChunkStateBuilder b = new ChunkStateBuilder(0);
        int t = b.addTicket("m", null, null, null, null);
        b.forced(-1, -1, t);
        b.forced(0, -1, t);
        b.forced(-1, 0, t);
        ChunkSnapshot s = b.build(new ChunkStateBuilder.Viewer(true, "Op", null));
        int[][] e = ChunkEdges.compute(s);
        assertEquals(NORTH | WEST, edgesAt(s, e, -1, -1)[0]);
        assertEquals(NORTH | EAST | SOUTH, edgesAt(s, e, 0, -1)[0]);
        assertEquals(WEST | SOUTH | EAST, edgesAt(s, e, -1, 0)[0]);
    }
}
