package com.donkeytt.journeysync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.donkeytt.journeysync.data.TileId;

public class TileIdTest {

    @Test
    public void relPathRoundTripsEveryMapType() {
        for (byte type = 0; type <= TileId.TOPO; type++) {
            TileId id = new TileId(-1, type, -3, 7);
            assertEquals(id, TileId.parse(id.relPath()));
        }
        assertEquals("DIM0/topo/1,2", new TileId(0, TileId.TOPO, 1, 2).relPath());
        assertEquals("DIM7/15/0,0", new TileId(7, (byte) 15, 0, 0).relPath());
    }

    @Test
    public void rejectsPathsJourneyMapDoesNotWrite() {
        assertNull(TileId.parse("DIM0/../0,0"));
        assertNull(TileId.parse("DIM0/day/0,0/../x"));
        assertNull(TileId.parse("DIM0/16/0,0"));
        assertNull(TileId.parse("DIM0/surface/0,0"));
        assertNull(TileId.parse("DIM0\\day\\0,0"));
    }

    @Test
    public void cellIndexWrapsNegativeChunks() {
        assertEquals(0, TileId.cellIndex(0, 0));
        assertEquals(31, TileId.cellIndex(-1, 0));
        assertEquals(31 * 32, TileId.cellIndex(0, -1));
        assertEquals(32 + 1, TileId.cellIndex(33, 1));
    }
}
