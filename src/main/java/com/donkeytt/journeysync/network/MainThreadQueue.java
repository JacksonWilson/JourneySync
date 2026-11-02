package com.donkeytt.journeysync.network;

import java.util.concurrent.ConcurrentLinkedQueue;

import com.donkeytt.journeysync.JourneySync;

/**
 * In 1.7.10, SimpleNetworkWrapper handlers run on the network thread and there is no scheduler for the game thread,
 * so handlers queue work here and a tick handler drains it.
 */
public final class MainThreadQueue {

    public static final MainThreadQueue SERVER = new MainThreadQueue();
    public static final MainThreadQueue CLIENT = new MainThreadQueue();

    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    public void execute(Runnable task) {
        tasks.add(task);
    }

    /** Runs everything queued so far. Call once per tick from the owning thread. */
    public void drain() {
        Runnable r;
        while ((r = tasks.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                JourneySync.LOG.error("Queued task failed", t);
            }
        }
    }
}
