package com.donkeytt.journeysync.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Data too big for one packet (the waypoint list, a dimension's chunk snapshot): gzipped, split into parts that fit
 * a 1.7.10 custom payload (32767 bytes), and put back together on the other side.
 */
public final class Blobs {

    public static final int PART_SIZE = 30_000;
    /** Refuse to inflate more than this, so a bad packet can't exhaust memory. */
    private static final int MAX_INFLATED = 64 << 20;

    /** Writes the uncompressed content of a blob. */
    public interface Writer {

        void write(DataOutputStream out) throws IOException;
    }

    private Blobs() {}

    public static byte[] gzip(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            writer.write(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    public static byte[] gzip(String text) {
        return gzip(out -> out.write(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** An inflating stream over a gzipped blob, capped at 64 MB. */
    public static InputStream gunzip(byte[] gzipped) throws IOException {
        return new LimitedInputStream(new GZIPInputStream(new ByteArrayInputStream(gzipped)), MAX_INFLATED);
    }

    public static String gunzipString(byte[] gzipped) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = gunzip(gzipped)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public static List<byte[]> split(byte[] gzipped, int partSize) {
        List<byte[]> parts = new ArrayList<>();
        for (int i = 0; i < gzipped.length; i += partSize) {
            parts.add(Arrays.copyOfRange(gzipped, i, Math.min(gzipped.length, i + partSize)));
        }
        if (parts.isEmpty()) parts.add(new byte[0]);
        return parts;
    }

    /**
     * Collects the parts of one kind of blob. A part with a new id drops any unfinished earlier blob, since only the
     * latest one matters. Thread-safe.
     */
    public static final class Assembler {

        private int id = -1;
        private byte[][] parts;

        /** Returns the whole gzipped blob once its last part arrives, otherwise null. */
        public synchronized byte[] add(int id, int part, int total, byte[] data) throws IOException {
            if (total < 1 || total > 4096 || part < 0 || part >= total) {
                throw new IOException("bad part " + part + "/" + total);
            }
            if (id != this.id || parts == null || parts.length != total) {
                this.id = id;
                parts = new byte[total][];
            }
            parts[part] = data;
            for (byte[] p : parts) if (p == null) return null;
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (byte[] p : parts) all.write(p, 0, p.length);
            parts = null;
            return all.toByteArray();
        }

        public synchronized void clear() {
            id = -1;
            parts = null;
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) throw new IOException("blob too large");
            int b = super.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) throw new IOException("blob too large");
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }
}
