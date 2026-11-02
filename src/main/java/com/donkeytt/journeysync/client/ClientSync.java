package com.donkeytt.journeysync.client;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
import com.donkeytt.journeysync.network.PacketHandler;
import com.donkeytt.journeysync.network.RateLimiter;
import com.donkeytt.journeysync.network.packets.BlobPacket;
import com.donkeytt.journeysync.network.packets.CellPatchPacket;
import com.donkeytt.journeysync.network.packets.HelloAckPacket;
import com.donkeytt.journeysync.network.packets.HelloPacket;
import com.donkeytt.journeysync.network.packets.WaypointChangePacket;

import cpw.mods.fml.common.network.simpleimpl.IMessage;

/**
 * The client side of map sharing. Network handlers only queue work; one worker thread applies received cells, sends
 * captured ones, uploads the existing map once, syncs waypoints and does all sending. The client tick only watches
 * for JourneyMap to be ready and does the few things that must run on the main thread.
 */
public final class ClientSync {

    private static final ClientSync INSTANCE = new ClientSync();
    /** Wait this long after joining before syncing, so JourneyMap has its world id and final folder. */
    private static final long JOIN_GRACE_MILLIS = 3_000;
    private static final long DIR_CHECK_MILLIS = 5_000;
    private static final long DISK_FLUSH_MILLIS = 250;
    private static final long WAYPOINT_SCAN_MILLIS = 5_000;
    private static final long STATE_SAVE_MILLIS = 10_000;
    /** A server with JourneySync answers the hello at once; without an answer by then, assume it doesn't have it. */
    private static final long HELLO_TIMEOUT_MILLIS = 10_000;

    public static ClientSync instance() {
        return INSTANCE;
    }

    private enum Phase {
        OFFLINE,
        WAITING_FOR_JOURNEYMAP,
        HELLO_SENT,
        CATCHING_UP,
        SYNCED,
        UNSUPPORTED,
        /** The server never answered the hello: it doesn't have JourneySync. A late answer still starts syncing. */
        NO_SERVER_MOD
    }

    private static final class BlobAssembly {

        final Map<Integer, byte[]> parts = new HashMap<>();
        int bytes;
    }

    // ---- shared between threads
    private volatile Phase phase = Phase.OFFLINE;
    private volatile File worldDir;
    private volatile boolean paused;
    private volatile boolean needsWaypointReset;
    private volatile long cellsSent;
    private volatile long cellsReceived;
    private volatile int seedDone;
    private volatile int seedTotal;
    private volatile long lastRevShown;
    private volatile long helloSentAt;
    private final BlockingQueue<Runnable> events = new LinkedBlockingQueue<>();
    private final BlockingQueue<ChatComponentTranslation> chat = new LinkedBlockingQueue<>();

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
    private final Map<Byte, BlobAssembly> blobs = new HashMap<>();
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

    public void onHelloAck(HelloAckPacket ack) {
        events.add(() -> handleHelloAck(ack));
    }

    public void onPatch(CellPatch patch) {
        events.add(() -> queueApply(patch));
    }

    public void onMark(long rev, boolean catchUpDone) {
        events.add(() -> handleMark(rev, catchUpDone));
    }

    public void onBlobFragment(BlobPacket packet) {
        events.add(() -> handleBlobFragment(packet));
    }

    // ------------------------------------------------------------------ main thread

