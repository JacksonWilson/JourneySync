package com.donkeytt.journeysync.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.donkeytt.journeysync.data.TileId;
import com.donkeytt.journeysync.data.TileImageTest;

public class CellStoreTest {

    @TempDir
    File root;

    private static final TileId A = new TileId(0, TileId.DAY, 0, 0);
    private static final TileId B = new TileId(-1, (byte) 3, -2, 4);

    @Test
    public void changedCellsGetIncreasingRevisionsAndUnchangedOnesDont() throws Exception {
        CellStore store = new CellStore(root, 4);
        store.load();
        int[] changed = store
            .merge(A, new int[] { 1, 2 }, new int[][] { TileImageTest.cell(0xFF000001), TileImageTest.cell(0) }, false);
        assertArrayEquals(new int[] { 1 }, changed);
        assertEquals(1, store.rev());
        assertEquals(0, store.merge(A, new int[] { 1 }, new int[][] { TileImageTest.cell(0xFF000001) }, false).length);
        store.merge(B, new int[] { 9 }, new int[][] { TileImageTest.cell(0xFF000002) }, false);
        assertEquals(2, store.rev());
        assertEquals(Collections.singletonList(B), store.tilesNewerThan(1));
        assertArrayEquals(new int[] { 1 }, store.cellsNewerThan(A, 0));
        assertArrayEquals(new int[0], store.cellsNewerThan(A, 1));
    }

    @Test
    public void reloadKeepsPixelsRevisionsAndWorldId() throws Exception {

        CellStore store = new CellStore(root, 4);
        store.load();
        String worldId = store.worldId();
        store.merge(B, new int[] { 9 }, new int[][] { TileImageTest.cell(0xFF000002) }, false);
        store.flush();

        CellStore reloaded = new CellStore(root, 4);
        reloaded.load();
        assertEquals(worldId, reloaded.worldId());
        assertEquals(1, reloaded.rev());
        assertEquals(1, reloaded.tileCount());
        assertArrayEquals(
            TileImageTest.cell(0xFF000002),
            reloaded.get(B)
                .cell(9));
        assertTrue(new File(root, "cells/DIM-1/3/-2,4.png").isFile());
    }

    @Test
    public void evictionWritesDirtyTilesFirst() throws Exception {
        CellStore store = new CellStore(root, 4);
        store.load();
        for (int x = 0; x < 10; x++) {
            store.merge(
                new TileId(0, TileId.DAY, x, 0),
                new int[] { 0 },
                new int[][] { TileImageTest.cell(0xFF000000 | x) },
                false);
        }
        // The first tiles were evicted from the 4-tile cache; reading them back comes from disk.
        assertArrayEquals(
            TileImageTest.cell(0xFF000000),
            store.get(new TileId(0, TileId.DAY, 0, 0))
                .cell(0));
        assertEquals(10, store.rev());
    }
}
