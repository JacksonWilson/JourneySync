package com.donkeytt.journeysync.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.donkeytt.journeysync.data.TileFiles;
import com.donkeytt.journeysync.data.TileImage;
import com.donkeytt.journeysync.data.TileImageTest;

public class OlderMapsTest {

    private static final String WORLD_ID = "621c6c34-9d02-48bc-a7ca-85c0b9d3377b";
    private static final String SUFFIX = "_621c6c34~9d02~48bc~a7ca~85c0b9d3377b";

    @TempDir
    File mp;

    private File folder(String name) {
        File f = new File(mp, name);
        f.mkdirs();
        return f;
    }

    @Test
    public void findsOtherServerNamesForTheSameWorldOnly() {
        File current = folder("Minecraft~Server" + SUFFIX);
        File ggg = folder("GGG" + SUFFIX);
        folder("GGG");
        folder("Minecraft~Server");
        folder("Other_97f200c0~af21~4cff~8923~82604f465b68");

        assertEquals(Collections.singletonList(ggg), OlderMaps.find(current, WORLD_ID));
        assertTrue(
            OlderMaps.find(current, null)
                .isEmpty());
        // JourneyMap didn't use the world id for this folder, so there is nothing to match.
        assertTrue(
            OlderMaps.find(folder("Minecraft~Server"), WORLD_ID)
                .isEmpty());
    }

    @Test
    public void mergeFillsTilesAndCopiesMissingWaypoints() throws Exception {
        File current = folder("Minecraft~Server" + SUFFIX);
        File ggg = folder("GGG" + SUFFIX);
        TileImage mine = new TileImage();
        mine.merge(0, TileImageTest.cell(0xFF000001), false);
        TileFiles.writePixels(new File(current, "DIM0/day/0,0.png"), mine.pixels);
        TileImage old = new TileImage();
        old.merge(0, TileImageTest.cell(0xFF0000AA), false);
        old.merge(5, TileImageTest.cell(0xFF0000BB), false);
        TileFiles.writePixels(new File(ggg, "DIM0/day/0,0.png"), old.pixels);
        TileFiles.writePixels(new File(ggg, "DIM-1/topo/3,-2.png"), old.pixels);
        write(new File(current, "waypoints/Home_1,64,2.json"), "mine");
        write(new File(ggg, "waypoints/Home_1,64,2.json"), "old");
        write(new File(ggg, "waypoints/Mine_9,12,40.json"), "old");

        OlderMaps.Result r = OlderMaps.mergeAll(OlderMaps.find(current, WORLD_ID), current);

        assertEquals(1, r.folders);
        assertEquals(2, r.tiles);
        assertEquals(1, r.waypoints);
        TileImage merged = new TileImage(
            TileFiles.readPixels(new File(current, "DIM0/day/0,0.png")),
            new long[TileImage.CELLS]);
        assertArrayEquals(TileImageTest.cell(0xFF000001), merged.cell(0));
        assertArrayEquals(TileImageTest.cell(0xFF0000BB), merged.cell(5));
        assertTrue(new File(current, "DIM-1/topo/3,-2.png").isFile());
        assertEquals("mine", read(new File(current, "waypoints/Home_1,64,2.json")));
        assertEquals("old", read(new File(current, "waypoints/Mine_9,12,40.json")));

        OlderMaps.Result again = OlderMaps.mergeAll(OlderMaps.find(current, WORLD_ID), current);
        assertEquals(0, again.tiles);
        assertEquals(0, again.waypoints);
    }

    private static void write(File f, String text) throws Exception {
        TileFiles.writeAtomic(f, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
