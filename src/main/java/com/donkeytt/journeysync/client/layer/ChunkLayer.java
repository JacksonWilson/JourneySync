package com.donkeytt.journeysync.client.layer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.client.Minecraft;

import com.donkeytt.journeysync.client.ChunkLayerState;
import com.donkeytt.journeysync.data.ChunkEdges;
import com.donkeytt.journeysync.data.ChunkSnapshot;
import com.gtnewhorizons.navigator.api.NavigatorApi;
import com.gtnewhorizons.navigator.api.model.SupportedMods;
import com.gtnewhorizons.navigator.api.model.layers.InteractableLayerManager;
import com.gtnewhorizons.navigator.api.model.layers.LayerRenderer;
import com.gtnewhorizons.navigator.api.model.layers.UniversalInteractableRenderer;
import com.gtnewhorizons.navigator.api.model.locations.ILocationProvider;

/**
 * The Navigator layer. Shows the latest snapshot for the player's dimension, and asks the server for a fresh one
 * every few seconds while a map with the layer on is drawing.
 */
public class ChunkLayer extends InteractableLayerManager {

    public static final ChunkLayer INSTANCE = new ChunkLayer();

    private ChunkLayer() {
        super(ChunkLayerButton.INSTANCE);
    }

    /** Registers the layer and its toggle button. Call once during client init, only when Navigator is loaded. */
    public static void register() {
        NavigatorApi.registerLayerManager(INSTANCE);
        // Each snapshot replaces every chunk, so drop the cached locations and their render steps.
        ChunkLayerState.setListener(INSTANCE::clearCurrentCache);
    }

    @Override
    protected LayerRenderer addLayerRenderer(InteractableLayerManager manager, SupportedMods mod) {
        return new UniversalInteractableRenderer(manager)
            .withRenderStep(location -> new ChunkStep((ChunkLocation) location));
    }

    // The minimap recaches every frame, so keep the last answer and reuse it while nothing changed.
    private ChunkSnapshot lastSnapshot;
    private final List<ChunkLocation> all = new ArrayList<>();
    private final List<ChunkLocation> visible = new ArrayList<>();
    private int lastMinX, lastMaxX, lastMinZ, lastMaxZ;

    @Override
    protected Collection<? extends ILocationProvider> generateVisibleLocations(int minBlockX, int minBlockZ,
        int maxBlockX, int maxBlockZ, int dimension) {
        ChunkSnapshot snapshot = ChunkLayerState.snapshotFor(dimension);
        int minX = minBlockX >> 4, maxX = maxBlockX >> 4, minZ = minBlockZ >> 4, maxZ = maxBlockZ >> 4;
        if (snapshot == lastSnapshot && minX == lastMinX && maxX == lastMaxX && minZ == lastMinZ && maxZ == lastMaxZ) {
            return visible;
        }
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot;
            all.clear();
            if (snapshot != null) {
                int[][] edges = ChunkEdges.compute(snapshot);
                for (int i = 0; i < edges.length; i++) {
                    all.add(new ChunkLocation(snapshot, snapshot.chunks.get(i), edges[i][0], edges[i][1]));
                }
            }
        }
        lastMinX = minX;
        lastMaxX = maxX;
        lastMinZ = minZ;
        lastMaxZ = maxZ;
        visible.clear();
        for (ChunkLocation l : all) {
            ChunkSnapshot.Chunk c = l.chunk;
            if (c.x >= minX && c.x <= maxX && c.z >= minZ && c.z <= maxZ) visible.add(l);
        }
        return visible;
    }

    @Override
    public void onUpdatePre(int minX, int maxX, int minZ, int maxZ) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) ChunkLayerState.requestIfDue(mc.thePlayer.dimension);
    }

    /** Minimap redraws seen, for the smoke test. */
    int minimapRecaches;

    @Override
    public void recacheMiniMap(int centerBlockX, int centerBlockZ, int blockWidth, int blockHeight) {
        minimapRecaches++;
        super.recacheMiniMap(centerBlockX, centerBlockZ, blockWidth, blockHeight);
    }

    @Override
    public void onOpenMap() {
        ChunkLayerState.requestSoon();
    }

    @Override
    public void onLayerToggled(boolean toEnable) {
        super.onLayerToggled(toEnable);
        if (toEnable) ChunkLayerState.requestSoon();
    }
}
