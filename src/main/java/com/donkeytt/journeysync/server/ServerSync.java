package com.donkeytt.journeysync.server;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.donkeytt.journeysync.Config;
import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.data.CellPatch;
import com.donkeytt.journeysync.data.TileId;
import com.donkeytt.journeysync.data.TileImage;
import com.donkeytt.journeysync.data.WaypointRecord;
import com.donkeytt.journeysync.network.Blobs;
import com.donkeytt.journeysync.network.PacketHandler;
import com.donkeytt.journeysync.network.packets.BlobPacket;
import com.donkeytt.journeysync.network.packets.CellPatchPacket;
import com.donkeytt.journeysync.network.packets.HelloPacket;
import com.donkeytt.journeysync.network.packets.SyncMarkPacket;

import cpw.mods.fml.common.network.simpleimpl.IMessage;

/**
 * The server side of map sharing. Network handlers and game events only queue work; one worker thread merges uploads
 * into the {@link CellStore} and sends each player what they're missing, through a per-player queue so it can be
 * rate-limited.
 */
public final class ServerSync {

    private static final ServerSync INSTANCE = new ServerSync();
    /** How many packets a catch-up may have waiting before it reads the next tile. */
    private static final int CATCH_UP_QUEUE_LIMIT = 32;
    private static final long FLUSH_MILLIS = 30_000;
    private static final long MARK_MILLIS = 15_000;

    public static ServerSync instance() {
        return INSTANCE;
    }

    private static final class Outgoing {

        final IMessage message;
        final int size;

        Outgoing(IMessage message, int size) {
            this.message = message;
            this.size = size;
        }
    }

    private static final class CatchUp {

        final List<TileId> tiles;
        final long since;
        final long markRev;
        int next;

        CatchUp(List<TileId> tiles, long since, long markRev) {
            this.tiles = tiles;
            this.since = since;
            this.markRev = markRev;
        }
    }

    private static final class Session {

        final EntityPlayerMP player;
        final Deque<Outgoing> queue = new ArrayDeque<>();
        CatchUp catchUp;
        double budget;
        long lastMarkRev = -1;

        Session(EntityPlayerMP player) {
            this.player = player;
        }
    }

    /** Work for the worker thread. May throw; a failure is logged and doesn't stop the next task. */
    private interface Task {

        void run() throws Exception;
    }

    private final LinkedBlockingQueue<Task> events = new LinkedBlockingQueue<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private CellStore store;
    private ServerWaypointStore waypoints;
    private boolean waypointsDirty;
    private Thread worker;
    private volatile boolean running;
    private long lastFlush;
    private long lastMark;
    private long lastBudgetRefill;

    private ServerSync() {}

    // ------------------------------------------------------------------ lifecycle (server thread)

