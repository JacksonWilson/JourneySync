package com.donkeytt.journeysync.server;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/**
 * Turns a ForgeChunkManager ticket into display text: the mod, who owns it, and what holds it. Mods store this in
 * different ways, so this checks the common ones: player tickets, ServerUtilities team tickets, entity tickets, and
 * block positions in the ticket's mod data (GregTech uses OwnerX/OwnerY/OwnerZ).
 */
public final class TicketDescriber {

    private static final String[][] POSITION_KEYS = { { "OwnerX", "OwnerY", "OwnerZ" },
        { "xCoord", "yCoord", "zCoord" }, { "x", "y", "z" }, { "posX", "posY", "posZ" } };
    private static final String[] OWNER_KEYS = { "owner", "Owner", "ownerName", "OwnerName", "player", "Player" };

    /** Display text plus the ownership used for visibility. Fields may be null. */
    public static final class Description {

        public String mod;
        public String owner;
        public String detail;
        public String ownerPlayer;
        public String ownerTeam;
    }

    private TicketDescriber() {}

    public static Description describe(ForgeChunkManager.Ticket ticket, World world, Claims claims) {
        Description d = new Description();
        d.mod = modName(ticket.getModId());

        String team = claims.ticketTeam(ticket);
        NBTTagCompound data = ticket.getModData();
        if (team != null) {
            d.ownerTeam = team;
            String name = claims.teamName(team);
            d.owner = name != null ? name : team;
            d.detail = "claimed chunk loading";
        } else if (ticket.isPlayerTicket()) {
            d.ownerPlayer = ticket.getPlayerName();
            d.owner = d.ownerPlayer;
        } else if (data != null) {
            for (String key : OWNER_KEYS) {
                if (data.hasKey(key, 8)) {
                    d.ownerPlayer = data.getString(key);
                    d.owner = d.ownerPlayer;
                    break;
                }
            }
        }

        Entity entity = ticket.getEntity();
        if (entity != null) {
            d.detail = entity.getCommandSenderName() + " at "
                + (int) Math.floor(entity.posX)
                + ", "
                + (int) Math.floor(entity.posY)
                + ", "
                + (int) Math.floor(entity.posZ);
        } else if (data != null && d.detail == null) {
            int[] pos = position(data);
            if (pos != null) {
                String block = blockName(world, pos[0], pos[1], pos[2]);
                d.detail = (block != null ? block + " at " : "at ") + pos[0] + ", " + pos[1] + ", " + pos[2];
            }
        }
        return d;
    }

    static String modName(String modId) {
        ModContainer mod = Loader.instance()
            .getIndexedModList()
            .get(modId);
        return mod != null ? mod.getName() : modId;
    }

    private static int[] position(NBTTagCompound data) {
        for (String[] keys : POSITION_KEYS) {
            // 99 matches any numeric tag type.
            if (data.hasKey(keys[0], 99) && data.hasKey(keys[1], 99) && data.hasKey(keys[2], 99)) {
                int y = data.getInteger(keys[1]);
                if (y <= 0) continue;
                return new int[] { data.getInteger(keys[0]), y, data.getInteger(keys[2]) };
            }
        }
        return null;
    }

    /** The name of the block at a position, or null. Never loads a chunk. */
    public static String blockName(World world, int x, int y, int z) {
        if (y < 0 || y > 255
            || !world.getChunkProvider()
                .chunkExists(x >> 4, z >> 4))
            return null;
        Block block = world.getBlock(x, y, z);
        if (block.isAir(world, x, y, z)) return null;
        try {
            MovingObjectPosition hit = new MovingObjectPosition(
                x,
                y,
                z,
                1,
                Vec3.createVectorHelper(x + 0.5, y + 0.5, z + 0.5));
            ItemStack stack = block.getPickBlock(hit, world, x, y, z);
            if (stack != null && stack.getItem() != null) return stack.getDisplayName();
        } catch (Throwable ignored) {
            // Some mods' pick-block code is client only or assumes a player. Fall back to the block's own name.
        }
        try {
            return block.getLocalizedName();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
