package com.donkeytt.journeysync.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import com.donkeytt.journeysync.JourneySync;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Per-server client state under {@code config/journeysync/}: which shared map this server has, how much of it this
 * client already has, whether the local map was uploaded once, and the last applied revision of each waypoint.
 */
public class ClientState {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    /** The server's shared map id. A different id means a different map: start over. */
    public String worldId = "";
    /** Every cell up to this revision has been received and applied. */
    public long lastRev;
    /** The local map drawn before syncing was uploaded (as fill-only). */
    public boolean seeded;
    public Map<String, Long> waypointRevs = new LinkedHashMap<>();

    /** Forgets everything synced with a previous map. */
    public void resetFor(String newWorldId) {
        worldId = newWorldId;
        lastRev = 0;
        seeded = false;
        waypointRevs.clear();
    }

    public static ClientState load(File file) {
        ClientState state = new ClientState();
        try {
            if (file.isFile()) {
                ClientState parsed = GSON
                    .fromJson(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), ClientState.class);
                if (parsed != null) state = parsed;
            }
        } catch (Exception e) {
            JourneySync.LOG.error("Failed loading client state {}, starting fresh", file, e);
        }
        if (state.worldId == null) state.worldId = "";
        if (state.waypointRevs == null) state.waypointRevs = new LinkedHashMap<>();
        return state;
    }

    public void save(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            Files.write(
                file.toPath(),
                GSON.toJson(this)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            JourneySync.LOG.error("Failed saving client state {}", file, e);
        }
    }
}
