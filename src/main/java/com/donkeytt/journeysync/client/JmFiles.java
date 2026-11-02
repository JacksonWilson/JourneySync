package com.donkeytt.journeysync.client;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which JourneyMap region images are loaded in memory, and a lock per image file.
 *
 * <p>
 * JourneyMap reads a region file once, keeps the image in memory for hours and writes it back whole, so a file changed
 * on disk while the image is loaded is ignored and then overwritten. {@code MixinImageHolder} loads each file while
 * holding this file's lock and registers the holder; it unregisters when JourneyMap drops the image. JourneySync takes
 * the same lock to apply cells: into the registered holder if there is one, otherwise into the file on disk.
 * </p>
 */
public final class JmFiles {

    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, HolderAccess> LOADED = new ConcurrentHashMap<>();

    private JmFiles() {}

    public static Object lock(File file) {
        return LOCKS.computeIfAbsent(key(file), k -> new Object());
    }

    /** The loaded holder for this file, or null. Call while holding {@link #lock(File)}. */
    public static HolderAccess loaded(File file) {
        return LOADED.get(key(file));
    }

    public static void register(File file, HolderAccess holder) {
        LOADED.put(key(file), holder);
    }

    public static void unregister(File file, HolderAccess holder) {
        LOADED.remove(key(file), holder);
    }

    private static String key(File file) {
        return file.getAbsoluteFile()
            .toPath()
            .normalize()
            .toString();
    }
}
