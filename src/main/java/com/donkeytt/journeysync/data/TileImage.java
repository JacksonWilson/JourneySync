package com.donkeytt.journeysync.data;

/**
 * A region tile's 512x512 ARGB pixels, plus a revision number per 16x16 cell (one cell per chunk). The client keeps
 * revisions at 0; the server uses them to know which cells a player hasn't received yet.
 *
 * <p>
 * Merge rule, the same one JourneyMap uses for its own chunk renders: an incoming pixel replaces the stored one unless
 * it is transparent, so an unexplored pixel never erases an explored one.
 * </p>
 */
public final class TileImage {

    public static final int SIZE = 512;
    public static final int CELLS = 1024;
    public static final int CELL_PIXELS = 256;

    public final int[] pixels;
    public final long[] revs;

    public TileImage() {
        this(new int[SIZE * SIZE], new long[CELLS]);
    }

    public TileImage(int[] pixels, long[] revs) {
        if (pixels.length != SIZE * SIZE || revs.length != CELLS) {
            throw new IllegalArgumentException("bad tile size");
        }
        this.pixels = pixels;
        this.revs = revs;
    }

    /** Copies one cell's 256 pixels, row by row. */
    public int[] cell(int index) {
        int[] out = new int[CELL_PIXELS];
        int x0 = (index & 31) * 16;
        int y0 = (index >> 5) * 16;
        for (int y = 0; y < 16; y++) {
            System.arraycopy(pixels, (y0 + y) * SIZE + x0, out, y * 16, 16);
        }
        return out;
    }

    /**
     * Draws a cell over the stored one. With {@code fillOnly}, only pixels the tile has never had are written, so old
     * maps can fill gaps without overwriting newer data. Returns whether any pixel changed.
     */
    public boolean merge(int index, int[] cell, boolean fillOnly) {
        int x0 = (index & 31) * 16;
        int y0 = (index >> 5) * 16;
        boolean changed = false;
        for (int y = 0; y < 16; y++) {
            int row = (y0 + y) * SIZE + x0;
            for (int x = 0; x < 16; x++) {
                int src = cell[y * 16 + x];
                if (src >>> 24 == 0) continue;
                int dst = pixels[row + x];
                if (src == dst || fillOnly && dst >>> 24 != 0) continue;
                pixels[row + x] = src;
                changed = true;
            }
        }
        return changed;
    }

    /** Fills this tile's transparent pixels from {@code other}. Returns the indices of the cells that changed. */
    public int[] fillFrom(TileImage other) {
        int[] changed = new int[CELLS];
        int n = 0;
        for (int i = 0; i < CELLS; i++) {
            int[] cell = other.cell(i);
            if (!isEmpty(cell) && merge(i, cell, true)) changed[n++] = i;
        }
        return java.util.Arrays.copyOf(changed, n);
    }

    /** True if every pixel of the cell is transparent. */
    public static boolean isEmpty(int[] cell) {
        for (int p : cell) {
            if (p >>> 24 != 0) return false;
        }
        return true;
    }
}
