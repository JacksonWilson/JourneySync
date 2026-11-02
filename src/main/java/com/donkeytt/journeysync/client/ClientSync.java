package com.donkeytt.journeysync.client;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;

import com.donkeytt.journeysync.Config;
import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.Tags;
import com.donkeytt.journeysync.data.CellPatch;
import com.donkeytt.journeysync.data.TileFiles;
import com.donkeytt.journeysync.data.TileId;
import com.donkeytt.journeysync.data.TileImage;
import com.donkeytt.journeysync.network.MainThreadQueue;
import com.donkeytt.journeysync.network.PacketHandler;
import com.donkeytt.journeysync.network.RateLimiter;
import com.donkeytt.journeysync.network.packets.CellPatchPacket;
import com.donkeytt.journeysync.network.packets.HelloPacket;
import com.donkeytt.journeysync.network.packets.WaypointChangePacket;

/**
 * The client side of map sharing. Network handlers only queue work; one worker thread applies received cells, sends
 * captured ones, uploads the existing map once and syncs waypoints. The client tick waits for the server's greeting
 * and for JourneyMap to settle on this world's folder, then starts the sync; anything else that must run on the main
 * thread goes through {@link MainThreadQueue#CLIENT}.
 */
public final class ClientSync {

    private static final ClientSync INSTANCE = new ClientSync();
    /** Wait this long after joining before syncing, so JourneyMap has its world id and final folder. */
    private static final long JOIN_GRACE_MILLIS = 3_000;
    /**
     * JourneyMap first maps into a folder without the world id, then moves to {@code <name>_<id>} when JourneyMap
     * Server's world id packet arrives. Wait for the id this long; servers without JourneyMap Server never send one.
     */
    private static final long WORLD_ID_WAIT_MILLIS = 15_000;
    /** A server with JourneySync greets the player at login. Without a greeting by then, it doesn't have it. */
    private static final long SERVER_HELLO_WAIT_MILLIS = 10_000;
    private static final long DIR_CHECK_MILLIS = 5_000;
    private static final long DISK_FLUSH_MILLIS = 250;
    private static final long WAYPOINT_SCAN_MILLIS = 5_000;
    private static final long STATE_SAVE_MILLIS = 10_000;

    public static ClientSync instance() {
        return INSTANCE;
    }

    private enum Phase {
        OFFLINE,
        WAITING_FOR_JOURNEYMAP,
        CATCHING_UP,
        SYNCED,
        /** No JourneyMap 5, or the server speaks another protocol. */
        UNSUPPORTED,
        /** The server didn't greet us: it doesn't have JourneySync. A late greeting still starts syncing. */
        NO_SERVER_MOD
    }

    // ---- shared between threads
    private volatile Phase phase = Phase.OFFLINE;
    private volatile File worldDir;
    private volatile boolean paused;
    private volatile long cellsSent;
    private volatile long cellsReceived;
    private volatile int seedDone;
    private volatile int seedTotal;
    private volatile long lastRevShown;
    private final BlockingQueue<Runnable> events = new LinkedBlockingQueue<>();

    // ---- main thread only
    private long joinedAt;
    private long lastDirCheck;

    // ---- worker thread only
    private Thread worker;
    private ClientState state;
    private File stateFile;
    private boolean stateDirty;
    private long lastStateSave;
    private WaypointSync waypoints;
    private RateLimiter limiter = new RateLimiter(0);
    private String pendingWaypointBlob;
    /** Received cells not yet drawn, by tile. Batched so a tile split over several packets is written once. */
    private final Map<TileId, TreeMap<Integer, int[]>> pendingApply = new LinkedHashMap<>();
    private long pendingApplySince;
    /** Tile files that existed when we joined, to upload once as fill-only. */
    private Deque<File> seedFiles;
    private List<File> seedSnapshot;
    private long lastUpload;
    private long lastWaypointScan;

    private ClientSync() {}

    // ------------------------------------------------------------------ connection (network thread)

