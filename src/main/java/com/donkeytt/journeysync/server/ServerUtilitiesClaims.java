package com.donkeytt.journeysync.server;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.ForgeChunkManager;

import com.donkeytt.journeysync.data.ChunkStateBuilder;

import serverutils.data.ClaimedChunk;
import serverutils.data.ClaimedChunks;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;
import serverutils.lib.math.ChunkDimPos;

/** Claims and teams from ServerUtilities. Only loaded when the serverutilities mod is present. */
public final class ServerUtilitiesClaims implements Claims {

    static final String MODID = "serverutilities";

    public ServerUtilitiesClaims() {}

    @Override
    public void addClaims(ChunkStateBuilder builder, int dimension) {
        if (!ClaimedChunks.isActive()) return;
        for (ClaimedChunk chunk : ClaimedChunks.instance.getAllChunks()) {
            ChunkDimPos pos = chunk.getPos();
            if (pos.dim != dimension || chunk.isInvalid()) continue;
            ForgeTeam team = chunk.getTeam();
            if (team == null || !team.isValid()) continue;
            builder.addTeam(
                team.getId(),
                team.getTitle()
                    .getUnformattedText(),
                team.getColor()
                    .getColor()
                    .rgb());
            builder.claimed(pos.posX, pos.posZ, team.getId());
        }
    }

    @Override
    public String teamOf(EntityPlayerMP player) {
        Universe universe = Universe.getNullable();
        if (universe == null) return null;
        ForgePlayer p = universe.getPlayer(player.getGameProfile());
        return p != null && p.hasTeam() ? p.team.getId() : null;
    }

    @Override
    public String ticketTeam(ForgeChunkManager.Ticket ticket) {
        if (!MODID.equals(ticket.getModId()) || ticket.getModData() == null) return null;
        String id = ticket.getModData()
            .getString("Team");
        return id.isEmpty() ? null : id;
    }

    @Override
    public String teamName(String teamId) {
        Universe universe = Universe.getNullable();
        if (universe == null) return null;
        ForgeTeam team = universe.getTeam(teamId);
        return team != null && team.isValid() ? team.getTitle()
            .getUnformattedText() : null;
    }
}
