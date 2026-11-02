package com.donkeytt.journeysync.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.donkeytt.journeysync.network.Blobs;

/** The chunk layer's snapshot format, and sending it as a blob split over several packets. */
class ChunkSnapshotTest {

    private static ChunkSnapshot big(int chunks) {
        ChunkStateBuilder b = new ChunkStateBuilder(7);
        b.addTeam("t", "Team é", 0x123456);
        int ticket = b.addTicket("GregTech", "Alice", "Chunk Loader at -1, 2, 3", "Alice", null);
        Random r = new Random(42);
        for (int i = 0; i < chunks; i++) {
            int x = r.nextInt(200_000) - 100_000;
            int z = r.nextInt(200_000) - 100_000;
            b.loaded(x, z);
            if (i % 3 == 0) b.claimed(x, z, "t");
            if (i % 7 == 0) b.forced(x, z, ticket);
        }
        return b.build(new ChunkStateBuilder.Viewer(true, "Op", null));
    }

    private static void assertSame(ChunkSnapshot a, ChunkSnapshot b) {
        assertEquals(a.dimension, b.dimension);
        assertEquals(a.seesAll, b.seesAll);
        assertEquals(a.teams.size(), b.teams.size());
        for (int i = 0; i < a.teams.size(); i++) {
            assertEquals(a.teams.get(i).name, b.teams.get(i).name);
            assertEquals(a.teams.get(i).rgb, b.teams.get(i).rgb);
        }
        assertEquals(a.tickets.size(), b.tickets.size());
        for (int i = 0; i < a.tickets.size(); i++) {
            assertEquals(a.tickets.get(i).mod, b.tickets.get(i).mod);
            assertEquals(a.tickets.get(i).owner, b.tickets.get(i).owner);
            assertEquals(a.tickets.get(i).detail, b.tickets.get(i).detail);
        }
        assertEquals(a.chunks.size(), b.chunks.size());
        for (int i = 0; i < a.chunks.size(); i++) {
            ChunkSnapshot.Chunk x = a.chunks.get(i), y = b.chunks.get(i);
            assertEquals(x.x, y.x);
            assertEquals(x.z, y.z);
            assertEquals(x.flags, y.flags);
            assertEquals(x.team, y.team);
            assertArrayEquals(x.tickets, y.tickets);
        }
    }

    private static ChunkSnapshot unzip(byte[] gzipped) throws IOException {
        try (DataInputStream in = new DataInputStream(Blobs.gunzip(gzipped))) {
            return ChunkSnapshot.read(in);
        }
    }

    @Test
    void roundTripsThroughDataStreams() throws IOException {
        ChunkSnapshot s = big(500);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        s.write(new DataOutputStream(bytes));
        ChunkSnapshot back = ChunkSnapshot.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertSame(s, back);
    }

    @Test
    void varIntsAndZigzagCoverTheIntRange() throws IOException {
        int[] values = { 0, 1, -1, 63, -64, 127, 128, 30_000_000, -30_000_000, Integer.MAX_VALUE, Integer.MIN_VALUE };
        for (int v : values) {
            assertEquals(v, ChunkSnapshot.unzigzag(ChunkSnapshot.zigzag(v)));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ChunkSnapshot.writeVarInt(new DataOutputStream(bytes), v);
            assertEquals(
                v,
                ChunkSnapshot.readVarInt(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        }
    }

    @Test
    void largeSnapshotSplitsIntoPartsAndReassembles() throws IOException {
        ChunkSnapshot s = big(60_000);
        List<byte[]> parts = Blobs.split(Blobs.gzip(s::write), Blobs.PART_SIZE);
        assertTrue(parts.size() > 1, "expected several parts, got " + parts.size());
        for (byte[] p : parts) assertTrue(p.length <= Blobs.PART_SIZE);

        Blobs.Assembler a = new Blobs.Assembler();
        byte[] whole = null;
        // Out of order on purpose.
        for (int i = parts.size() - 1; i >= 0; i--) {
            byte[] r = a.add(5, i, parts.size(), parts.get(i));
            if (i > 0) assertNull(r);
            else whole = r;
        }
        assertNotNull(whole);
        assertSame(s, unzip(whole));
    }

    @Test
    void newerBlobDropsAnUnfinishedOlderOne() throws IOException {
        List<byte[]> old = Blobs.split(Blobs.gzip(big(60_000)::write), Blobs.PART_SIZE);
        ChunkSnapshot fresh = big(10);
        List<byte[]> now = Blobs.split(Blobs.gzip(fresh::write), 50);
        Blobs.Assembler a = new Blobs.Assembler();
        assertNull(a.add(1, 0, old.size(), old.get(0)));
        byte[] whole = null;
        for (int i = 0; i < now.size(); i++) whole = a.add(2, i, now.size(), now.get(i));
        assertNotNull(whole);
        assertSame(fresh, unzip(whole));
    }

    @Test
    void rejectsGarbage() {
        Blobs.Assembler a = new Blobs.Assembler();
        assertThrows(IOException.class, () -> a.add(1, 3, 2, new byte[0]));
        assertThrows(IOException.class, () -> unzip(new byte[] { 1, 2, 3 }));
    }

    @Test
    void textBlobsRoundTrip() throws IOException {
        String json = "{\"records\":[{\"name\":\"Base é\"}]}";
        assertEquals(json, Blobs.gunzipString(Blobs.gzip(json)));
    }
}
