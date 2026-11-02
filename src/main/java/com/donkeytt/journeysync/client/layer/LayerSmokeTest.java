package com.donkeytt.journeysync.client.layer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ScreenShotHelper;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.client.ChunkLayerState;
import com.donkeytt.journeysync.data.ChunkSnapshot;
import com.gtnewhorizons.navigator.api.NavigatorApi;
import com.gtnewhorizons.navigator.api.model.SupportedMods;
import com.gtnewhorizons.navigator.api.model.layers.LayerRenderer;
import com.gtnewhorizons.navigator.api.model.layers.UniversalInteractableRenderer;
import com.gtnewhorizons.navigator.api.model.steps.RenderStep;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Dev-client check, enabled by {@code gradlew runClient -Pjoin=127.0.0.1:25570 -Psmoke}. After joining it claims and
 * loads the chunk it stands in through ServerUtilities commands, opens the JourneyMap fullscreen map with the layer
 * on, hovers the most interesting chunk on screen, toggles the layer off and on, closes the map to check the
 * minimap, and saves screenshots plus a report
 * to {@code run/client/journeysync-smoke/}. Then it quits the game.
 */
public final class LayerSmokeTest {

    /** Mode "readonly" only looks, for a copy of a real world. Otherwise it claims a chunk first. */
    private final boolean readOnly = "readonly".equals(mode());
    /** Mode "noserver": joined a server without JourneySync. The layer must stay empty and nothing may be sent. */
    private final boolean noServer = "noserver".equals(mode());
    private final List<String> report = new ArrayList<>();
    private final File dir;
    private int tick;
    private int joinedAt = -1;
    private int mapOpenedAt = -1;
    private int failures;

    private LayerSmokeTest() {
        dir = new File(Minecraft.getMinecraft().mcDataDir, "journeysync-smoke");
    }

