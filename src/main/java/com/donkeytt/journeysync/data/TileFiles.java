package com.donkeytt.journeysync.data;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.imageio.ImageIO;

/** Reads and writes tile PNGs and revision files. Writes go to a temp file first, then replace the target. */
public final class TileFiles {

    private TileFiles() {}

    /** Reads a tile PNG, or returns a blank tile if the file is missing or isn't a 512x512 image. */
    public static int[] readPixels(File png) throws IOException {
        int[] pixels = new int[TileImage.SIZE * TileImage.SIZE];
        if (!png.isFile()) return pixels;
        BufferedImage img = ImageIO.read(png);
        if (img == null || img.getWidth() != TileImage.SIZE || img.getHeight() != TileImage.SIZE) return pixels;
        img.getRGB(0, 0, TileImage.SIZE, TileImage.SIZE, pixels, 0, TileImage.SIZE);
        return pixels;
    }

    public static void writePixels(File png, int[] pixels) throws IOException {
        BufferedImage img = new BufferedImage(TileImage.SIZE, TileImage.SIZE, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, TileImage.SIZE, TileImage.SIZE, pixels, 0, TileImage.SIZE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        writeAtomic(png, bytes.toByteArray());
    }

    public static long[] readRevs(File file) throws IOException {
        long[] revs = new long[TileImage.CELLS];
        if (!file.isFile()) return revs;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file.toPath())))) {
            for (int i = 0; i < revs.length; i++) revs[i] = in.readLong();
        }
        return revs;
    }

    public static void writeRevs(File file, long[] revs) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            for (long rev : revs) out.writeLong(rev);
        }
        writeAtomic(file, bytes.toByteArray());
    }

    public static void writeAtomic(File target, byte[] data) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        File tmp = new File(dir, target.getName() + ".tmp");
        Files.write(tmp.toPath(), data);
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
