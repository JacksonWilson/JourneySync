package com.donkeytt.journeysync.client;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.data.TileFiles;
import com.donkeytt.journeysync.data.TileImage;

/**
 * Map folders JourneyMap made for the same world under other server-list names.
 *
 * <p>
 * JourneyMap names a server's folder after its server-list entry plus the world id JourneyMap Server sends, so renaming
 * the entry starts an empty folder and leaves the old map behind. Before a player's first upload, JourneySync fills
 * the current folder from those folders: empty pixels only, so the current map wins, plus waypoints the current
 * folder doesn't have. The usual seed then shares all of it, and the old map shows up again locally.
 * </p>
 */
final class OlderMaps {

    /** What a merge added to the current folder. */
    static final class Result {

        int folders;
        int tiles;
        int waypoints;
    }

    private OlderMaps() {}

    /** Other folders for this world id next to {@code worldDir}. Empty if JourneyMap didn't use a world id. */
    static List<File> find(File worldDir, String jmWorldId) {
        List<File> out = new ArrayList<>();
        if (worldDir == null || jmWorldId == null || jmWorldId.isEmpty()) return out;
        String suffix = "_" + jmWorldId.replaceAll("\\W+", "~");
        File parent = worldDir.getParentFile();
        if (!worldDir.getName()
            .endsWith(suffix) || parent == null) return out;
        File[] dirs = parent.listFiles(
            d -> d.isDirectory() && d.getName()
                .endsWith(suffix) && !d.equals(worldDir));
        if (dirs != null) java.util.Collections.addAll(out, dirs);
        // Most recently drawn first, so where two older maps disagree the newer one fills the gap.
        Map<File, Long> newest = new HashMap<>();
        for (File d : out) newest.put(d, newestTile(d));
        out.sort((a, b) -> Long.compare(newest.get(b), newest.get(a)));
        return out;
    }

    private static long newestTile(File dir) {
        long newest = 0;
        for (File f : TileFiles.listTiles(dir)) newest = Math.max(newest, f.lastModified());
        return newest;
    }

    /** Fills {@code into} from each of {@code folders}. Worker thread. */
    static Result mergeAll(List<File> folders, File into) {
        Result result = new Result();
        for (File from : folders) {
            try {
                result.tiles += mergeTiles(from, into);
                result.waypoints += copyWaypoints(from, into);
                result.folders++;
                JourneySync.LOG.info("Merged the older map folder {} into {}", from.getName(), into.getName());
            } catch (IOException e) {
                JourneySync.LOG.error("Failed merging the older map folder {}", from, e);
            }
        }
        return result;
    }

    /** Fills the transparent pixels of {@code into}'s tiles from {@code from}'s. Returns how many tiles changed. */
    private static int mergeTiles(File from, File into) throws IOException {
        int changed = 0;
        Path base = from.toPath();
        for (File src : TileFiles.listTiles(from)) {
            File dst = into.toPath()
                .resolve(base.relativize(src.toPath()))
                .toFile();
            TileImage older = new TileImage(TileFiles.readPixels(src), new long[TileImage.CELLS]);
            synchronized (JmFiles.lock(dst)) {
                HolderAccess loaded = JmFiles.loaded(dst);
                int[] pixels = loaded != null ? loaded.journeysync$pixels() : null;
                if (pixels == null) pixels = TileFiles.readPixels(dst);
                TileImage current = new TileImage(pixels, new long[TileImage.CELLS]);
                int[] cells = current.fillFrom(older);
                if (cells.length == 0) continue;
                if (loaded != null) {
                    int[][] drawn = new int[cells.length][];
                    for (int i = 0; i < cells.length; i++) drawn[i] = current.cell(cells[i]);
                    loaded.journeysync$drawCells(cells, drawn);
                } else {
                    TileFiles.writePixels(dst, pixels);
                }
                changed++;
            }
        }
        return changed;
    }

    /**
     * Copies waypoint files the current folder doesn't have. JourneyMap names them after the waypoint's id, its name
     * and position, so the same waypoint in both folders is copied once.
     */
    private static int copyWaypoints(File from, File into) throws IOException {
        File[] files = new File(from, "waypoints").listFiles(
            f -> f.isFile() && f.getName()
                .endsWith(".json"));
        if (files == null) return 0;
        File target = new File(into, "waypoints");
        int copied = 0;
        for (File f : files) {
            File dst = new File(target, f.getName());
            if (dst.exists()) continue;
            TileFiles.writeAtomic(dst, Files.readAllBytes(f.toPath()));
            copied++;
        }
        return copied;
    }
}