    /**
     * The smoke test mode, or null when it is off: {@code -Djourneysync.smoke=<mode>}, or else the first line of
     * {@code journeysync-smoke.txt} in the game folder. The file is for launchers that make JVM flags awkward.
     */
    public static String mode() {
        String mode = System.getProperty("journeysync.smoke");
        if (mode != null) return mode;
        File file = new File(Minecraft.getMinecraft().mcDataDir, "journeysync-smoke.txt");
        if (!file.isFile()) return null;
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            return lines.isEmpty() ? ""
                : lines.get(0)
                    .trim();
        } catch (IOException e) {
            return "";
        }
    }

    public static void start() {
        JourneySync.LOG.warn("Smoke test mode: will join, check the map layer, and quit");
        FMLCommonHandler.instance()
            .bus()
            .register(new LayerSmokeTest());
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tick++;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (joinedAt < 0) {
                if (mc.thePlayer != null && (noServer || ChunkLayerState.serverHasMod())) {
                    joinedAt = tick;
                    log(noServer ? "joined a server" : "joined, server has the mod");
                } else if (tick > 20 * 900) {
                    fail("never joined a server with the mod");
                    finish();
                }
                return;
            }
            int t = tick - joinedAt;
            if (noServer) {
                tickWithoutServerMod(mc, t);
                return;
            }
            if (t == 40 && !readOnly) {
                // ServerUtilities: a team, then claim and chunk-load the chunk the player stands in.
                mc.thePlayer.sendChatMessage("/team create smoketest blue");
                mc.thePlayer.sendChatMessage("/chunks claim");
                mc.thePlayer.sendChatMessage("/chunks load");
            } else if (t == 100) {
                openMap(mc);
                mapOpenedAt = tick;
            } else if (mapOpenedAt > 0 && tick - mapOpenedAt == 160) {
                checkLayer(mc);
            } else if (mapOpenedAt > 0 && tick - mapOpenedAt == 200) {
                screenshot(mc, "layer-off.png");
                ChunkLayerButton.INSTANCE.toggle();
                check(isActive(), "layer active again after a second toggle");
            } else if (mapOpenedAt > 0 && tick - mapOpenedAt == 220) {
                // Back to the game view, where JourneyMap draws its minimap with the layer still on. The dev window
                // usually isn't focused, so stop Minecraft from opening the pause menu over it.
                mc.gameSettings.pauseOnLostFocus = false;
                mc.displayGuiScreen(null);
                mc.guiAchievement.func_146257_b();
                ChunkLayer.INSTANCE.minimapRecaches = 0;
            } else if (mapOpenedAt > 0 && tick - mapOpenedAt == 300) {
                checkMinimap(mc);
            } else if (mapOpenedAt > 0 && tick - mapOpenedAt == 320) {
                finish();
            }
        } catch (Throwable e) {
            fail("exception: " + e);
            JourneySync.LOG.error("Smoke test failed", e);
            finish();
        }
    }

    private void openMap(Minecraft mc) throws ReflectiveOperationException {
        Class<?> ui = Class.forName("journeymap.client.ui.UIManager");
        Object manager = ui.getMethod("getInstance")
            .invoke(null);
        ui.getMethod("openFullscreenMap")
            .invoke(manager);
        new NavigatorApi().openJourneyMapAt(ChunkLayer.INSTANCE, (int) mc.thePlayer.posX, (int) mc.thePlayer.posZ, 1);
        log("opened JourneyMap fullscreen at zoom 1, screen " + mc.currentScreen);
    }

    /** The client-only case: the server doesn't have the mod. */
    private void tickWithoutServerMod(Minecraft mc, int t) throws ReflectiveOperationException {
        if (t == 100) {
            check(!ChunkLayerState.serverHasMod(), "the server didn't greet the client, so it doesn't have the mod");
            openMap(mc);
        } else if (t == 200) {
            check(isActive(), "layer can still be turned on");
            check(ChunkLayerState.snapshotFor(mc.thePlayer.dimension) == null, "no snapshot");
            UniversalInteractableRenderer renderer = renderer();
            check(
                renderer != null && renderer.getRenderSteps()
                    .isEmpty(),
                "nothing drawn on the fullscreen map");
            String button = ChunkLayerButton.INSTANCE.getButtonText();
            check(button.contains("doesn't have"), "button tooltip says so: " + button);
            check(ChunkLayerState.requestsSent() == 0, "sent nothing to the server");
            screenshot(mc, "noserver-map.png");
        } else if (t == 220) {
            mc.gameSettings.pauseOnLostFocus = false;
            mc.displayGuiScreen(null);
            ChunkLayer.INSTANCE.minimapRecaches = 0;
        } else if (t == 300) {
            check(ChunkLayer.INSTANCE.minimapRecaches > 0, "minimap redrew the layer");
            UniversalInteractableRenderer renderer = renderer();
            check(
                renderer != null && renderer.getRenderSteps()
                    .isEmpty(),
                "nothing drawn on the minimap");
            check(ChunkLayerState.requestsSent() == 0, "still sent nothing to the server");
            check(mc.theWorld != null && mc.getNetHandler() != null, "still connected");
            screenshot(mc, "noserver-minimap.png");
            finish();
        }
    }

    private void checkMinimap(Minecraft mc) {
        check(mc.currentScreen == null, "no screen open, so only the minimap draws");
        check(isActive(), "layer still active on the minimap");
        int recaches = ChunkLayer.INSTANCE.minimapRecaches;
        check(recaches > 0, "minimap redrew the layer " + recaches + " times in 4 s");
        UniversalInteractableRenderer renderer = renderer();
        int steps = renderer == null ? 0
            : renderer.getRenderSteps()
                .size();
        check(steps > 0, "minimap has " + steps + " chunk steps");
        screenshot(mc, "minimap.png");
    }

    private void checkLayer(Minecraft mc) throws IOException {
        log(
            "JourneyMap " + Loader.instance()
                .getIndexedModList()
                .get("journeymap")
                .getVersion() + (hasClass("journeymap.client.feature.impl.Unlimited") ? " unlimited" : " fairplay"));
        check(isActive(), "layer is active");
        ChunkSnapshot snapshot = ChunkLayerState.snapshotFor(mc.thePlayer.dimension);
        check(snapshot != null, "received a snapshot for dimension " + mc.thePlayer.dimension);
        if (snapshot != null) {
            int forced = 0, loaded = 0, claimed = 0;
            for (ChunkSnapshot.Chunk c : snapshot.chunks) {
                if (c.has(ChunkSnapshot.FORCED)) forced++;
                if (c.has(ChunkSnapshot.LOADED)) loaded++;
                if (c.has(ChunkSnapshot.CLAIMED)) claimed++;
            }
            log(
                "snapshot: " + snapshot.chunks.size()
                    + " chunks, "
                    + loaded
                    + " loaded, "
                    + forced
                    + " forced, "
                    + claimed
                    + " claimed, "
                    + snapshot.tickets.size()
                    + " tickets, "
                    + snapshot.teams.size()
                    + " teams, seesAll="
                    + snapshot.seesAll);
            check(loaded > 0, "snapshot has loaded chunks");
            // A real world may have neither in this dimension, so only the demo world requires them.
            expect(forced > 0, "snapshot has force-loaded chunks");
            expect(claimed > 0, "snapshot has claimed chunks");
        }

        UniversalInteractableRenderer renderer = renderer();
        check(renderer != null, "JourneyMap renderer registered");
        if (renderer == null) return;
        List<? extends RenderStep> steps = renderer.getRenderSteps();
        check(!steps.isEmpty(), "render steps drawn: " + steps.size());
        ChunkStep target = pickTarget(steps, mc);
        check(target != null, "a chunk step is on screen");
        if (target != null) {
            int mx = (int) (target.getX() + target.getAdjustedWidth() / 2);
            int my = (int) (target.getY() + target.getAdjustedHeight() / 2);
            renderer.onMouseMove(mx, my);
            List<String> tooltip = renderer.getTooltip();
            check(
                !tooltip.isEmpty(),
                "tooltip at " + mx
                    + ", "
                    + my
                    + " for chunk "
                    + target.getLocation().chunk.x
                    + ", "
                    + target.getLocation().chunk.z);
            for (String line : tooltip) log("  tooltip: " + EnumChatFormatting.getTextWithoutFormattingCodes(line));
        }
        screenshot(mc, "layer-on.png");

        ChunkLayerButton.INSTANCE.toggle();
        check(!isActive(), "layer inactive after toggle");
        check(renderer() == null, "renderer no longer active after toggle");
    }

    /** Prefers a force-loaded chunk, then a claimed one, then any. */
    private static ChunkStep pickTarget(List<? extends RenderStep> steps, Minecraft mc) {
        ChunkStep best = null;
        int bestScore = -1;
        for (RenderStep s : steps) {
            if (!(s instanceof ChunkStep step)) continue;
            double x = step.getX(), y = step.getY();
            if (x < 0 || y < 0
                || x + step.getAdjustedWidth() > mc.displayWidth
                || y + step.getAdjustedHeight() > mc.displayHeight) continue;
            ChunkSnapshot.Chunk c = step.getLocation().chunk;
            int score = (c.has(ChunkSnapshot.FORCED) ? 4 : 0) + (c.has(ChunkSnapshot.CLAIMED) ? 2 : 0) + 1;
            if (score > bestScore) {
                bestScore = score;
                best = step;
            }
        }
        return best;
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, LayerSmokeTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean isActive() {
        return ChunkLayer.INSTANCE.isLayerActive();
    }

    private static UniversalInteractableRenderer renderer() {
        for (LayerRenderer r : NavigatorApi.getActiveRenderersFor(SupportedMods.JourneyMap)) {
            if (r instanceof UniversalInteractableRenderer u
                && ChunkLayer.INSTANCE.getLayerRenderer(SupportedMods.JourneyMap) == r) {
                return u;
            }
        }
        return null;
    }

    private void screenshot(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        log("screenshot screenshots/" + name);
    }

    private void check(boolean ok, String what) {
        if (!ok) failures++;
        log((ok ? "PASS " : "FAIL ") + what);
    }

    /** A check in the demo world; in read-only mode just a note. */
    private void expect(boolean ok, String what) {
        if (readOnly) log((ok ? "PASS " : "NOTE not met: ") + what);
        else check(ok, what);
    }

    private void fail(String what) {
        check(false, what);
    }

    private void log(String line) {
        report.add(line);
        JourneySync.LOG.warn("[smoke] {}", line);
    }

    private void finish() {
        log(failures == 0 ? "RESULT PASS" : "RESULT FAIL (" + failures + ")");
        try {
            dir.mkdirs();
            Files.write(new File(dir, "report.txt").toPath(), report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            JourneySync.LOG.error("Could not write the smoke report", e);
        }
        Minecraft.getMinecraft()
            .shutdown();
    }
}