    public void onConnect(boolean local) {
        startWorker();
        phase = local ? Phase.OFFLINE : Phase.WAITING_FOR_JOURNEYMAP;
        worldDir = null;
        joinedAt = 0;
        events.add(this::beginSession);
    }

    public void onDisconnect() {
        phase = Phase.OFFLINE;
        CellCapture.enable(null);
        events.add(this::endSession);
    }

    // ------------------------------------------------------------------ packets (network thread)

    public void onServerHello(int protocol, String worldId) {
        ServerInfo.set(protocol, worldId);
        if (!ServerInfo.compatible()) {
            JourneySync.LOG.warn(
                "The server runs JourneySync protocol {}, this client {}; not syncing",
                protocol,
                PacketHandler.PROTOCOL_VERSION);
            chat("journeysync.chat.version_mismatch");
        } else if (phase == Phase.NO_SERVER_MOD) {
            phase = Phase.WAITING_FOR_JOURNEYMAP;
        }
    }

    public void onPatch(CellPatch patch) {
        events.add(() -> queueApply(patch));
    }

    public void onMark(long rev, boolean catchUpDone) {
        events.add(() -> handleMark(rev, catchUpDone));
    }

    public void onWaypointBlob(String json) {
        if (!Config.downloadEnabled) return;
        events.add(() -> {
            if (worldDir == null) {
                pendingWaypointBlob = json;
            } else {
                applyWaypointBlob(json);
            }
        });
    }

    // ------------------------------------------------------------------ main thread

    public void onClientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (phase != Phase.WAITING_FOR_JOURNEYMAP && phase != Phase.CATCHING_UP && phase != Phase.SYNCED
            || mc.thePlayer == null
            || mc.theWorld == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (phase == Phase.WAITING_FOR_JOURNEYMAP) {
            if (joinedAt == 0) joinedAt = now;
            if (!ServerInfo.received()) {
                if (now - joinedAt > SERVER_HELLO_WAIT_MILLIS) {
                    phase = Phase.NO_SERVER_MOD;
                    JourneySync.LOG.info("The server didn't greet us; it doesn't have JourneySync. Not syncing maps.");
                }
                return;
            }
            if (!ServerInfo.compatible() || !JmVersion.isSupported()) {
                phase = Phase.UNSUPPORTED;
                if (!JmVersion.isSupported()) JourneySync.LOG.info("JourneyMap 5 isn't installed; not syncing maps");
                return;
            }
            if (now - joinedAt < JOIN_GRACE_MILLIS || !JmBridge.isMapping()) return;
            if (JmBridge.currentWorldId() == null && now - joinedAt < WORLD_ID_WAIT_MILLIS) return;
            File dir = JmBridge.worldDir();
            if (dir == null) return;
            worldDir = dir;
            lastDirCheck = now;
            phase = Phase.CATCHING_UP;
            events.add(this::startMapSync);
        } else if (now - lastDirCheck > DIR_CHECK_MILLIS && JmBridge.isMapping()) {
            lastDirCheck = now;
            File dir = JmBridge.worldDir();
            if (dir != null && !dir.equals(worldDir)) {
                JourneySync.LOG.info("JourneyMap moved this world's map to {}", dir);
                events.add(() -> restartInFolder(dir));
            }
        }
    }

    // ------------------------------------------------------------------ commands (main thread)

    public void setPaused(boolean value) {
        paused = value;
        chat(value ? "journeysync.chat.paused" : "journeysync.chat.resumed");
    }

    /** Asks the server for the whole shared map again, for when the local map files were deleted. */
    public boolean resync() {
        if (phase != Phase.CATCHING_UP && phase != Phase.SYNCED) return false;
        events.add(() -> {
            state.lastRev = 0;
            saveState();
            phase = Phase.CATCHING_UP;
            sendHello();
        });
        return true;
    }

    public String statusLine() {
        String key = "journeysync.status." + phase.name()
            .toLowerCase();
        String text = StatCollector.translateToLocal(key);
        return text + " | "
            + StatCollector.translateToLocalFormatted(
                "journeysync.status.progress",
                cellsReceived,
                cellsSent,
                CellCapture.pendingCount(),
                seedDone,
                seedTotal,
                lastRevShown);
    }