    public void start(File worldDir) {
        File dataDir = new File(worldDir, "journeysync");
        store = new CellStore(dataDir, Config.serverTileCache);
        try {
            store.load();
        } catch (IOException e) {
            JourneySync.LOG.error("Failed loading the shared map; starting with what could be read", e);
        }
        waypoints = new ServerWaypointStore(new File(dataDir, "waypoints.json"));
        events.clear();
        sessions.clear();
        running = true;
        lastFlush = lastMark = lastBudgetRefill = System.currentTimeMillis();
        worker = new Thread(this::workerLoop, "JourneySync-Server");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
            worker = null;
        }
        if (store != null) store.flush();
        if (waypoints != null) waypoints.save();
    }

    // ------------------------------------------------------------------ inbound (any thread)

    public void onHello(EntityPlayerMP player, HelloPacket hello) {
        if (player != null) events.add(() -> handleHello(player, hello));
    }

    public void onUpload(EntityPlayerMP player, CellPatch patch) {
        if (player != null) events.add(() -> handleUpload(player, patch));
    }

    public void onWaypointChange(EntityPlayerMP player, WaypointRecord record) {
        if (player != null) events.add(() -> handleWaypoint(player, record));
    }

    public void onLogout(EntityPlayerMP player) {
        events.add(() -> sessions.remove(player.getUniqueID()));
    }

    // ------------------------------------------------------------------ worker

    private void workerLoop() {
        while (running) {
            try {
                Task event = events.poll(sessions.isEmpty() ? 500 : 20, TimeUnit.MILLISECONDS);
                int handled = 0;
                while (event != null) {
                    try {
                        event.run();
                    } catch (Exception e) {
                        JourneySync.LOG.warn("JourneySync server task failed", e);
                    }
                    if (++handled >= 64) break;
                    event = events.poll();
                }
                long now = System.currentTimeMillis();
                for (Session s : sessions.values()) {
                    advanceCatchUp(s);
                }
                sendQueued(now);
                if (now - lastMark > MARK_MILLIS) {
                    lastMark = now;
                    for (Session s : sessions.values()) {
                        if (s.catchUp == null && s.queue.isEmpty() && s.lastMarkRev != store.rev()) {
                            s.lastMarkRev = store.rev();
                            s.queue.add(new Outgoing(new SyncMarkPacket(store.rev(), false), 9));
                        }
                    }
                }
                if (now - lastFlush > FLUSH_MILLIS) {
                    lastFlush = now;
                    store.flush();
                    if (waypointsDirty) {
                        waypointsDirty = false;
                        waypoints.save();
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                JourneySync.LOG.error("JourneySync server worker error", t);
            }
        }
    }

    private void handleHello(EntityPlayerMP player, HelloPacket hello) {
        // The client only says hello after our login greeting told it our protocol, so a mismatch means a broken
        // client.
        if (hello.protocolVersion != PacketHandler.PROTOCOL_VERSION) {
            JourneySync.LOG.warn(
                "{} sent JourneySync protocol {} ({}); this server uses {}. Not syncing with them.",
                player.getCommandSenderName(),
                hello.protocolVersion,
                hello.modVersion,
                PacketHandler.PROTOCOL_VERSION);
            return;
        }
        Session s = new Session(player);
        sessions.put(player.getUniqueID(), s);
        long since = store.worldId()
            .equals(hello.worldId) ? Math.max(0, hello.lastRev) : 0;
        List<TileId> tiles = store.tilesNewerThan(since);
        s.catchUp = new CatchUp(tiles, since, store.rev());
        queueWaypoints(s);
        JourneySync.LOG.info(
            "{} joined with JourneySync {}: sending {} tiles changed since revision {}",
            player.getCommandSenderName(),
            hello.modVersion,
            tiles.size(),
            since);
    }

    private void advanceCatchUp(Session s) throws IOException {
        CatchUp job = s.catchUp;
        if (job == null || s.queue.size() >= CATCH_UP_QUEUE_LIMIT) return;
        if (job.next < job.tiles.size()) {
            TileId id = job.tiles.get(job.next++);
            int[] indices = store.cellsNewerThan(id, job.since);
            enqueueCells(s, id, indices);
            return;
        }
        s.catchUp = null;
        s.lastMarkRev = job.markRev;
        s.queue.add(new Outgoing(new SyncMarkPacket(job.markRev, true), 9));
    }

    private void handleUpload(EntityPlayerMP player, CellPatch patch) throws IOException {
        if (!sessions.containsKey(player.getUniqueID()) || !Config.serverAcceptUploads) return;
        int[][] cells = patch.cells();
        int[] changed = store.merge(patch.tile, patch.indices, cells, patch.fillOnly);
        if (changed.length == 0) return;
        for (Session s : sessions.values()) {
            if (s.player != player) enqueueCells(s, patch.tile, changed);
        }
    }

    private void handleWaypoint(EntityPlayerMP player, WaypointRecord record) {
        if (!sessions.containsKey(player.getUniqueID())) return;
        boolean op = MinecraftServer.getServer()
            .getConfigurationManager()
            .func_152596_g(player.getGameProfile());
        if (waypoints.apply(
            record,
            player.getUniqueID()
                .toString(),
            op) == null) return;
        waypointsDirty = true;
        for (Session s : sessions.values()) queueWaypoints(s);
    }

    /** Queues the whole waypoint list, the way every change is sent. */
    private void queueWaypoints(Session s) {
        for (BlobPacket p : PacketHandler.blobPackets(PacketHandler.BLOB_WAYPOINTS, Blobs.gzip(waypoints.toJson()))) {
            s.queue.add(new Outgoing(p, p.size()));
        }
    }

    /** Queues the store's current pixels for these cells. */
    private void enqueueCells(Session s, TileId id, int[] indices) throws IOException {
        if (indices.length == 0) return;
        TileImage tile = store.get(id);
        int[][] cells = new int[indices.length][];
        for (int i = 0; i < indices.length; i++) cells[i] = tile.cell(indices[i]);
        for (CellPatch patch : CellPatch.encode(id, false, indices, cells)) {
            CellPatchPacket packet = new CellPatchPacket(patch);
            s.queue.add(new Outgoing(packet, packet.size()));
        }
    }

    private void sendQueued(long now) {
        double refill = (now - lastBudgetRefill) / 1000.0 * Config.serverSendKBps * 1024.0;
        lastBudgetRefill = now;
        double cap = Math.max(64 * 1024, Config.serverSendKBps * 1024.0);
        for (Session s : sessions.values()) {
            s.budget = Config.serverSendKBps <= 0 ? Double.MAX_VALUE : Math.min(cap, s.budget + refill);
            while (!s.queue.isEmpty() && s.budget > 0) {
                Outgoing out = s.queue.poll();
                send(s.player, out.message);
                if (Config.serverSendKBps > 0) s.budget -= out.size;
            }
        }
    }

    private void send(EntityPlayerMP player, IMessage message) {
        try {
            PacketHandler.sendTo(message, player);
        } catch (Exception e) {
            JourneySync.LOG.debug("Couldn't send to {}: {}", player.getCommandSenderName(), e.toString());
        }
    }

    /** The shared map's id, for the login greeting. Empty before the server has started. */
    public String worldId() {
        return store == null ? "" : store.worldId();
    }

    /** One line for the server log or a command. */
    public String status() {
        return store == null ? "not running"
            : String.format(
                "shared map %s: %d tiles, revision %d, %d players syncing",
                store.worldId(),
                store.tileCount(),
                store.rev(),
                sessions.size());
    }
}
