package com.donkeytt.journeysync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import com.donkeytt.journeysync.data.TileImage;

public class TileImageTest {

    static int[] cell(int color) {
        int[] c = new int[TileImage.CELL_PIXELS];
        Arrays.fill(c, color);
        return c;
    }

    @Test
    public void mergeDrawsOpaquePixelsAndKeepsOthersUnderTransparentOnes() {
        TileImage tile = new TileImage();
        assertTrue(tile.merge(33, cell(0xFF112233), false));
        int[] half = cell(0xFF445566);
        for (int i = 0; i < 128; i++) half[i] = 0; // top half unexplored
        assertTrue(tile.merge(33, half, false));
        int[] out = tile.cell(33);
        assertEquals(0xFF112233, out[0]);
        assertEquals(0xFF445566, out[255]);
        // The cell sits at x 16..31, y 16..31 of the 512x512 image.
        assertEquals(0xFF112233, tile.pixels[16 * TileImage.SIZE + 16]);
        assertEquals(0, tile.pixels[15 * TileImage.SIZE + 16]);
    }

    @Test
    public void mergeReportsNoChangeForSamePixelsOrEmptyCells() {
        TileImage tile = new TileImage();
        tile.merge(0, cell(0xFF010101), false);
        assertFalse(tile.merge(0, cell(0xFF010101), false));
        assertFalse(tile.merge(0, cell(0), false));
    }

    @Test
    public void fillOnlyNeverOverwritesExploredPixels() {
        TileImage tile = new TileImage();
        int[] left = cell(0);
        for (int y = 0; y < 16; y++) left[y * 16] = 0xFF0000FF;
        tile.merge(5, left, false);
        assertTrue(tile.merge(5, cell(0xFFFF0000), true));
        int[] out = tile.cell(5);
        assertEquals(0xFF0000FF, out[0]);
        assertEquals(0xFFFF0000, out[1]);
        assertFalse(tile.merge(5, cell(0xFF00FF00), true));
    }

    @Test
    public void cellCopiesRowByRow() {
        TileImage tile = new TileImage();
        int[] gradient = new int[256];
        for (int i = 0; i < 256; i++) gradient[i] = 0xFF000000 | i;
        tile.merge(1023, gradient, false);
        assertArrayEquals(gradient, tile.cell(1023));
    }
}
