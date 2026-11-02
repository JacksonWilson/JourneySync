package com.donkeytt.journeysync.client;

import java.io.File;

import net.minecraft.client.Minecraft;

import com.donkeytt.journeysync.data.TileId;

import journeymap.client.JourneymapClient;
import journeymap.client.io.FileHandler;
import journeymap.client.io.RegionImageHandler;
import journeymap.client.model.MapType;
import journeymap.client.model.RegionCoord;
import journeymap.client.waypoint.WaypointStore;

/**
 * Calls into JourneyMap 5's public classes. Only use it after {@link JmVersion#isSupported()}; without JourneyMap 5
 * these calls fail to link.
 */
public final class JmBridge {

    private JmBridge() {}

    /** True once JourneyMap is mapping. It may still move to another folder when the world id arrives. */
    public static boolean isMapping() {
        JourneymapClient jm = JourneymapClient.getInstance();
        return jm != null && Boolean.TRUE.equals(jm.isMapping());
    }

    /** The world id JourneyMap Server sent for this world, or null if none has arrived (or the server has none). */
    public static String currentWorldId() {
        JourneymapClient jm = JourneymapClient.getInstance();
        return jm == null ? null : jm.getCurrentWorldId();
    }

    /** JourneyMap's folder for the current world, or null. Main thread: it may move a legacy folder. */
    public static File worldDir() {
        return FileHandler.getJMWorldDir(Minecraft.getMinecraft());
    }

    /** The region image file JourneyMap uses for this tile. */
    public static File regionFile(File worldDir, TileId tile) {
        return RegionImageHandler.getRegionImageFile(
            RegionCoord.fromRegionPos(worldDir, tile.regionX, tile.regionZ, tile.dimension),
            mapType(tile),
            false);
    }

    private static MapType mapType(TileId tile) {
        switch (tile.type) {
            case TileId.DAY:
                return MapType.day(tile.dimension);
            case TileId.NIGHT:
                return MapType.night(tile.dimension);
            case TileId.TOPO:
                return MapType.topo(tile.dimension);
            default:
                return MapType.underground((int) tile.type, tile.dimension);
        }
    }

    /** Makes JourneyMap re-read the waypoint files. Main thread. */
    public static void resetWaypoints() {
        WaypointStore.instance()
            .reset();
    }
}
