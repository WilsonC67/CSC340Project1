package Services.Compression;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import Source.Node;
import Source.Service;

/**
 * CompressionNode — Microservice node for COMPRESSION_DECOMPRESSION (service #3).
 *
 * Uses a raw binary streaming protocol — no base64 anywhere:
 *
 * Request (HTTPGateway → Pipe → this node):
 *   {"service":3,"operation":"compress"|"decompress","filename":"<name>"}\n
 *   <raw binary file bytes>
 *
 * Response (this node → Pipe → HTTPGateway):
 *   {"status":"ok","filename":"<output name>"}\n
 *   <raw binary result bytes>
 *     — or on error —
 *   {"status":"error","message":"<details>"}\n
 *
 * This eliminates the ~33 % base64 size overhead and avoids buffering the full
 * payload in heap, keeping memory usage O(1) regardless of file size.
 *
 * Directory: Services/Compression/CompressionNode.java
 */
public class CompressionNode extends Node {

    private static final int NODE_ID = 3;

    /** Log a progress update every this many bytes read/written. */
    private static final long LOG_INTERVAL_BYTES = 10 * 1024 * 1024; // 10 MB

    // -----------------------------------------------------------------------
    // Node identity
    // -----------------------------------------------------------------------

    @Override
    protected int getNodeId() { return NODE_ID; }

    @Override
    protected Service getService() { return Service.COMPRESSION_DECOMPRESSION; }

    // -----------------------------------------------------------------------
    // Raw binary streaming handler
    // -----------------------------------------------------------------------

    /**
     * Reads the one-line JSON header, then streams raw bytes through
     * ZipOutputStream (compress) or ZipInputStream (decompress) directly
     * to/from the socket — no buffering, no base64.
     */
    @Override
    protected void handleRequestStreaming(InputStream rawIn, OutputStream rawOut) throws Exception {
        // Read the JSON header line (terminated by '\n')
        StringBuilder header = new StringBuilder();
        int b;
        while ((b = rawIn.read()) != -1 && b != '\n') {
            header.append((char) b);
            if (header.length() > 8192) throw new IOException("Header line exceeds 8 KB");
        }
        if (header.length() == 0) {
            writeError(rawOut, "Empty request header");
            return;
        }

        String headerStr = header.toString();
        String operation = extractJsonString(headerStr, "operation");
        String filename  = extractJsonString(headerStr, "filename");

        System.out.printf("[CompressionNode] op=%s file=%s%n", operation, filename);

        if (operation == null || operation.isBlank()) {
            writeError(rawOut, "Missing \"operation\" field. Expected \"compress\" or \"decompress\".");
            return;
        }
        if (filename == null || filename.isBlank()) filename = "file";

        switch (operation.trim().toLowerCase()) {
            case "compress"   -> streamCompressRaw(rawIn, rawOut, filename);
            case "decompress" -> streamDecompressRaw(rawIn, rawOut, filename);
            default           -> writeError(rawOut, "Unknown operation \"" + operation + "\".");
        }
    }

    /**
     * Writes the JSON response header line, then streams raw bytes from rawIn
     * through a ZipOutputStream directly to rawOut.
     * Closing ZipOutputStream flushes final Deflater bytes and signals EOF to Pipe.
     */
    private static void streamCompressRaw(InputStream rawIn, OutputStream rawOut, String filename)
            throws IOException {
        String outName = filename + ".zip";
        rawOut.write((responseOk(outName) + "\n").getBytes(StandardCharsets.UTF_8));
        rawOut.flush();
        long startMs = System.currentTimeMillis();
        CountingInputStream in = new CountingInputStream(rawIn, "read", LOG_INTERVAL_BYTES);
        try (ZipOutputStream zip = new ZipOutputStream(rawOut)) {
            zip.putNextEntry(new ZipEntry(filename));
            in.transferTo(zip);
            zip.closeEntry();
        } // zip.close() → Deflater.end() (releases native memory) + closes rawOut (signals EOF to Pipe)
        System.out.printf("[CompressionNode] Compress done — in: %s, time: %.2fs%n",
                formatBytes(in.total()), (System.currentTimeMillis() - startMs) / 1000.0);
    }

