package com.donkeytt.journeysync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.donkeytt.journeysync.data.CellPatch;
import com.donkeytt.journeysync.data.TileId;
import com.donkeytt.journeysync.data.TileImage;

public class CellPatchTest {

    private static final TileId TILE = new TileId(0, TileId.DAY, 5, 5);

    @Test
    public void encodeDecodeRoundTrips() throws Exception {
        int[] indices = { 0, 7, 1023 };
        int[][] cells = { TileImageTest.cell(0xFF102030), TileImageTest.cell(0), TileImageTest.cell(0x80FFFFFF) };
        List<CellPatch> patches = CellPatch.encode(TILE, true, indices, cells);
        assertEquals(1, patches.size());
        CellPatch p = patches.get(0);
        assertTrue(p.fillOnly);
        assertArrayEquals(indices, CellPatch.indicesFromMask(p.mask()));
        int[][] back = p.cells();
        for (int i = 0; i < cells.length; i++) assertArrayEquals(cells[i], back[i]);
    }

    @Test
    public void largeTilesSplitUnderThePacketLimit() throws Exception {
        // Random noise doesn't compress, so a full tile can't fit in one patch.
        Random random = new Random(1);
        int[] indices = new int[TileImage.CELLS];
        int[][] cells = new int[TileImage.CELLS][];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
            cells[i] = new int[TileImage.CELL_PIXELS];
            for (int j = 0; j < cells[i].length; j++) cells[i][j] = 0xFF000000 | random.nextInt(0x1000000);
        }
        List<CellPatch> patches = CellPatch.encode(TILE, false, indices, cells);
        assertTrue(patches.size() > 1);
        int seen = 0;
        for (CellPatch p : patches) {
            assertTrue(p.png.length <= CellPatch.MAX_PNG_BYTES);
            int[][] back = p.cells();
            for (int i = 0; i < p.indices.length; i++) {
                assertArrayEquals(cells[p.indices[i]], back[i]);
            }
            seen += p.indices.length;
        }
        assertEquals(TileImage.CELLS, seen);
    }
}