    public void onClientTick() {
        ChatComponentTranslation message;
        Minecraft mc = Minecraft.getMinecraft();
        while ((message = chat.poll()) != null) {
            if (mc.thePlayer != null) mc.thePlayer.addChatMessage(message);
        }
        if (needsWaypointReset && JmVersion.isSupported()) {
            needsWaypointReset = false;
            JmBridge.resetWaypoints();
        }
        if (phase == Phase.OFFLINE || phase == Phase.UNSUPPORTED
            || phase == Phase.NO_SERVER_MOD
            || mc.thePlayer == null
            || mc.theWorld == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (phase == Phase.WAITING_FOR_JOURNEYMAP) {
            if (!JmVersion.isSupported()) {
                phase = Phase.UNSUPPORTED;
                JourneySync.LOG.info("JourneyMap 5 isn't installed; not syncing maps");
                return;
            }
            if (joinedAt == 0) joinedAt = now;
            if (now - joinedAt < JOIN_GRACE_MILLIS || !JmBridge.isMapping()) return;
            File dir = JmBridge.worldDir();
            if (dir == null) return;
            worldDir = dir;
            lastDirCheck = now;
            // Set before the phase: the worker times out HELLO_SENT against this, maybe before sendHello has run.
            helloSentAt = now;
            phase = Phase.HELLO_SENT;
            events.add(this::sendHello);
        } else if (now - lastDirCheck > DIR_CHECK_MILLIS && JmBridge.isMapping()) {
            lastDirCheck = now;
            File dir = JmBridge.worldDir();
            if (dir != null && !dir.equals(worldDir)) {
                JourneySync.LOG.info("JourneyMap moved this world's map to {}", dir);
                events.add(() -> {
                    try {
                        flushApply();
                    } catch (IOException e) {
                        JourneySync.LOG.error("Failed writing received cells before the folder moved", e);
                    }
                    worldDir = dir;
                    if (phase == Phase.CATCHING_UP || phase == Phase.SYNCED) CellCapture.enable(dir);
                });
            }
        }
    }

    // ------------------------------------------------------------------ commands (main thread)

    public void setPaused(boolean value) {
        paused = value;
        chat.add(new ChatComponentTranslation(value ? "journeysync.chat.paused" : "journeysync.chat.resumed"));
    }

    /** Asks the server for the whole shared map again, for when the local map files were deleted. */
    public boolean resync() {
        if (phase != Phase.CATCHING_UP && phase != Phase.SYNCED) return false;
        events.add(() -> {
            state.lastRev = 0;
            saveState();
            phase = Phase.HELLO_SENT;
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
                if (phase == Phase.CATCHING_UP || phase == Phase.SYNCED) {
                    periodicWork();
                } else
                    if (phase == Phase.HELLO_SENT && System.currentTimeMillis() - helloSentAt > HELLO_TIMEOUT_MILLIS) {
                        phase = Phase.NO_SERVER_MOD;
                        JourneySync.LOG
                            .info("The server didn't answer; it doesn't have JourneySync. Not syncing maps.");
                    }
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
        waypoints = new WaypointSync(state, record -> send(new WaypointChangePacket(record)));
        limiter = new RateLimiter(Config.uploadKBps * 1024L);
        blobs.clear();
        pendingApply.clear();
        pendingWaypointBlob = null;
        seedFiles = null;
        seedSnapshot = null;
        cellsSent = cellsReceived = 0;
        seedDone = seedTotal = 0;
        lastRevShown = state.lastRev;
        CellCapture.reset();
        JourneySync.LOG.info("Connected to {}; will sync once JourneyMap is mapping", serverId);
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

    private void sendHello() {
        long since = state.lastRev;
        if (since > 0 && !hasLocalMap(worldDir)) {
            JourneySync.LOG.info("No local map files for this server; asking for the whole shared map");
            since = 0;
        }
        // Snapshot the files the player drew before syncing; received cells will be merged into them.
        seedSnapshot = Config.seedExistingMap && !state.seeded ? listTileFiles(worldDir) : null;
        helloSentAt = System.currentTimeMillis();
        send(new HelloPacket(true, Tags.VERSION, state.worldId, since));
        JourneySync.LOG
            .info("Sent hello: map {} up to revision {}", state.worldId.isEmpty() ? "(none)" : state.worldId, since);
    }

    private void handleHelloAck(HelloAckPacket ack) {
        if (ack.protocolVersion != PacketHandler.PROTOCOL_VERSION) {
            phase = Phase.UNSUPPORTED;
            chat.add(new ChatComponentTranslation("journeysync.chat.version_mismatch"));
            return;
        }
        if (!ack.worldId.equals(state.worldId)) {
            JourneySync.LOG.info("Server's shared map is {}; starting fresh for it", ack.worldId);
            state.resetFor(ack.worldId);
            // Nothing from this map has been applied yet (patches follow the ack), so the folder still holds only
            // what the player drew themselves.
            seedSnapshot = Config.seedExistingMap ? listTileFiles(worldDir) : null;
            saveState();
        }
        phase = Phase.CATCHING_UP;
        if (Config.uploadEnabled) CellCapture.enable(worldDir);
        chat.add(new ChatComponentTranslation("journeysync.chat.sync_start"));
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
            chat.add(new ChatComponentTranslation("journeysync.chat.sync_complete", cellsReceived));
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
            int[] indices = new int[e.getValue()
                .size()];
            int[][] cells = new int[indices.length][];
            int k = 0;
            for (Map.Entry<Integer, int[]> c : e.getValue()
                .entrySet()) {
                indices[k] = c.getKey();
                cells[k++] = c.getValue();
            }
            File file = JmBridge.regionFile(worldDir, e.getKey());
            synchronized (JmFiles.lock(file)) {
                HolderAccess loaded = JmFiles.loaded(file);
                if (loaded != null) {
                    loaded.journeysync$drawCells(indices, cells);
                } else {
                    int[] pixels = TileFiles.readPixels(file);
                    TileImage tile = new TileImage(pixels, new long[TileImage.CELLS]);
                    boolean changed = false;
                    for (int i = 0; i < indices.length; i++) changed |= tile.merge(indices[i], cells[i], false);
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
            int[] indices = new int[e.getValue()
                .size()];
            int[][] cells = new int[indices.length][];
            int k = 0;
            for (Map.Entry<Integer, int[]> c : e.getValue()
                .entrySet()) {
                indices[k] = c.getKey();
                cells[k++] = c.getValue();
            }
            sendCells(e.getKey(), false, indices, cells);
        }
    }

    /** Uploads one tile file from before syncing, as fill-only, skipping cells the server already sent us. */
    private void seedNext() throws IOException {
        File file = seedFiles.poll();
        if (file == null) {
            seedFiles = null;
            state.seeded = true;
            saveState();
            chat.add(new ChatComponentTranslation("journeysync.chat.seed_complete", seedTotal));
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
        List<Integer> indices = new ArrayList<>();
        List<int[]> cells = new ArrayList<>();
        for (int i = 0; i < TileImage.CELLS; i++) {
            int[] cell = image.cell(i);
            if (TileImage.isEmpty(cell) || CellCapture.isKnown(tile, i, cell)) continue;
            indices.add(i);
            cells.add(cell);
        }
        if (indices.isEmpty()) return;
        sendCells(
            tile,
            true,
            indices.stream()
                .mapToInt(Integer::intValue)
                .toArray(),
            cells.toArray(new int[0][]));
    }

    private void sendCells(TileId tile, boolean fillOnly, int[] indices, int[][] cells) throws IOException {
        for (CellPatch patch : CellPatch.encode(tile, fillOnly, indices, cells)) {
            CellPatchPacket packet = new CellPatchPacket(patch);
            limiter.acquire(packet.size());
            send(packet);
            cellsSent += patch.indices.length;
        }
    }

    private void send(IMessage message) {
        PacketHandler.wrapper.sendToServer(message);
    }

    // ------------------------------------------------------------------ waypoints

    private void handleBlobFragment(BlobPacket packet) {
        BlobAssembly blob = blobs.computeIfAbsent(packet.blobType, k -> new BlobAssembly());
        if (blob.parts.put(packet.seq, packet.data) == null) blob.bytes += packet.data.length;
        if (blob.parts.size() < packet.total) return;
        blobs.remove(packet.blobType);
        byte[] joined = new byte[blob.bytes];
        int off = 0;
        for (int i = 0; i < packet.total; i++) {
            byte[] part = blob.parts.get(i);
            System.arraycopy(part, 0, joined, off, part.length);
            off += part.length;
        }
        if (packet.blobType != PacketHandler.BLOB_WAYPOINTS_FULL || !Config.downloadEnabled) return;
        String json = BlobPacket.gunzip(joined);
        if (worldDir == null) {
            pendingWaypointBlob = json;
        } else {
            applyWaypointBlob(json);
        }
    }

    private void applyWaypointBlob(String json) {
        if (waypoints.applyServerBlob(json, worldDir)) {
            needsWaypointReset = true;
            stateDirty = true;
        }
    }

    // ------------------------------------------------------------------ helpers

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
