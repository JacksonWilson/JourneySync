package com.donkeytt.journeysync.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import com.donkeytt.journeysync.Config;
import com.donkeytt.journeysync.data.ChunkSnapshot;
import com.donkeytt.journeysync.data.ChunkStateBuilder;
import com.donkeytt.journeysync.network.Blobs;
import com.donkeytt.journeysync.network.PacketHandler;

/**
 * Answers chunk layer requests on the server thread, at most once per player per configured interval: what the player
 * may see of their dimension's chunk loading and claims.
 */
public final class ChunkSnapshots {

    /** Collected state is reused for this long, so several players with maps open cost one scan. */
    private static final long CACHE_MILLIS = 1000;

    private static final Map<UUID, Long> lastRequest = new HashMap<>();
    private static final Map<Integer, ChunkStateBuilder> cache = new HashMap<>();
    private static final Map<Integer, Long> cacheTime = new HashMap<>();
    private static Claims claims;

    private ChunkSnapshots() {}

    public static void handle(EntityPlayerMP player) {
        if (player.playerNetServerHandler == null || player.isDead) return;
        UUID id = player.getUniqueID();
        long now = System.currentTimeMillis();
        Long previous = lastRequest.get(id);
        if (previous != null && now - previous < Config.chunkRequestIntervalMillis) return;
        lastRequest.put(id, now);
        ChunkSnapshot snapshot = snapshotFor(player);
        PacketHandler.sendBlob(player, PacketHandler.BLOB_CHUNKS, Blobs.gzip(snapshot::write));
    }

    public static void forget(EntityPlayer player) {
        lastRequest.remove(player.getUniqueID());
    }

    /** Collects the player's dimension and filters it to what they may see. Server thread only. */
    public static ChunkSnapshot snapshotFor(EntityPlayerMP player) {
        WorldServer world = player.getServerForPlayer();
        int dim = world.provider.dimensionId;
        long now = System.currentTimeMillis();
        ChunkStateBuilder state = cache.get(dim);
        Long time = cacheTime.get(dim);
        if (state == null || time == null || now - time >= CACHE_MILLIS) {
            state = ChunkStateCollector.collect(world, claims());
            cache.put(dim, state);
            cacheTime.put(dim, now);
        }
        return state.build(viewerFor(player));
    }

    public static ChunkStateBuilder.Viewer viewerFor(EntityPlayerMP player) {
        String name = player.getCommandSenderName();
        return new ChunkStateBuilder.Viewer(seesAll(player), name, claims().teamOf(player));
    }

    /** Ops (when opsSeeAll is on) and players in seeAllPlayers see every team's data. */
    public static boolean seesAll(EntityPlayerMP player) {
        String name = player.getCommandSenderName();
        String uuid = player.getUniqueID()
            .toString();
        for (String entry : Config.seeAllPlayers) {
            String e = entry.trim();
            if (e.equalsIgnoreCase(name) || e.equalsIgnoreCase(uuid)) return true;
        }
        if (!Config.opsSeeAll) return false;
        MinecraftServer server = MinecraftServer.getServer();
        // canSendCommands: an op, or the owner of a singleplayer world.
        return server != null && server.getConfigurationManager()
            .func_152596_g(player.getGameProfile());
    }

    /** Clears the per-dimension cache. For tests that change the world between requests. */
    public static void clearCache() {
        cache.clear();
        cacheTime.clear();
    }

    static Claims claims() {
        if (claims == null) claims = Claims.detect();
        return claims;
    }
}
