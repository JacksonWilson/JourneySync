package com.donkeytt.journeysync;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * JourneySync configuration.
 */
public class Config {

    public static final String CATEGORY_GENERAL = "general";
    public static final String CATEGORY_TRANSFER = "transfer";
    public static final String CATEGORY_SERVER = "server";

    // general (client)
    public static boolean uploadEnabled = true;
    public static boolean downloadEnabled = true;
    public static boolean seedExistingMap = true;
    public static boolean logDebug = false;

    // transfer (client)
    public static int uploadIntervalMillis = 2000;
    public static int uploadKBps = 256;

    // server
    public static boolean serverAcceptUploads = true;
    public static int serverSendKBps = 1024;
    public static int serverTileCache = 32;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);
        uploadEnabled = configuration.getBoolean(
            "uploadEnabled",
            CATEGORY_GENERAL,
            uploadEnabled,
            "Send the chunks your JourneyMap draws to the server");
        downloadEnabled = configuration.getBoolean(
            "downloadEnabled",
            CATEGORY_GENERAL,
            downloadEnabled,
            "Draw the chunks other players have explored, and their shared waypoints");
        seedExistingMap = configuration.getBoolean(
            "seedExistingMap",
            CATEGORY_GENERAL,
            seedExistingMap,
            "The first time you join a server, upload the map you already had for it. It only fills places the "
                + "shared map has never seen, so it never overwrites newer data.");
        logDebug = configuration.getBoolean("logDebug", CATEGORY_GENERAL, logDebug, "Verbose debug logging");
        uploadIntervalMillis = configuration.getInt(
            "uploadIntervalMillis",
            CATEGORY_TRANSFER,
            uploadIntervalMillis,
            250,
            60_000,
            "How often freshly drawn chunks are sent to the server");
        uploadKBps = configuration.getInt(
            "uploadKBps",
            CATEGORY_TRANSFER,
            uploadKBps,
            0,
            1024 * 1024,
            "Upload limit in KB/s (0 = unlimited). Mostly matters for the one-time upload of an existing map.");
        serverAcceptUploads = configuration.getBoolean(
            "acceptUploads",
            CATEGORY_SERVER,
            serverAcceptUploads,
            "Server only: merge chunks players send into the shared map");
        serverSendKBps = configuration.getInt(
            "sendKBpsPerPlayer",
            CATEGORY_SERVER,
            serverSendKBps,
            0,
            1024 * 1024,
            "Server only: how fast the shared map is sent to each player, in KB/s (0 = unlimited). A new player "
                + "receives the whole map once.");
        serverTileCache = configuration.getInt(
            "tileCache",
            CATEGORY_SERVER,
            serverTileCache,
            4,
            4096,
            "Server only: region tiles kept in memory, about 1 MB each");
        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
