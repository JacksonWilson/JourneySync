package com.donkeytt.journeysync.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.util.List;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.donkeytt.journeysync.Config;
import com.donkeytt.journeysync.data.ChunkSnapshot;
import com.donkeytt.journeysync.network.Blobs;
import com.donkeytt.journeysync.server.AdminCommand;
import com.donkeytt.journeysync.server.ChunkSnapshots;
import com.donkeytt.journeysync.server.Claims;
import com.donkeytt.journeysync.server.TicketDescriber;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.Loader;

/**
 * Runs on the dedicated dev server's thread. Every server-side code path runs here for real, so a client-only
 * Minecraft method anywhere in it fails these tests with NoSuchMethodError instead of crashing a live server.
 */
class ServerChunkStateTest {

    private static final GameProfile VIEWER = new GameProfile(
        UUID.fromString("0b3f6a52-1c55-4a8e-9a43-6f4c51d2f7a1"),
        "LayerTest");

    private final WorldServer world = MinecraftServer.getServer()
        .worldServerForDimension(0);
    private ForgeChunkManager.Ticket ticket;
    private int furnaceX, furnaceY, furnaceZ;

    @BeforeEach
    void setUp() {
        ChunkSnapshots.clearCache();
        Config.seeAllPlayers = new String[0];
        Config.opsSeeAll = true;
        // Far enough from spawn that nothing else holds the chunk loaded.
        ChunkCoordinates spawn = world.getSpawnPoint();
        furnaceX = spawn.posX + 40 * 16;
        furnaceY = 200;
        furnaceZ = spawn.posZ;
        world.setBlock(furnaceX, furnaceY, furnaceZ, Blocks.furnace);
        ticket = TestTickets.request(world, "Steve", furnaceX, furnaceY, furnaceZ);
        ForgeChunkManager.forceChunk(ticket, chunk());
    }

    @AfterEach
    void tearDown() {
        world.setBlockToAir(furnaceX, furnaceY, furnaceZ);
        ForgeChunkManager.releaseTicket(ticket);
        Config.seeAllPlayers = new String[0];
        ChunkSnapshots.clearCache();
    }

    private ChunkCoordIntPair chunk() {
        return new ChunkCoordIntPair(furnaceX >> 4, furnaceZ >> 4);
    }

    private FakePlayer viewer() {
        FakePlayer p = FakePlayerFactory.get(world, VIEWER);
        p.setLocationAndAngles(furnaceX, furnaceY + 1, furnaceZ, 0, 0);
        return p;
    }

    private ChunkSnapshot.Chunk find(ChunkSnapshot s, int x, int z) {
        for (ChunkSnapshot.Chunk c : s.chunks) if (c.x == x && c.z == z) return c;
        return null;
    }

    @Test
    void whitelistedViewerSeesTheForcedChunkWithTicketDetails() {
        Config.seeAllPlayers = new String[] { "layertest" };
        FakePlayer p = viewer();
        assertTrue(ChunkSnapshots.seesAll(p));

        ChunkSnapshot s = ChunkSnapshots.snapshotFor(p);
        assertEquals(0, s.dimension);
        assertTrue(s.seesAll);
        ChunkSnapshot.Chunk c = find(s, furnaceX >> 4, furnaceZ >> 4);
        assertNotNull(c, "forced chunk missing from snapshot");
        assertTrue(c.has(ChunkSnapshot.FORCED));
        assertTrue(c.has(ChunkSnapshot.LOADED), "a forced chunk is loaded");
        assertEquals(1, c.tickets.length);
        ChunkSnapshot.Ticket t = s.tickets.get(c.tickets[0]);
        assertEquals("JourneySync functional tests", t.mod);
        assertEquals("Steve", t.owner);
        assertEquals("Furnace at " + furnaceX + ", " + furnaceY + ", " + furnaceZ, t.detail);
    }

    @Test
    void spawnChunksShowAsLoaded() {
        Config.seeAllPlayers = new String[] { VIEWER.getId()
            .toString() };
        ChunkSnapshot s = ChunkSnapshots.snapshotFor(viewer());
        ChunkCoordinates spawn = world.getSpawnPoint();
        ChunkSnapshot.Chunk c = find(s, spawn.posX >> 4, spawn.posZ >> 4);
        assertNotNull(c, "spawn chunk missing");
        assertTrue(c.has(ChunkSnapshot.LOADED));
        assertTrue(c.has(ChunkSnapshot.LOAD_KNOWN));
        long loaded = s.chunks.stream()
            .filter(x -> x.has(ChunkSnapshot.LOADED))
            .count();
        assertEquals(world.theChunkProviderServer.loadedChunks.size(), loaded);
    }

