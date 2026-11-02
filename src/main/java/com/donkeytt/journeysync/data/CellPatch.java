package com.donkeytt.journeysync.data;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Some cells of one region tile, as sent over the network. On the wire the cells are a PNG strip, 16 pixels wide and
 * 16 pixels tall per cell, in ascending cell index order, plus a 1024-bit mask saying which cells are included. PNG
 * keeps a patch near the size JourneyMap's own files would need for the same pixels.
 */
public final class CellPatch {

    /** Strip PNGs larger than this are split, so a packet stays under Forge's 32 KB client-to-server limit. */
    public static final int MAX_PNG_BYTES = 28_000;
    public static final int MASK_BYTES = TileImage.CELLS / 8;

    public final TileId tile;
    /** Server side: only fill pixels the server has never seen. Used for maps drawn before the mod was installed. */
    public final boolean fillOnly;
    /** Ascending cell indices, see {@link TileId#cellIndex}. */
    public final int[] indices;
    public final byte[] png;

    public CellPatch(TileId tile, boolean fillOnly, int[] indices, byte[] png) {
        this.tile = tile;
        this.fillOnly = fillOnly;
        this.indices = indices;
        this.png = png;
    }

    /** Encodes cells into one or more patches, each with a strip PNG of at most {@link #MAX_PNG_BYTES}. */
    public static List<CellPatch> encode(TileId tile, boolean fillOnly, int[] indices, int[][] cells)
        throws IOException {
        List<CellPatch> out = new ArrayList<>();
        encodeInto(out, tile, fillOnly, indices, cells, 0, indices.length);
        return out;
    }

    private static void encodeInto(List<CellPatch> out, TileId tile, boolean fillOnly, int[] indices, int[][] cells,
        int from, int to) throws IOException {
        byte[] png = stripPng(cells, from, to);
        if (png.length > MAX_PNG_BYTES && to - from > 1) {
            int mid = (from + to) >>> 1;
            encodeInto(out, tile, fillOnly, indices, cells, from, mid);
            encodeInto(out, tile, fillOnly, indices, cells, mid, to);
            return;
        }
        out.add(new CellPatch(tile, fillOnly, Arrays.copyOfRange(indices, from, to), png));
    }

    /** Decodes the strip back into one 256-pixel array per index. */
    public int[][] cells() throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null || img.getWidth() != 16 || img.getHeight() != 16 * indices.length) {
            throw new IOException("cell strip doesn't match its mask");
        }
        int[][] out = new int[indices.length][];
        for (int i = 0; i < indices.length; i++) {
            out[i] = img.getRGB(0, i * 16, 16, 16, null, 0, 16);
        }
        return out;
    }

    public byte[] mask() {
        byte[] mask = new byte[MASK_BYTES];
        for (int index : indices) {
            mask[index >> 3] |= (byte) (1 << (index & 7));
        }
        return mask;
    }

    public static int[] indicesFromMask(byte[] mask) {
        int n = 0;
        for (byte b : mask) n += Integer.bitCount(b & 0xff);
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < TileImage.CELLS; i++) {
            if ((mask[i >> 3] & (1 << (i & 7))) != 0) out[k++] = i;
        }
        return out;
    }

    private static byte[] stripPng(int[][] cells, int from, int to) throws IOException {
        BufferedImage img = new BufferedImage(16, 16 * (to - from), BufferedImage.TYPE_INT_ARGB);
        for (int i = from; i < to; i++) {
            img.setRGB(0, (i - from) * 16, 16, 16, cells[i], 0, 16);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return bytes.toByteArray();
    }
}
