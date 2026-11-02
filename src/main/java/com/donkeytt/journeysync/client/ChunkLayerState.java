package com.donkeytt.journeysync.client;

import com.donkeytt.journeysync.Config;
import com.donkeytt.journeysync.data.ChunkSnapshot;
import com.donkeytt.journeysync.network.PacketHandler;
import com.donkeytt.journeysync.network.packets.ChunkRequestPacket;

/**
 * The chunk layer's data: the latest chunk loading and claims snapshot, and when to ask for a new one. Client thread
 * only. Nothing is sent to a server that didn't greet us ({@link ServerInfo}).
 */
public final class ChunkLayerState {

    private static ChunkSnapshot snapshot;
    private static long lastRequest;
    private static int lastRequestDim = Integer.MIN_VALUE;
    private static Runnable onSnapshot = () -> {};
    private static int requestsSent;

    private ChunkLayerState() {}

    public static void onDisconnect() {
        snapshot = null;
        lastRequest = 0;
        lastRequestDim = Integer.MIN_VALUE;
        onSnapshot.run();
    }

    public static void onSnapshot(ChunkSnapshot s) {
        snapshot = s;
        onSnapshot.run();
    }

    /** Called after each new snapshot, and after a disconnect clears it. */
    public static void setListener(Runnable listener) {
        onSnapshot = listener;
    }

    public static boolean serverHasMod() {
        return ServerInfo.compatible();
    }

    /** The latest snapshot if it is for this dimension, otherwise null. */
    public static ChunkSnapshot snapshotFor(int dimension) {
        return snapshot != null && snapshot.dimension == dimension ? snapshot : null;
    }

    /**
     * Asks the server for fresh data if the refresh interval has passed or the player changed dimension. Called
     * whenever a map with the layer on redraws, so the data stays fresh only while someone is looking at it.
     */
    public static void requestIfDue(int dimension) {
        if (!ServerInfo.compatible()) return;
        long now = System.currentTimeMillis();
        if (dimension == lastRequestDim && now - lastRequest < Config.chunkLayerRefreshSeconds * 1000L) return;
        lastRequest = now;
        lastRequestDim = dimension;
        requestsSent++;
        PacketHandler.sendToServer(new ChunkRequestPacket());
    }

    /** Requests sent since the game started. For the smoke test. */
    public static int requestsSent() {
        return requestsSent;
    }

    /** Makes the next {@link #requestIfDue} send right away, for example when a map opens. */
    public static void requestSoon() {
        lastRequest = 0;
    }
}