    @Test
    void regularPlayerDoesNotSeeOtherPlayersTickets() {
        FakePlayer p = viewer();
        assertFalse(ChunkSnapshots.seesAll(p), "a fake player is not an op");
        ChunkSnapshot s = ChunkSnapshots.snapshotFor(p);
        assertFalse(s.seesAll);
        assertTrue(s.tickets.isEmpty(), "saw someone else's ticket");
        for (ChunkSnapshot.Chunk c : s.chunks) {
            assertFalse(c.has(ChunkSnapshot.LOADED), "saw loaded state of a chunk that isn't theirs");
        }
    }

    @Test
    void ownerSeesTheirOwnTicket() {
        ForgeChunkManager.Ticket own = TestTickets.request(world, VIEWER.getName(), furnaceX, furnaceY, furnaceZ);
        try {
            ForgeChunkManager.forceChunk(own, chunk());
            ChunkSnapshot s = ChunkSnapshots.snapshotFor(viewer());
            ChunkSnapshot.Chunk c = find(s, furnaceX >> 4, furnaceZ >> 4);
            assertNotNull(c);
            assertEquals(1, c.tickets.length, "should see only their own of the two tickets");
            assertEquals(VIEWER.getName(), s.tickets.get(c.tickets[0]).owner);
            assertTrue(c.has(ChunkSnapshot.LOADED));
        } finally {
            ForgeChunkManager.releaseTicket(own);
        }
    }

    @Test
    void snapshotSurvivesTheWireFormat() throws Exception {
        Config.seeAllPlayers = new String[] { "LayerTest" };
        ChunkSnapshot s = ChunkSnapshots.snapshotFor(viewer());
        List<byte[]> parts = Blobs.split(Blobs.gzip(s::write), Blobs.PART_SIZE);
        Blobs.Assembler a = new Blobs.Assembler();
        byte[] whole = null;
        for (int i = 0; i < parts.size(); i++) whole = a.add(9, i, parts.size(), parts.get(i));
        assertNotNull(whole);
        ChunkSnapshot back;
        try (DataInputStream in = new DataInputStream(Blobs.gunzip(whole))) {
            back = ChunkSnapshot.read(in);
        }
        assertEquals(s.chunks.size(), back.chunks.size());
        assertEquals(s.tickets.size(), back.tickets.size());
    }

    @Test
    void blockNamesWorkOnTheDedicatedServer() {
        assertEquals("Furnace", TicketDescriber.blockName(world, furnaceX, furnaceY, furnaceZ));
        world.setBlock(furnaceX, furnaceY + 1, furnaceZ, Blocks.chest);
        try {
            assertEquals("Chest", TicketDescriber.blockName(world, furnaceX, furnaceY + 1, furnaceZ));
        } finally {
            world.setBlockToAir(furnaceX, furnaceY + 1, furnaceZ);
        }
        assertEquals(null, TicketDescriber.blockName(world, furnaceX, furnaceY + 2, furnaceZ));
        // A chunk that was never generated must not be loaded just to name a block.
        assertEquals(null, TicketDescriber.blockName(world, 29_000_000, 64, 29_000_000));
        assertFalse(
            world.getChunkProvider()
                .chunkExists(29_000_000 >> 4, 29_000_000 >> 4));
    }

    @Test
    void serverUtilitiesClaimsAreReadWhenInstalled() {
        Claims claims = Claims.detect();
        if (Loader.isModLoaded("serverutilities")) {
            assertEquals(
                "ServerUtilitiesClaims",
                claims.getClass()
                    .getSimpleName());
        }
        // Must not throw, with or without claims in the world.
        claims.teamOf(viewer());
        Config.seeAllPlayers = new String[] { "LayerTest" };
        ChunkSnapshots.snapshotFor(viewer());
    }

    @Test
    void commandReportListsTheTicket() {
        assertTrue(
            MinecraftServer.getServer()
                .getCommandManager()
                .getCommands()
                .containsKey("jsyncadmin"),
            "command not registered");
        List<String> lines = AdminCommand.report(world);
        assertTrue(
            lines.get(0)
                .startsWith("Dimension 0: "),
            lines.get(0));
        String expected = "  ticket: JourneySync functional tests, Steve (Furnace at " + furnaceX
            + ", "
            + furnaceY
            + ", "
            + furnaceZ
            + "): 1 chunks";
        assertTrue(lines.contains(expected), String.join("\n", lines));
    }

    @Test
    void requestFromAFakePlayerIsIgnoredQuietly() {
        // FakePlayers have no network handler. handle() must not try to send to one.
        ChunkSnapshots.handle(viewer());
    }
}