    /**
     * Writes the JSON response header line, then streams decompressed bytes
     * from rawIn through a ZipInputStream directly to rawOut.
     */
    private static void streamDecompressRaw(InputStream rawIn, OutputStream rawOut, String filename)
            throws IOException {
        String outName = filename.endsWith(".zip")
                ? filename.substring(0, filename.length() - 4)
                : filename + ".decompressed";
        rawOut.write((responseOk(outName) + "\n").getBytes(StandardCharsets.UTF_8));
        rawOut.flush();
        long startMs = System.currentTimeMillis();
        CountingOutputStream out = new CountingOutputStream(rawOut, "wrote", LOG_INTERVAL_BYTES);
        try (ZipInputStream zip = new ZipInputStream(rawIn)) {
            while (zip.getNextEntry() != null) {
                zip.transferTo(out);
                zip.closeEntry();
            }
        }
        System.out.printf("[CompressionNode] Decompress done — out: %s, time: %.2fs%n",
                formatBytes(out.total()), (System.currentTimeMillis() - startMs) / 1000.0);
    }

    // -----------------------------------------------------------------------
    // Progress-tracking stream wrappers
    // -----------------------------------------------------------------------

    /** Wraps an InputStream and logs every {@code intervalBytes} bytes read. */
    private static final class CountingInputStream extends FilterInputStream {
        private final String tag;
        private final long   interval;
        private long total;
        private long nextLog;

        CountingInputStream(InputStream in, String tag, long interval) {
            super(in);
            this.tag      = tag;
            this.interval = interval;
            this.nextLog  = interval;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                total += n;
                while (total >= nextLog) {
                    System.out.printf("[CompressionNode] %s %s…%n", tag, formatBytes(nextLog));
                    nextLog += interval;
                }
            }
            return n;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                total++;
                if (total >= nextLog) {
                    System.out.printf("[CompressionNode] %s %s…%n", tag, formatBytes(nextLog));
                    nextLog += interval;
                }
            }
            return b;
        }

        long total() { return total; }
    }

    /** Wraps an OutputStream and logs every {@code intervalBytes} bytes written. */
    private static final class CountingOutputStream extends FilterOutputStream {
        private final String tag;
        private final long   interval;
        private long total;
        private long nextLog;

        CountingOutputStream(OutputStream out, String tag, long interval) {
            super(out);
            this.tag      = tag;
            this.interval = interval;
            this.nextLog  = interval;
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            out.write(buf, off, len);
            total += len;
            while (total >= nextLog) {
                System.out.printf("[CompressionNode] %s %s…%n", tag, formatBytes(nextLog));
                nextLog += interval;
            }
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            total++;
            if (total >= nextLog) {
                System.out.printf("[CompressionNode] %s %s…%n", tag, formatBytes(nextLog));
                nextLog += interval;
            }
        }

        long total() { return total; }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int pos = json.indexOf(needle);
        if (pos < 0) return null;
        pos += needle.length();
        StringBuilder sb = new StringBuilder();
        while (pos < json.length()) {
            char c = json.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\' && pos < json.length()) c = json.charAt(pos++);
            sb.append(c);
        }
        return null;
    }

    private static String responseOk(String filename) {
        return "{\"status\":\"ok\",\"filename\":\"" + escapeJson(filename) + "\"}";
    }

    private static void writeError(OutputStream out, String message) throws IOException {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        out.write(("{\"status\":\"error\",\"message\":\"" + escaped + "\"}\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String formatBytes(long n) {
        if (n < 1024)              return n + " B";
        if (n < 1024 * 1024)       return String.format("%.1f KB", n / 1024.0);
        return String.format("%.2f MB", n / (1024.0 * 1024));
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("[CompressionNode] Starting...");
        new CompressionNode().run();
    }
}
