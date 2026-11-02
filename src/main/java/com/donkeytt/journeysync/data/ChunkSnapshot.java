package com.donkeytt.journeysync.data;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one player may see of one dimension: claimed, loaded and force-loaded chunks. Teams and tickets are stored
 * once and referenced by index from each chunk. Built on the server by {@link ChunkStateBuilder}, sent to the client
 * with {@link #write(DataOutput)}.
 */
public final class ChunkSnapshot {

    public static final int LOADED = 1;
    public static final int FORCED = 2;
    public static final int CLAIMED = 4;
    /** The viewer may see the loaded state of this chunk. Without it, LOADED is unknown rather than false. */
    public static final int LOAD_KNOWN = 8;

    private static final int FORMAT = 1;

    public final int dimension;
    /** The viewer is an op or whitelisted, so the snapshot holds every team's data. */
    public final boolean seesAll;
    public final List<Team> teams;
    public final List<Ticket> tickets;
    public final List<Chunk> chunks;

    public ChunkSnapshot(int dimension, boolean seesAll, List<Team> teams, List<Ticket> tickets, List<Chunk> chunks) {
        this.dimension = dimension;
        this.seesAll = seesAll;
        this.teams = Collections.unmodifiableList(teams);
        this.tickets = Collections.unmodifiableList(tickets);
        this.chunks = Collections.unmodifiableList(chunks);
    }

    public static ChunkSnapshot empty(int dimension) {
        return new ChunkSnapshot(dimension, false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public static final class Team {

        public final String name;
        public final int rgb;

        public Team(String name, int rgb) {
            this.name = name;
            this.rgb = rgb & 0xFFFFFF;
        }
    }

    /** A ForgeChunkManager ticket, described for display. Empty strings mean unknown. */
    public static final class Ticket {

        public final String mod;
        public final String owner;
        public final String detail;

        public Ticket(String mod, String owner, String detail) {
            this.mod = mod;
            this.owner = owner;
            this.detail = detail;
        }
    }

    public static final class Chunk {

        public final int x;
        public final int z;
        public final int flags;
        /** Index into {@link #teams}, or -1 when unclaimed. */
        public final int team;
        /** Indexes into {@link #tickets}. Empty unless FORCED. */
        public final int[] tickets;

        public Chunk(int x, int z, int flags, int team, int[] tickets) {
            this.x = x;
            this.z = z;
            this.flags = flags;
            this.team = team;
            this.tickets = tickets;
        }

        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    public void write(DataOutput out) throws IOException {
        writeVarInt(out, FORMAT);
        out.writeInt(dimension);
        out.writeBoolean(seesAll);
        writeVarInt(out, teams.size());
        for (Team t : teams) {
            out.writeUTF(t.name);
            out.writeInt(t.rgb);
        }
        writeVarInt(out, tickets.size());
        for (Ticket t : tickets) {
            out.writeUTF(t.mod);
            out.writeUTF(t.owner);
            out.writeUTF(t.detail);
        }
        writeVarInt(out, chunks.size());
        for (Chunk c : chunks) {
            writeVarInt(out, zigzag(c.x));
            writeVarInt(out, zigzag(c.z));
            writeVarInt(out, c.flags);
            writeVarInt(out, c.team + 1);
            writeVarInt(out, c.tickets.length);
            for (int t : c.tickets) writeVarInt(out, t);
        }
    }

    public static ChunkSnapshot read(DataInput in) throws IOException {
        int format = readVarInt(in);
        if (format != FORMAT) throw new IOException("unknown snapshot format " + format);
        int dimension = in.readInt();
        boolean seesAll = in.readBoolean();
        int teamCount = readCount(in);
        List<Team> teams = new ArrayList<>(teamCount);
        for (int i = 0; i < teamCount; i++) teams.add(new Team(in.readUTF(), in.readInt()));
        int ticketCount = readCount(in);
        List<Ticket> tickets = new ArrayList<>(ticketCount);
        for (int i = 0; i < ticketCount; i++) tickets.add(new Ticket(in.readUTF(), in.readUTF(), in.readUTF()));
        int chunkCount = readCount(in);
        List<Chunk> chunks = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            int x = unzigzag(readVarInt(in));
            int z = unzigzag(readVarInt(in));
            int flags = readVarInt(in);
            int team = readVarInt(in) - 1;
            if (team >= teamCount) throw new IOException("team index out of range");
            int[] ids = new int[readCount(in)];
            for (int j = 0; j < ids.length; j++) {
                ids[j] = readVarInt(in);
                if (ids[j] >= ticketCount) throw new IOException("ticket index out of range");
            }
            chunks.add(new Chunk(x, z, flags, team, ids));
        }
        return new ChunkSnapshot(dimension, seesAll, teams, tickets, chunks);
    }

    private static int readCount(DataInput in) throws IOException {
        int n = readVarInt(in);
        if (n < 0 || n > 4_000_000) throw new IOException("bad count " + n);
        return n;
    }

    static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    static int unzigzag(int v) {
        return (v >>> 1) ^ -(v & 1);
    }

    static void writeVarInt(DataOutput out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    static int readVarInt(DataInput in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IOException("varint too long");
    }
}
