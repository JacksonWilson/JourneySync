package com.donkeytt.journeysync.client;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.donkeytt.journeysync.data.TileId;
import com.donkeytt.journeysync.data.TileImage;

import journeymap.client.model.ChunkMD;
import journeymap.client.model.MapType;
import journeymap.client.model.RegionCoord;
import journeymap.client.model.RegionImageCache;
import journeymap.client.model.RegionImageSet;

/**
 * Collects the chunks JourneyMap draws, for upload. {@code MixinChunkRenderController} calls {@link #captureRendered}
 * on JourneyMap's mapping thread each time a chunk render finishes. JourneyMap redraws nearby chunks over and over, so
 * a cell is only queued if it differs from the last copy sent.
 */
public final class CellCapture {

    private static final class CellKey {

        final TileId tile;
        final int index;

        CellKey(TileId tile, int index) {
            this.tile = tile;
            this.index = index;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CellKey && ((CellKey) o).index == index && ((CellKey) o).tile.equals(tile);
        }

        @Override
        public int hashCode() {
            return tile.hashCode() * 1031 + index;
        }
    }

    private static volatile boolean enabled;
    private static volatile File worldDir;
    private static final ConcurrentHashMap<CellKey, int[]> PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CellKey, Integer> LAST_SENT = new ConcurrentHashMap<>();

    private CellCapture() {}

    /** Starts capturing chunks drawn into this JourneyMap world folder. Null stops. */
    public static void enable(File dir) {
        worldDir = dir;
        enabled = dir != null;
    }

    public static void reset() {
        enabled = false;
        worldDir = null;
        PENDING.clear();
        LAST_SENT.clear();
    }

    /**
     * Called after JourneyMap rendered a chunk successfully. A surface render draws day, night and topo; a cave or
     * topo render draws just that map type. The chunk's cells in the region images now hold its final pixels.
     */
    public static void captureRendered(RegionCoord region, MapType mapType, ChunkMD chunk) {
        if (!enabled || !region.worldDir.equals(worldDir)) return;
        RegionImageSet set = RegionImageCache.instance()
            .getRegionImageSet(region);
        if (mapType.isUnderground() || mapType.isTopo()) {
            capture(region, chunk, mapType, set.getChunkImage(chunk, mapType));
        } else {
            capture(
                region,
                chunk,
                MapType.day(region.dimension),
                set.getChunkImage(chunk, MapType.day(region.dimension)));
            capture(
                region,
                chunk,
                MapType.night(region.dimension),
                set.getChunkImage(chunk, MapType.night(region.dimension)));
            capture(
                region,
                chunk,
                MapType.topo(region.dimension),
                set.getChunkImage(chunk, MapType.topo(region.dimension)));
        }
    }

    private static void capture(RegionCoord region, ChunkMD chunk, MapType mapType, BufferedImage cellImage) {
        if (cellImage == null) return;
        byte type = typeOf(mapType);
        if (type < 0) return;
        int[] pixels = cellImage.getRGB(0, 0, 16, 16, null, 0, 16);
        if (TileImage.isEmpty(pixels)) return;
        CellKey key = new CellKey(
            new TileId(region.dimension, type, region.regionX, region.regionZ),
            TileId.cellIndex(chunk.getCoord().chunkXPos, chunk.getCoord().chunkZPos));
        Integer sent = LAST_SENT.get(key);
        if (sent != null && sent == Arrays.hashCode(pixels)) {
            PENDING.remove(key);
            return;
        }
        PENDING.put(key, pixels);
    }

    /** Takes every queued cell, grouped by tile, in ascending cell order. */
    public static Map<TileId, TreeMap<Integer, int[]>> drain() {
        Map<TileId, TreeMap<Integer, int[]>> out = new HashMap<>();
        Iterator<Map.Entry<CellKey, int[]>> it = PENDING.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<CellKey, int[]> e = it.next();
            CellKey key = e.getKey();
            int[] pixels = e.getValue();
            if (!PENDING.remove(key, pixels)) continue;
            LAST_SENT.put(key, Arrays.hashCode(pixels));
            out.computeIfAbsent(key.tile, k -> new TreeMap<>())
                .put(key.index, pixels);
        }
        return out;
    }

    /** Remembers cells received from the server, so drawing the same pixels again doesn't send them back. */
    public static void noteReceived(TileId tile, int index, int[] pixels) {
        LAST_SENT.put(new CellKey(tile, index), Arrays.hashCode(pixels));
    }

    /** True if these exact pixels were already sent or received this session. */
    public static boolean isKnown(TileId tile, int index, int[] pixels) {
        Integer known = LAST_SENT.get(new CellKey(tile, index));
        return known != null && known == Arrays.hashCode(pixels);
    }

    public static int pendingCount() {
        return PENDING.size();
    }

    static byte typeOf(MapType mapType) {
        if (mapType.isUnderground()) {
            return mapType.vSlice != null && mapType.vSlice >= 0 && mapType.vSlice < 16 ? mapType.vSlice.byteValue()
                : -1;
        }
        if (mapType.isDay()) return TileId.DAY;
        if (mapType.isNight()) return TileId.NIGHT;
        if (mapType.isTopo()) return TileId.TOPO;
        return -1;
    }
}
