package com.donkeytt.journeysync.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.donkeytt.journeysync.data.ChunkSnapshot;
import com.donkeytt.journeysync.data.ChunkStateBuilder;

/**
 * {@code /jsyncadmin}, ops only, works from the server console:
 * <ul>
 * <li>{@code map}: the shared map's id, size and revision, and how many players are syncing.</li>
 * <li>{@code chunks [dimension]}: what the chunk layer shows an op for a dimension, as counts plus every ticket with
 * its chunk count.</li>
 * </ul>
 * Players use the client-side {@code /jsync} for their own sync status.
 */
public class AdminCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "jsyncadmin";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/jsyncadmin map | chunks [dimension]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "map", "chunks") : null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0] : "";
        if (sub.equals("map")) {
            sender.addChatMessage(
                new ChatComponentText(
                    ServerSync.instance()
                        .status()));
            return;
        }
        if (!sub.equals("chunks")) throw new WrongUsageException(getCommandUsage(sender));
        WorldServer world;
        if (args.length > 1) {
            world = DimensionManager.getWorld(parseInt(sender, args[1]));
            if (world == null) throw new WrongUsageException("Dimension " + args[1] + " is not loaded");
        } else if (sender instanceof EntityPlayerMP player) {
            world = player.getServerForPlayer();
        } else {
            world = MinecraftServer.getServer()
                .worldServerForDimension(0);
        }
        for (String line : report(world)) sender.addChatMessage(new ChatComponentText(line));
    }

    /** The report lines for a dimension, as an op would see it. Server thread only. */
    public static List<String> report(WorldServer world) {
        long start = System.nanoTime();
        ChunkStateBuilder state = ChunkStateCollector.collect(world, ChunkSnapshots.claims());
        ChunkSnapshot s = state.build(new ChunkStateBuilder.Viewer(true, "", null));
        long micros = (System.nanoTime() - start) / 1000;

        int loaded = 0, forced = 0, claimed = 0;
        Map<Integer, Integer> chunksPerTicket = new LinkedHashMap<>();
        Map<Integer, Integer> chunksPerTeam = new LinkedHashMap<>();
        for (ChunkSnapshot.Chunk c : s.chunks) {
            if (c.has(ChunkSnapshot.LOADED)) loaded++;
            if (c.has(ChunkSnapshot.FORCED)) forced++;
            if (c.has(ChunkSnapshot.CLAIMED)) {
                claimed++;
                chunksPerTeam.merge(c.team, 1, Integer::sum);
            }
            for (int t : c.tickets) chunksPerTicket.merge(t, 1, Integer::sum);
        }

        List<String> lines = new ArrayList<>();
        lines.add(
            "Dimension " + s.dimension
                + ": "
                + loaded
                + " loaded, "
                + forced
                + " force-loaded, "
                + claimed
                + " claimed chunks; "
                + s.tickets.size()
                + " tickets, "
                + s.teams.size()
                + " teams. Collected in "
                + (micros / 1000.0)
                + " ms");
        for (Map.Entry<Integer, Integer> e : chunksPerTicket.entrySet()) {
            ChunkSnapshot.Ticket t = s.tickets.get(e.getKey());
            lines.add(
                "  ticket: " + t.mod
                    + ", "
                    + (t.owner.isEmpty() ? "unknown owner" : t.owner)
                    + (t.detail.isEmpty() ? "" : " (" + t.detail + ")")
                    + ": "
                    + e.getValue()
                    + " chunks");
        }
        for (Map.Entry<Integer, Integer> e : chunksPerTeam.entrySet()) {
            lines.add("  claims: " + s.teams.get(e.getKey()).name + ": " + e.getValue() + " chunks");
        }
        return lines;
    }
}