    // ------------------------------------------------------------------ worker

    private void startWorker() {
        synchronized (this) {
            if (worker != null && worker.isAlive()) return;
            worker = new Thread(this::workerLoop, "JourneySync-Client");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void workerLoop() {
        while (true) {
            try {
                Runnable event = events.poll(50, TimeUnit.MILLISECONDS);
                while (event != null) {
                    event.run();
                    event = events.poll();
                }
                if (phase == Phase.CATCHING_UP || phase == Phase.SYNCED) periodicWork();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                JourneySync.LOG.error("JourneySync client worker error", t);
            }
        }
    }

    private void periodicWork() throws IOException {
        long now = System.currentTimeMillis();
        if (!pendingApply.isEmpty() && now - pendingApplySince > DISK_FLUSH_MILLIS) flushApply();
        if (pendingWaypointBlob != null && worldDir != null) {
            String blob = pendingWaypointBlob;
            pendingWaypointBlob = null;
            applyWaypointBlob(blob);
        }
        if (paused) return;
        if (Config.uploadEnabled && now - lastUpload > Config.uploadIntervalMillis) {
            lastUpload = now;
            uploadCaptured();
        }
        if (phase == Phase.SYNCED && worldDir != null && now - lastWaypointScan > WAYPOINT_SCAN_MILLIS) {
            lastWaypointScan = now;
            waypoints.scan(worldDir);
            waypoints.flush();
        }
        if (seedFiles != null && events.isEmpty()) seedNext();
        if (stateDirty && now - lastStateSave > STATE_SAVE_MILLIS) saveState();
    }

    private void beginSession() {
        String serverId = computeServerId();
        stateFile = new File(Minecraft.getMinecraft().mcDataDir, "config/journeysync/state-" + serverId + ".json");
        state = ClientState.load(stateFile);
        waypoints = new WaypointSync(state, record -> PacketHandler.sendToServer(new WaypointChangePacket(record)));
        limiter = new RateLimiter(Config.uploadKBps * 1024L);
        pendingApply.clear();
        pendingWaypointBlob = null;
        seedFiles = null;
        seedSnapshot = null;
        cellsSent = cellsReceived = 0;
        seedDone = seedTotal = 0;
        lastRevShown = state.lastRev;
        CellCapture.reset();
        JourneySync.LOG
            .info("Connected to {}; will sync once the server greets us and JourneyMap is mapping", serverId);
    }

    private void endSession() {
        try {
            flushApply();
        } catch (IOException e) {
            JourneySync.LOG.error("Failed writing received cells", e);
        }
        if (state != null) saveState();
        CellCapture.reset();
        seedFiles = null;
    }

    /** The server greeted us and JourneyMap has settled on this world's folder. */
    private void startMapSync() {
        String worldId = ServerInfo.worldId();
        if (!worldId.equals(state.worldId)) {
            // Nothing from this map has been applied yet, so the folder still holds only what the player drew.
            JourneySync.LOG.info("Server's shared map is {}; starting fresh for it", worldId);
            state.resetFor(worldId);
            saveState();
        }
        sendHello();
        if (Config.uploadEnabled) CellCapture.enable(worldDir);
        chat("journeysync.chat.sync_start");
    }

    /**
     * JourneyMap moved this world's map to another folder, usually because JourneyMap Server's world id arrived after
     * the 15 s wait. Everything received so far went into the old folder, and the existing-map snapshot came from it,
     * so start over in the new folder: receive the whole map again, take a new snapshot, re-sync waypoints.
     */
    private void restartInFolder(File dir) {
        try {
            flushApply();
        } catch (IOException e) {
            JourneySync.LOG.error("Failed writing received cells before the folder moved", e);
        }
        pendingApply.clear();
        worldDir = dir;
        if (phase != Phase.CATCHING_UP && phase != Phase.SYNCED) return;
        JourneySync.LOG.info("Starting the sync over in {}", dir);
        CellCapture.reset();
        seedFiles = null;
        seedTotal = seedDone = 0;
        state.resetFor(state.worldId);
        saveState();
        phase = Phase.CATCHING_UP;
        sendHello();
        if (Config.uploadEnabled) CellCapture.enable(dir);
    }

    /** Asks the server for every cell newer than what this client has. The server answers with a catch-up. */
    private void sendHello() {
        long since = state.lastRev;
        if (since > 0 && !hasLocalMap(worldDir)) {
            JourneySync.LOG.info("No local map files for this server; asking for the whole shared map");
            since = 0;
        }
        // Snapshot the files the player drew before syncing; received cells will be merged into them.
        seedSnapshot = Config.seedExistingMap && !state.seeded ? listTileFiles(worldDir) : null;
        PacketHandler.sendToServer(new HelloPacket(Tags.VERSION, state.worldId, since));
        JourneySync.LOG.info("Asked for map {} from revision {}", state.worldId, since);
    }

    private void handleMark(long rev, boolean catchUpDone) {
        try {
            flushApply();
        } catch (IOException e) {
            JourneySync.LOG.error("Failed writing received cells; not advancing the sync mark", e);
            return;
        }
        if (rev > state.lastRev) {
            state.lastRev = rev;
            lastRevShown = rev;
            saveState();
        }
        if (catchUpDone && phase == Phase.CATCHING_UP) {
            phase = Phase.SYNCED;
            chat("journeysync.chat.sync_complete", cellsReceived);
            if (seedSnapshot != null && !seedSnapshot.isEmpty()) {
                seedFiles = new ArrayDeque<>(seedSnapshot);
                seedTotal = seedFiles.size();
                seedDone = 0;
            } else if (Config.seedExistingMap && !state.seeded) {
                state.seeded = true;
                saveState();
            }
            seedSnapshot = null;
        }
    }

    // ------------------------------------------------------------------ applying received cells

    private void queueApply(CellPatch patch) {
        if (!Config.downloadEnabled) return;
        int[][] cells;
        try {
            cells = patch.cells();
        } catch (IOException e) {
            JourneySync.LOG.warn("Dropped a bad cell patch for {}: {}", patch.tile, e.getMessage());
            return;
        }
        if (pendingApply.isEmpty()) pendingApplySince = System.currentTimeMillis();
        TreeMap<Integer, int[]> tile = pendingApply.computeIfAbsent(patch.tile, k -> new TreeMap<>());
        for (int i = 0; i < patch.indices.length; i++) {
            tile.put(patch.indices[i], cells[i]);
            CellCapture.noteReceived(patch.tile, patch.indices[i], cells[i]);
        }
        cellsReceived += patch.indices.length;
    }

    /**
     * Draws received cells: into JourneyMap's in-memory image if it has the region loaded, otherwise into the file.
     * The file lock keeps JourneyMap from loading the file halfway through.
     */
    private void flushApply() throws IOException {
        if (pendingApply.isEmpty() || worldDir == null) return;
        for (Map.Entry<TileId, TreeMap<Integer, int[]>> e : pendingApply.entrySet()) {
            Cells cells = new Cells(e.getValue());
            File file = JmBridge.regionFile(worldDir, e.getKey());
            synchronized (JmFiles.lock(file)) {
                HolderAccess loaded = JmFiles.loaded(file);
                if (loaded != null) {
                    loaded.journeysync$drawCells(cells.indices, cells.pixels);
                } else {
                    int[] pixels = TileFiles.readPixels(file);
                    TileImage tile = new TileImage(pixels, new long[TileImage.CELLS]);
                    boolean changed = false;
                    for (int i = 0; i < cells.indices.length; i++) {
                        changed |= tile.merge(cells.indices[i], cells.pixels[i], false);
                    }
                    if (changed) TileFiles.writePixels(file, pixels);
                }
            }
        }
        pendingApply.clear();
    }

    // ------------------------------------------------------------------ sending

    private void uploadCaptured() throws IOException {
        for (Map.Entry<TileId, TreeMap<Integer, int[]>> e : CellCapture.drain()
            .entrySet()) {
            Cells cells = new Cells(e.getValue());
            sendCells(e.getKey(), false, cells.indices, cells.pixels);
        }
    }

    /** Uploads one tile file from before syncing, as fill-only, skipping cells the server already sent us. */
    private void seedNext() throws IOException {
        File file = seedFiles.poll();
        if (file == null) {
            seedFiles = null;
            state.seeded = true;
            saveState();
            chat("journeysync.chat.seed_complete", seedTotal);
            return;
        }
        seedDone++;
        TileId tile = tileIdOf(file);
        if (tile == null) return;
        int[] pixels;
        synchronized (JmFiles.lock(file)) {
            HolderAccess loaded = JmFiles.loaded(file);
            pixels = loaded != null ? loaded.journeysync$pixels() : TileFiles.readPixels(file);
        }
        if (pixels == null) return;
        TileImage image = new TileImage(pixels, new long[TileImage.CELLS]);
        TreeMap<Integer, int[]> unknown = new TreeMap<>();
        for (int i = 0; i < TileImage.CELLS; i++) {
            int[] cell = image.cell(i);
            if (!TileImage.isEmpty(cell) && !CellCapture.isKnown(tile, i, cell)) unknown.put(i, cell);
        }
        if (unknown.isEmpty()) return;
        Cells cells = new Cells(unknown);
        sendCells(tile, true, cells.indices, cells.pixels);
    }

    private void sendCells(TileId tile, boolean fillOnly, int[] indices, int[][] cells) throws IOException {
        for (CellPatch patch : CellPatch.encode(tile, fillOnly, indices, cells)) {
            CellPatchPacket packet = new CellPatchPacket(patch);
            limiter.acquire(packet.size());
            PacketHandler.sendToServer(packet);
            cellsSent += patch.indices.length;
        }
    }

    // ------------------------------------------------------------------ waypoints

    private void applyWaypointBlob(String json) {
        if (waypoints.applyServerBlob(json, worldDir)) {
            MainThreadQueue.CLIENT.execute(JmBridge::resetWaypoints);
            stateDirty = true;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A tile's cells as parallel arrays, in ascending index order. */
    private static final class Cells {

        final int[] indices;
        final int[][] pixels;

        Cells(TreeMap<Integer, int[]> byIndex) {
            indices = new int[byIndex.size()];
            pixels = new int[indices.length][];
            int k = 0;
            for (Map.Entry<Integer, int[]> c : byIndex.entrySet()) {
                indices[k] = c.getKey();
                pixels[k++] = c.getValue();
            }
        }
    }

    private static void chat(String key, Object... args) {
        MainThreadQueue.CLIENT.execute(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentTranslation(key, args));
        });
    }

    private void saveState() {
        if (state == null || stateFile == null) return;
        state.save(stateFile);
        stateDirty = false;
        lastStateSave = System.currentTimeMillis();
    }

    /** Region image files under JourneyMap's world folder, for every dimension and map type. */
    private static List<File> listTileFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] dims = dir == null ? null : dir.listFiles((d, name) -> name.startsWith("DIM"));
        if (dims == null) return out;
        for (File dim : dims) {
            File[] types = dim.listFiles(File::isDirectory);
            if (types == null) continue;
            for (File type : types) {
                if (TileId.parseType(type.getName()) < 0) continue;
                File[] files = type.listFiles((d, name) -> name.endsWith(".png"));
                if (files != null) java.util.Collections.addAll(out, files);
            }
        }
        return out;
    }

    private static boolean hasLocalMap(File dir) {
        return !listTileFiles(dir).isEmpty();
    }

    private static TileId tileIdOf(File png) {
        File type = png.getParentFile();
        File dim = type == null ? null : type.getParentFile();
        if (dim == null) return null;
        String name = png.getName();
        return TileId.parse(dim.getName() + "/" + type.getName() + "/" + name.substring(0, name.length() - 4));
    }

    private static String computeServerId() {
        Minecraft mc = Minecraft.getMinecraft();
        ServerData server = mc.func_147104_D();
        String raw = server != null ? server.serverName + "_" + server.serverIP : "unknown";
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
