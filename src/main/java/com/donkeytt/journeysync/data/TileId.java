package com.donkeytt.journeysync.data;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One JourneyMap region tile: a dimension, a map type and a region position. A region is 32x32 chunks, drawn as a
 * 512x512 image at {@code DIM<dim>/<type>/<x>,<z>.png}.
 *
 * <p>
 * Map types are stored as a byte: cave slices {@code 0..15}, then {@link #DAY}, {@link #NIGHT} and {@link #TOPO}.
 * </p>
 */
public final class TileId {

    public static final byte DAY = 16;
    public static final byte NIGHT = 17;
    public static final byte TOPO = 18;

    private static final Pattern REL_PATH = Pattern.compile("DIM(-?\\d+)/(day|night|topo|\\d{1,2})/(-?\\d+),(-?\\d+)");

    public final int dimension;
    public final byte type;
    public final int regionX;
    public final int regionZ;

    public TileId(int dimension, byte type, int regionX, int regionZ) {
        if (type < 0 || type > TOPO) {
            throw new IllegalArgumentException("bad map type " + type);
        }
        this.dimension = dimension;
        this.type = type;
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    /** The folder JourneyMap uses for this map type. */
    public String typeFolder() {
        return typeFolder(type);
    }

    public static String typeFolder(byte type) {
        switch (type) {
            case DAY:
                return "day";
            case NIGHT:
                return "night";
            case TOPO:
                return "topo";
            default:
                return Integer.toString(type);
        }
    }

    /** Parses a folder name, or returns -1 if it isn't a map type JourneyMap writes. */
    public static byte parseType(String folder) {
        switch (folder) {
            case "day":
                return DAY;
            case "night":
                return NIGHT;
            case "topo":
                return TOPO;
            default:
                try {
                    int slice = Integer.parseInt(folder);
                    return slice >= 0 && slice < 16 ? (byte) slice : -1;
                } catch (NumberFormatException e) {
                    return -1;
                }
        }
    }

    /** {@code DIM0/day/-1,2}, without an extension. */
    public String relPath() {
        return "DIM" + dimension + "/" + typeFolder() + "/" + regionX + "," + regionZ;
    }

    /** Parses {@link #relPath()} output, or returns null. Strict, so it doubles as a path traversal check. */
    public static TileId parse(String relPath) {
        Matcher m = REL_PATH.matcher(relPath);
        if (!m.matches()) {
            return null;
        }
        byte type = parseType(m.group(2));
        if (type < 0) {
            return null;
        }
        try {
            return new TileId(
                Integer.parseInt(m.group(1)),
                type,
                Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Index of a chunk's cell inside its region tile: {@code (z & 31) * 32 + (x & 31)}. */
    public static int cellIndex(int chunkX, int chunkZ) {
        return (chunkZ & 31) * 32 + (chunkX & 31);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TileId)) return false;
        TileId t = (TileId) o;
        return dimension == t.dimension && type == t.type && regionX == t.regionX && regionZ == t.regionZ;
    }

    @Override
    public int hashCode() {
        return ((dimension * 31 + type) * 31 + regionX) * 31 + regionZ;
    }

    @Override
    public String toString() {
        return relPath();
    }
}
