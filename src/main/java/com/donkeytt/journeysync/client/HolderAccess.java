package com.donkeytt.journeysync.client;

/** Added to JourneyMap's {@code ImageHolder} by {@code MixinImageHolder}: one map type of one loaded region. */
public interface HolderAccess {

    /** Draws cells into JourneyMap's in-memory image, the way its own chunk renders are drawn, and marks it to save. */
    void journeysync$drawCells(int[] indices, int[][] cells);

    /** A copy of the in-memory image's pixels, or null if it has none. */
    int[] journeysync$pixels();
}
