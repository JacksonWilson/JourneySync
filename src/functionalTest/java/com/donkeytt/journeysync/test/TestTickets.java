package com.donkeytt.journeysync.test;

import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;

final class TestTickets {

    private TestTickets() {}

    /** A player ticket that records the block holding it, the way GregTech's chunk loaders do. */
    static ForgeChunkManager.Ticket request(World world, String player, int x, int y, int z) {
        ForgeChunkManager.Ticket ticket = ForgeChunkManager
            .requestPlayerTicket(FunctionalTestMod.instance, player, world, ForgeChunkManager.Type.NORMAL);
        if (ticket == null) throw new IllegalStateException("Forge refused a ticket");
        ticket.getModData()
            .setInteger("OwnerX", x);
        ticket.getModData()
            .setInteger("OwnerY", y);
        ticket.getModData()
            .setInteger("OwnerZ", z);
        return ticket;
    }
}
