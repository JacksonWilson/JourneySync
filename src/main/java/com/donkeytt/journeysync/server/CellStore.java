package com.donkeytt.journeysync.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.data.TileFiles;
import com.donkeytt.journeysync.data.TileId;
import com.donkeytt.journeysync.data.TileImage;
import com.google.gson.Gson;

/**
 * The server's shared map: every tile anyone has sent, under {@code <world>/journeysync/cells/}, as a PNG plus a
 * {@code .rev} file of per-cell revisions. A server-wide counter gives each changed cell the next revision, so a
 * player who has everything up to revision N only needs the cells above N.
 *
 * <p>
 * Not thread-safe; {@link ServerSync}'s worker thread is the only user.
 * </p>
 */
public final class CellStore {

    private static final Gson GSON = new Gson();

    private static final class State {

        String worldId;
        long rev;
    }

    private final File root;
    private final File stateFile;
    private final int cacheSize;
    private final Map<TileId, TileImage> cache;
    private final Set<TileId> dirty = new HashSet<>();
    /** Highest cell revision in each stored tile. */
    private final Map<TileId, Long> maxRevs = new HashMap<>();
    private State state = new State();

    public CellStore(File dataDir, int cacheSize) {
        this.root = new File(dataDir, "cells");
        this.stateFile = new File(dataDir, "cells.json");
        this.cacheSize = Math.max(4, cacheSize);
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
    }

    public void load() throws IOException {
        if (stateFile.isFile()) {
            State loaded = GSON
                .fromJson(new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8), State.class);
            if (loaded != null) state = loaded;
        }
        if (state.worldId == null || state.worldId.isEmpty()) {
            state.worldId = UUID.randomUUID()
                .toString();
        }
        maxRevs.clear();
        File[] dims = root.listFiles();
        if (dims != null) {
            for (File dim : dims) {
                File[] types = dim.listFiles();
                if (types == null) continue;
                for (File type : types) {
                    File[] files = type.listFiles((d, name) -> name.endsWith(".rev"));
                    if (files == null) continue;
                    for (File f : files) {
                        String name = f.getName();
                        TileId id = TileId
                            .parse(dim.getName() + "/" + type.getName() + "/" + name.substring(0, name.length() - 4));
                        if (id == null) continue;
                        long max = 0;
                        for (long r : TileFiles.readRevs(f)) max = Math.max(max, r);
                        maxRevs.put(id, max);
                        state.rev = Math.max(state.rev, max);
                    }
                }
            }
        }
        saveState();
        JourneySync.LOG.info("Shared map {}: {} tiles, revision {}", state.worldId, maxRevs.size(), state.rev);
    }

    public String worldId() {
        return state.worldId;
    }

    public long rev() {
        return state.rev;
    }

    public int tileCount() {
        return maxRevs.size();
    }

    /**
     * Merges cells into a tile. Returns the indices of cells whose pixels changed; each gets a new revision. Cells
     * that didn't change keep theirs, so players aren't sent pixels they already have.
     */
    public int[] merge(TileId id, int[] indices, int[][] cells, boolean fillOnly) throws IOException {
        TileImage tile = get(id);
        int[] changed = new int[indices.length];
        int n = 0;
        for (int i = 0; i < indices.length; i++) {
            if (tile.merge(indices[i], cells[i], fillOnly)) {
                tile.revs[indices[i]] = ++state.rev;
                changed[n++] = indices[i];
            }
        }
        if (n > 0) {
            dirty.add(id);
            maxRevs.put(id, state.rev);
        }
        return java.util.Arrays.copyOf(changed, n);
    }

    /** Tiles that have a cell newer than {@code rev}. */
    public List<TileId> tilesNewerThan(long rev) {
        List<TileId> out = new ArrayList<>();
        for (Map.Entry<TileId, Long> e : maxRevs.entrySet()) {
            if (e.getValue() > rev) out.add(e.getKey());
        }
        return out;
    }

    /** Indices of a tile's cells newer than {@code rev}. */
    public int[] cellsNewerThan(TileId id, long rev) throws IOException {
        TileImage tile = get(id);
        int[] out = new int[TileImage.CELLS];
        int n = 0;
        for (int i = 0; i < TileImage.CELLS; i++) {
            if (tile.revs[i] > rev) out[n++] = i;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    public TileImage get(TileId id) throws IOException {
        TileImage tile = cache.get(id);
        if (tile != null) return tile;
        tile = new TileImage(TileFiles.readPixels(png(id)), TileFiles.readRevs(revFile(id)));
        cache.put(id, tile);
        evict();
        return tile;
    }

    /** Writes every changed tile and the revision counter. */
    public void flush() {
        for (TileId id : new ArrayList<>(dirty)) {
            write(id);
        }
        saveState();
    }

    private void evict() {
        Iterator<Map.Entry<TileId, TileImage>> it = cache.entrySet()
            .iterator();
        while (cache.size() > cacheSize && it.hasNext()) {
            Map.Entry<TileId, TileImage> e = it.next();
            if (dirty.contains(e.getKey())) write(e.getKey(), e.getValue());
            it.remove();
        }
    }

    private void write(TileId id) {
        // Not cache.get(): that reorders the access-ordered map.
        for (Map.Entry<TileId, TileImage> e : cache.entrySet()) {
            if (e.getKey()
                .equals(id)) {
                write(id, e.getValue());
                return;
            }
        }
    }

    private void write(TileId id, TileImage tile) {
        try {
            TileFiles.writePixels(png(id), tile.pixels);
            TileFiles.writeRevs(revFile(id), tile.revs);
            dirty.remove(id);
        } catch (IOException e) {
            JourneySync.LOG.error("Failed writing shared tile {}", id, e);
        }
    }

    private void saveState() {
        try {
            TileFiles.writeAtomic(
                stateFile,
                GSON.toJson(state)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            JourneySync.LOG.error("Failed writing {}", stateFile, e);
        }
    }

    private File png(TileId id) {
        return new File(root, id.relPath() + ".png");
    }

    private File revFile(TileId id) {
        return new File(root, id.relPath() + ".rev");
    }
}
