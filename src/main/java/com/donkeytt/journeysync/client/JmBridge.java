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

    /** True once JourneyMap is mapping, which means it has settled on the folder for this world. */
    public static boolean isMapping() {
        JourneymapClient jm = JourneymapClient.getInstance();
        return jm != null && Boolean.TRUE.equals(jm.isMapping());
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
