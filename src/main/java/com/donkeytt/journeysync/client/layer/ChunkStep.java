package com.donkeytt.journeysync.client.layer;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.donkeytt.journeysync.data.ChunkEdges;
import com.donkeytt.journeysync.data.ChunkSnapshot;
import com.gtnewhorizons.navigator.api.model.steps.UniversalLocationInteractableStep;
import com.gtnewhorizons.navigator.api.util.DrawUtils;

/**
 * Draws one chunk. Claimed chunks are filled with the team colour (fainter when not loaded) and a block of claims gets
 * a thin outline in that colour. Other loaded chunks get a light wash. A block of force-loaded chunks gets one orange
 * outline around its outside. Fills are lighter on the minimap so the terrain stays readable.
 */
public class ChunkStep extends UniversalLocationInteractableStep<ChunkLocation> {

    static final int FORCED_COLOR = 0xFF8C00;
    static final int LOADED_COLOR = 0xFFFFFF;
    private static final int MAX_TICKET_LINES = 6;

    public ChunkStep(ChunkLocation location) {
        super(location);
        setSize(16);
    }

    @Override
    public void draw(double x, double y, float drawScale, double zoom) {
        double w = getAdjustedWidth();
        double h = getAdjustedHeight();
        if (!isXaero) {
            Minecraft mc = Minecraft.getMinecraft();
            if (x + w < 0 || y + h < 0 || x > mc.displayWidth || y > mc.displayHeight) return;
        }
        boolean minimap = isMinimap();
        double fade = minimap ? 0.65 : 1;
        ChunkSnapshot.Chunk c = location.chunk;
        boolean loaded = c.has(ChunkSnapshot.LOADED);
        ChunkSnapshot.Team team = location.team();

        double teamLine = 0;
        if (team != null) {
            boolean knownUnloaded = c.has(ChunkSnapshot.LOAD_KNOWN) && !loaded;
            DrawUtils.drawRect(x, y, w, h, team.rgb, (int) ((knownUnloaded ? 45 : 100) * fade));
            teamLine = Math.max(1, w / 32);
            edges(x, y, w, h, location.teamEdges, teamLine, 0, team.rgb, 230);
        } else if (loaded) {
            DrawUtils.drawRect(x, y, w, h, LOADED_COLOR, (int) (45 * fade));
        }
        if (location.forcedEdges != 0) {
            double line = minimap ? Math.max(1, w / 16) : Math.max(1, Math.min(2.5, w / 12));
            // Inside the team outline, so both stay visible where a claim's edge is also a force-loaded edge.
            edges(x, y, w, h, location.forcedEdges, line, teamLine, FORCED_COLOR, 255);
        }
    }

    /** Draws a line of the given thickness along each side in {@code mask}, {@code inset} in from the chunk edge. */
    private static void edges(double x, double y, double w, double h, int mask, double t, double inset, int rgb,
        int alpha) {
        if ((mask & ChunkEdges.NORTH) != 0) DrawUtils.drawRect(x, y + inset, w, t, rgb, alpha);
        if ((mask & ChunkEdges.SOUTH) != 0) DrawUtils.drawRect(x, y + h - inset - t, w, t, rgb, alpha);
        if ((mask & ChunkEdges.WEST) != 0) DrawUtils.drawRect(x + inset, y, t, h, rgb, alpha);
        if ((mask & ChunkEdges.EAST) != 0) DrawUtils.drawRect(x + w - inset - t, y, t, h, rgb, alpha);
    }

    @Override
    public void getTooltip(List<String> list) {
        tooltip(location, list);
    }

    static void tooltip(ChunkLocation location, List<String> list) {
        ChunkSnapshot.Chunk c = location.chunk;
        list.add(
            EnumChatFormatting.WHITE + StatCollector
                .translateToLocalFormatted("journeysync.layer.tooltip.chunk", c.x, c.z, c.x << 4, c.z << 4));
        ChunkSnapshot.Team team = location.team();
        if (team != null) {
            list.add(
                EnumChatFormatting.AQUA
                    + StatCollector.translateToLocalFormatted("journeysync.layer.tooltip.claimed", team.name));
        }
        if (c.has(ChunkSnapshot.LOAD_KNOWN)) {
            list.add(
                c.has(ChunkSnapshot.LOADED)
                    ? EnumChatFormatting.GREEN + StatCollector.translateToLocal("journeysync.layer.tooltip.loaded")
                    : EnumChatFormatting.DARK_GRAY
                        + StatCollector.translateToLocal("journeysync.layer.tooltip.unloaded"));
        }
        if (c.tickets.length > 0) {
            list.add(EnumChatFormatting.GOLD + StatCollector.translateToLocal("journeysync.layer.tooltip.forced"));
            for (int i = 0; i < c.tickets.length && i < MAX_TICKET_LINES; i++) {
                list.add(ticketLine(location.snapshot.tickets.get(c.tickets[i])));
            }
            if (c.tickets.length > MAX_TICKET_LINES) {
                list.add(
                    EnumChatFormatting.GRAY + "  "
                        + StatCollector.translateToLocalFormatted(
                            "journeysync.layer.tooltip.more",
                            c.tickets.length - MAX_TICKET_LINES));
            }
        }
    }

    private static String ticketLine(ChunkSnapshot.Ticket t) {
        StringBuilder sb = new StringBuilder("  ").append(EnumChatFormatting.YELLOW)
            .append(t.mod);
        String owner = t.owner.isEmpty() ? StatCollector.translateToLocal("journeysync.layer.tooltip.unknown_owner")
            : t.owner;
        sb.append(EnumChatFormatting.GRAY)
            .append(": ")
            .append(EnumChatFormatting.WHITE)
            .append(owner);
        if (!t.detail.isEmpty()) {
            sb.append(EnumChatFormatting.DARK_GRAY)
                .append(" (")
                .append(t.detail)
                .append(")");
        }
        return sb.toString();
    }
}
