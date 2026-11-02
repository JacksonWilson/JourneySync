package com.donkeytt.journeysync.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the chunk state of one dimension with the ownership needed to decide who may see what, then builds a
 * {@link ChunkSnapshot} for one viewer. Has no Minecraft dependencies so the visibility rules can be unit tested.
 */
public final class ChunkStateBuilder {

    /** Who is asking. A null team id means the viewer has no team (or ServerUtilities isn't installed). */
    public static final class Viewer {

        public final boolean seesAll;
        public final String name;
        public final String teamId;

        public Viewer(boolean seesAll, String name, String teamId) {
            this.seesAll = seesAll;
            this.name = name;
            this.teamId = teamId;
        }
    }

    private static final class TeamEntry {

        final String id;
        final ChunkSnapshot.Team display;

        TeamEntry(String id, ChunkSnapshot.Team display) {
            this.id = id;
            this.display = display;
        }
    }

    private static final class TicketEntry {

        final ChunkSnapshot.Ticket display;
        final String ownerPlayer;
        final String ownerTeamId;

        TicketEntry(ChunkSnapshot.Ticket display, String ownerPlayer, String ownerTeamId) {
            this.display = display;
            this.ownerPlayer = ownerPlayer;
            this.ownerTeamId = ownerTeamId;
        }
    }

    private static final class ChunkEntry {

        final int x, z;
        boolean loaded;
        String teamId;
        final List<Integer> tickets = new ArrayList<>(1);

        ChunkEntry(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    private final int dimension;
    private final Map<String, TeamEntry> teams = new HashMap<>();
    private final List<TicketEntry> tickets = new ArrayList<>();
    private final Map<Long, ChunkEntry> chunks = new LinkedHashMap<>();

    public ChunkStateBuilder(int dimension) {
        this.dimension = dimension;
    }

    public void addTeam(String id, String name, int rgb) {
        teams.put(id, new TeamEntry(id, new ChunkSnapshot.Team(name, rgb)));
    }

    /**
     * Adds a ticket and returns its id for {@link #forced}. {@code ownerPlayer} and {@code ownerTeamId} decide which
     * regular players may see it and may be null.
     */
    public int addTicket(String mod, String owner, String detail, String ownerPlayer, String ownerTeamId) {
        tickets
            .add(new TicketEntry(new ChunkSnapshot.Ticket(nn(mod), nn(owner), nn(detail)), ownerPlayer, ownerTeamId));
        return tickets.size() - 1;
    }

    public void loaded(int x, int z) {
        chunk(x, z).loaded = true;
    }

    public void forced(int x, int z, int ticket) {
        ChunkEntry c = chunk(x, z);
        if (!c.tickets.contains(ticket)) c.tickets.add(ticket);
    }

    /** Marks a chunk claimed by a team added with {@link #addTeam}. */
    public void claimed(int x, int z, String teamId) {
        chunk(x, z).teamId = teamId;
    }

    public int chunkCount() {
        return chunks.size();
    }

    /**
     * Builds what {@code viewer} may see. A regular player sees their own team's claims, tickets they or their team
     * own, and the loaded state of those chunks. A viewer with {@code seesAll} sees everything.
     */
    public ChunkSnapshot build(Viewer viewer) {
        Map<String, Integer> teamIndex = new HashMap<>();
        List<ChunkSnapshot.Team> outTeams = new ArrayList<>();
        Map<Integer, Integer> ticketIndex = new HashMap<>();
        List<ChunkSnapshot.Ticket> outTickets = new ArrayList<>();
        List<ChunkSnapshot.Chunk> outChunks = new ArrayList<>();

        for (ChunkEntry c : chunks.values()) {
            int team = -1;
            boolean ownClaim = false;
            if (c.teamId != null && teams.containsKey(c.teamId)) {
                ownClaim = c.teamId.equals(viewer.teamId);
                if (viewer.seesAll || ownClaim) {
                    Integer i = teamIndex.get(c.teamId);
                    if (i == null) {
                        i = outTeams.size();
                        teamIndex.put(c.teamId, i);
                        outTeams.add(teams.get(c.teamId).display);
                    }
                    team = i;
                }
            }

            List<Integer> visibleTickets = new ArrayList<>(c.tickets.size());
            for (int t : c.tickets) {
                if (viewer.seesAll || ownClaim || ownsTicket(viewer, tickets.get(t))) {
                    Integer i = ticketIndex.get(t);
                    if (i == null) {
                        i = outTickets.size();
                        ticketIndex.put(t, i);
                        outTickets.add(tickets.get(t).display);
                    }
                    visibleTickets.add(i);
                }
            }

            boolean loadKnown = viewer.seesAll || ownClaim || !visibleTickets.isEmpty();
            int flags = 0;
            if (team >= 0) flags |= ChunkSnapshot.CLAIMED;
            if (!visibleTickets.isEmpty()) flags |= ChunkSnapshot.FORCED;
            if (loadKnown) {
                flags |= ChunkSnapshot.LOAD_KNOWN;
                if (c.loaded) flags |= ChunkSnapshot.LOADED;
            }
            if ((flags & (ChunkSnapshot.CLAIMED | ChunkSnapshot.FORCED | ChunkSnapshot.LOADED)) == 0) continue;

            int[] ids = new int[visibleTickets.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = visibleTickets.get(i);
            outChunks.add(new ChunkSnapshot.Chunk(c.x, c.z, flags, team, ids));
        }
        return new ChunkSnapshot(dimension, viewer.seesAll, outTeams, outTickets, outChunks);
    }

    private static boolean ownsTicket(Viewer viewer, TicketEntry t) {
        if (t.ownerPlayer != null && t.ownerPlayer.equalsIgnoreCase(viewer.name)) return true;
        return t.ownerTeamId != null && t.ownerTeamId.equals(viewer.teamId);
    }

    private ChunkEntry chunk(int x, int z) {
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        ChunkEntry c = chunks.get(key);
        if (c == null) {
            c = new ChunkEntry(x, z);
            chunks.put(key, c);
        }
        return c;
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }
}
