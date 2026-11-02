package com.donkeytt.journeysync.test;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;

import org.apache.commons.io.output.CloseShieldOutputStream;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener;

import com.donkeytt.journeysync.Config;
import com.donkeytt.journeysync.JourneySync;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;

/**
 * Runs the JUnit tests in this package inside a started dev server, then stops it. Enabled by
 * `gradlew runServer -Pft`. Reports go to run/server/junit-out/. Pattern from ModularUI2's MUITestMod.
 * <p>
 * `gradlew runServer -Pdemo` instead sets up a furnace held loaded by a player ticket for "Steve", 3 chunks east of
 * spawn, so the client smoke test has a force-loaded chunk that isn't the player's own. It also adds the dev
 * client's "Developer" to seeAllPlayers, since that player isn't an op.
 */
@Mod(
    modid = FunctionalTestMod.MODID,
    name = "JourneySync functional tests",
    version = "1.0",
    dependencies = "required-after:journeysync",
    acceptableRemoteVersions = "*")
public class FunctionalTestMod {

    static final String MODID = "journeysync-tests";

    @Mod.Instance(MODID)
    public static FunctionalTestMod instance;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Forge only hands out tickets to mods with a loading callback. Tickets from the last run are dropped.
        ForgeChunkManager.setForcedChunkLoadingCallback(
            this,
            (List<ForgeChunkManager.Ticket> tickets, World world) -> {
                for (ForgeChunkManager.Ticket t : tickets) ForgeChunkManager.releaseTicket(t);
            });
    }

    @Mod.EventHandler
    public void onServerStarted(FMLServerStartedEvent event) {
        if (Boolean.getBoolean("journeysync.demo")) setUpDemo();
        if (!Boolean.getBoolean("journeysync.functionalTests")) return;

        File out = new File("junit-out").getAbsoluteFile();
        out.mkdirs();
        File[] old = out.listFiles((dir, name) -> name.endsWith(".xml"));
        if (old != null) for (File f : old) f.delete();

        LauncherDiscoveryRequest discovery = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("com.donkeytt.journeysync.test"))
            .build();
        SummaryGeneratingListener summary = new SummaryGeneratingListener();
        TestExecutionSummary result;
        try (PrintWriter err = new PrintWriter(new CloseShieldOutputStream(System.err), true)) {
            try (LauncherSession session = LauncherFactory.openSession()) {
                Launcher launcher = session.getLauncher();
                launcher
                    .registerTestExecutionListeners(summary, new LegacyXmlReportGeneratingListener(out.toPath(), err));
                launcher.execute(launcher.discover(discovery));
            }
            result = summary.getSummary();
            result.printFailuresTo(err, 32);
            result.printTo(err);
        }

        if (result.getTotalFailureCount() > 0) {
            FMLCommonHandler.instance()
                .exitJava(1, false);
        } else {
            MinecraftServer.getServer()
                .initiateShutdown();
        }
    }

    private void setUpDemo() {
        WorldServer world = MinecraftServer.getServer()
            .worldServerForDimension(0);
        ChunkCoordinates spawn = world.getSpawnPoint();
        int x = spawn.posX + 48, y = 200, z = spawn.posZ;
        world.setBlock(x, y, z, Blocks.furnace);
        ForgeChunkManager.Ticket ticket = TestTickets.request(world, "Steve", x, y, z);
        ForgeChunkManager.forceChunk(ticket, new ChunkCoordIntPair(x >> 4, z >> 4));
        ForgeChunkManager.forceChunk(ticket, new ChunkCoordIntPair((x >> 4) + 1, z >> 4));
        // The dev client's player isn't an op. Let it see everything, like an op would on Akliz.
        List<String> seeAll = new ArrayList<>(Arrays.asList(Config.seeAllPlayers));
        if (!seeAll.contains("Developer")) seeAll.add("Developer");
        Config.seeAllPlayers = seeAll.toArray(new String[0]);
        JourneySync.LOG.warn("Demo: furnace at {}, {}, {} force-loaded by a ticket for Steve", x, y, z);
    }
}
