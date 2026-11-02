package com.donkeytt.journeysync.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ChunkStateBuilderTest {

    /**
     * Two teams. Red claims (0,0) loaded and (1,0) unloaded; blue claims (5,5) loaded and force-loads it through its
     * team ticket. Alice (red) owns a GregTech player ticket on (10,10). Bob owns one on (20,20). (30,30) is loaded
     * with nothing else.
     */
    private static ChunkStateBuilder world() {
        ChunkStateBuilder b = new ChunkStateBuilder(0);
        b.addTeam("red", "Red Team", 0xFF0000);
        b.addTeam("blue", "Blue Team", 0x0000FF);
        b.claimed(0, 0, "red");
        b.loaded(0, 0);
        b.claimed(1, 0, "red");
        b.claimed(5, 5, "blue");
        b.loaded(5, 5);
        int blueTicket = b.addTicket("ServerUtilities", "Blue Team", "claimed chunk loading", null, "blue");
        b.forced(5, 5, blueTicket);
        int alice = b.addTicket("GregTech", "Alice", "Chunk Loader at 160, 64, 160", "Alice", null);
        b.forced(10, 10, alice);
        b.loaded(10, 10);
        int bob = b.addTicket("GregTech", "Bob", null, "Bob", null);
        b.forced(20, 20, bob);
        b.loaded(20, 20);
        b.loaded(30, 30);
        return b;
    }

    private static Map<String, ChunkSnapshot.Chunk> byPos(ChunkSnapshot s) {
        Map<String, ChunkSnapshot.Chunk> m = new HashMap<>();
        for (ChunkSnapshot.Chunk c : s.chunks) m.put(c.x + "," + c.z, c);
        return m;
    }

    @Test
    void opSeesEverything() {
        ChunkSnapshot s = world().build(new ChunkStateBuilder.Viewer(true, "Op", null));
        assertTrue(s.seesAll);
        Map<String, ChunkSnapshot.Chunk> m = byPos(s);
        assertEquals(6, m.size());
        assertEquals(2, s.teams.size());
        assertEquals(3, s.tickets.size());

        ChunkSnapshot.Chunk unloadedClaim = m.get("1,0");
        assertTrue(unloadedClaim.has(ChunkSnapshot.CLAIMED));
        assertTrue(unloadedClaim.has(ChunkSnapshot.LOAD_KNOWN));
        assertFalse(unloadedClaim.has(ChunkSnapshot.LOADED));

        ChunkSnapshot.Chunk blue = m.get("5,5");
        assertEquals("Blue Team", s.teams.get(blue.team).name);
        assertEquals(1, blue.tickets.length);
        assertEquals("ServerUtilities", s.tickets.get(blue.tickets[0]).mod);
        assertTrue(blue.has(ChunkSnapshot.FORCED | ChunkSnapshot.LOADED));

        ChunkSnapshot.Chunk plain = m.get("30,30");
        assertEquals(-1, plain.team);
        assertEquals(ChunkSnapshot.LOADED | ChunkSnapshot.LOAD_KNOWN, plain.flags);
    }

    @Test
    void regularPlayerSeesOwnTeamAndOwnTickets() {
        ChunkSnapshot s = world().build(new ChunkStateBuilder.Viewer(false, "alice", "red"));
        assertFalse(s.seesAll);
        Map<String, ChunkSnapshot.Chunk> m = byPos(s);
        // Own claims, and her own ticket matched by name regardless of case. Nothing of blue's or Bob's.
        assertEquals(3, m.size(), m.keySet()::toString);
        assertTrue(m.containsKey("0,0"));
        assertTrue(m.containsKey("1,0"));
        assertTrue(m.containsKey("10,10"));
        assertEquals(1, s.teams.size());
        assertEquals("Red Team", s.teams.get(0).name);
        assertEquals(1, s.tickets.size());
        assertEquals("Alice", s.tickets.get(0).owner);
        assertTrue(
            m.get("10,10")
                .has(ChunkSnapshot.LOADED));
    }

    @Test
    void teamTicketVisibleToTeamMembersOnly() {
        ChunkSnapshot blue = world().build(new ChunkStateBuilder.Viewer(false, "Carol", "blue"));
        Map<String, ChunkSnapshot.Chunk> m = byPos(blue);
        assertEquals(1, m.size());
        assertEquals(1, m.get("5,5").tickets.length);

        ChunkSnapshot loner = world().build(new ChunkStateBuilder.Viewer(false, "Dave", null));
        assertTrue(loner.chunks.isEmpty());
        assertTrue(loner.teams.isEmpty());
        assertTrue(loner.tickets.isEmpty());
    }

    @Test
    void ticketOnSomeoneElsesClaimShowsTicketButNotTheClaim() {
        ChunkStateBuilder b = world();
        int alice = b.addTicket("GregTech", "Alice", null, "Alice", null);
        b.forced(5, 5, alice);
        ChunkSnapshot s = b.build(new ChunkStateBuilder.Viewer(false, "Alice", "red"));
        ChunkSnapshot.Chunk c = byPos(s).get("5,5");
        assertEquals(-1, c.team);
        assertFalse(c.has(ChunkSnapshot.CLAIMED));
        assertEquals(1, c.tickets.length);
        assertEquals("Alice", s.tickets.get(c.tickets[0]).owner);
    }

    @Test
    void sameTicketTwiceOnAChunkIsListedOnce() {
        ChunkStateBuilder b = new ChunkStateBuilder(-1);
        int t = b.addTicket("GregTech", null, null, null, null);
        b.forced(3, -4, t);
        b.forced(3, -4, t);
        ChunkSnapshot s = b.build(new ChunkStateBuilder.Viewer(true, "Op", null));
        assertEquals(-1, s.dimension);
        assertArrayEquals(new int[] { 0 }, s.chunks.get(0).tickets);
        assertEquals("", s.tickets.get(0).owner);
    }

    @Test
    void unknownTeamIsIgnored() {
        ChunkStateBuilder b = new ChunkStateBuilder(0);
        b.claimed(1, 1, "ghost");
        ChunkSnapshot s = b.build(new ChunkStateBuilder.Viewer(true, "Op", null));
        assertTrue(s.chunks.isEmpty());
    }
}
