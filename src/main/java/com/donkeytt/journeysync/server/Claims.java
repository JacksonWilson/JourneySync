package com.donkeytt.journeysync.server;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.ForgeChunkManager;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.data.ChunkStateBuilder;

import cpw.mods.fml.common.Loader;

/** Source of chunk claims and teams. ServerUtilities when installed, otherwise nothing. */
public interface Claims {

    /** Adds every claim in the dimension, with its team. */
    void addClaims(ChunkStateBuilder builder, int dimension);

    /** The player's team id, or null. */
    String teamOf(EntityPlayerMP player);

    /** The team that owns a ticket, for tickets that record one, or null. */
    String ticketTeam(ForgeChunkManager.Ticket ticket);

    /** Display name of a team, or null if unknown. */
    String teamName(String teamId);

    static Claims detect() {
        if (Loader.isModLoaded("serverutilities")) {
            // Loaded by name so the JVM never touches ServerUtilities classes on a server without it.
            try {
                return (Claims) Class.forName("com.donkeytt.journeysync.server.ServerUtilitiesClaims")
                    .getDeclaredConstructor()
                    .newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                JourneySync.LOG.error("ServerUtilities is installed but its claims could not be read", e);
            }
        }
        return new Claims() {

            @Override
            public void addClaims(ChunkStateBuilder builder, int dimension) {}

            @Override
            public String teamOf(EntityPlayerMP player) {
                return null;
            }

            @Override
            public String ticketTeam(ForgeChunkManager.Ticket ticket) {
                return null;
            }

            @Override
            public String teamName(String teamId) {
                return null;
            }
        };
    }
}
