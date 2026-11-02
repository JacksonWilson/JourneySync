package com.donkeytt.journeysync.client;

import com.donkeytt.journeysync.network.PacketHandler;

/**
 * What the server said in its login greeting ({@code ServerHelloPacket}): whether it has JourneySync, which protocol
 * it speaks, and the id of its shared map. Map sync and the chunk layer both wait for this before sending anything.
 */
public final class ServerInfo {

    private static volatile int protocol = -1;
    private static volatile String worldId = "";

    private ServerInfo() {}

    static void set(int protocolVersion, String id) {
        worldId = id == null ? "" : id;
        protocol = protocolVersion;
    }

    static void clear() {
        protocol = -1;
        worldId = "";
    }

    /** The server greeted us, so it has JourneySync. */
    public static boolean received() {
        return protocol >= 0;
    }

    /** The server has JourneySync and speaks our protocol. */
    public static boolean compatible() {
        return protocol == PacketHandler.PROTOCOL_VERSION;
    }

    public static String worldId() {
        return worldId;
    }
}
