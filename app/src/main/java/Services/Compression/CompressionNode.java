package Services.Compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import Source.Node;
import Source.Service;

/**
 * CompressionNode — Microservice node for COMPRESSION_DECOMPRESSION (service #3).
 *
 * Uses Pattern A: overrides handleRequest() and processes everything
 * in-process using CompressionService (Java Deflater/Inflater). No subprocess needed.
 *
 * Expected inbound JSON (forwarded by Pipe):
 *   {
 *     "service"   : 3,
 *     "operation" : "compress" | "decompress",
 *     "filename"  : "example.txt",
 *     "data"      : "<base64-encoded file bytes>"
 *   }
 *
 * Response JSON written back to Pipe → client:
 *   { "status": "ok",    "result": "<base64 result bytes>", "filename": "<output name>" }
 *   { "status": "error", "message": "<details>" }
 *
 * Filename convention:
 *   compress   → appends ".zip"   (e.g. "notes.txt" → "notes.txt.zip")
 *   decompress → strips ".zip" if present (e.g. "notes.txt.zip" → "notes.txt")
 *
 * Directory: Services/Compression/CompressionNode.java
 */
public class CompressionNode extends Node {

    private static final int NODE_ID = 3;

    private static final Pattern OPERATION_PATTERN =
            Pattern.compile("\"operation\"\\s*:\\s*\"([^\"]+)\"");

    private static final Base64.Decoder DECODER = Base64.getDecoder();
    private static final Base64.Encoder ENCODER = Base64.getEncoder();

    private final CompressionService compressionService = new CompressionService();

    // -----------------------------------------------------------------------
    // Node identity
    // -----------------------------------------------------------------------

    @Override
    protected int getNodeId() { return NODE_ID; }

    @Override
    protected Service getService() { return Service.COMPRESSION_DECOMPRESSION; }

    // -----------------------------------------------------------------------
    // Pattern A — in-process request handler
    // -----------------------------------------------------------------------

    @Override
    protected byte[] handleRequest(byte[] payload) {
        String json = new String(payload, StandardCharsets.UTF_8);

        String operation = extract(OPERATION_PATTERN, json);
        String filename  = extractJsonString(json, "filename");
        String data      = extractJsonString(json, "data");

        if (operation == null || operation.isBlank()) {
            return error("Missing \"operation\" field. Expected \"compress\" or \"decompress\".");
        }
        if (data == null) {
            return error("Missing \"data\" field.");
        }
        if (filename == null || filename.isBlank()) {
            filename = "file";
        }

        byte[] inputBytes;
        try {
            inputBytes = DECODER.decode(data);
        } catch (IllegalArgumentException e) {
            return error("Invalid Base64 input in \"data\" field: " + e.getMessage());
        }

        // Release large Strings before compression allocates its output buffer
        data = null;
        json = null;

        return switch (operation.trim().toLowerCase()) {
            case "compress" -> {
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    compressionService.compressChunked(new ByteArrayInputStream(inputBytes), out, filename);
                    String outName = filename + ".zip";
                    yield success(ENCODER.encodeToString(out.toByteArray()), outName);
                } catch (IOException e) {
                    yield error("Compression failed: " + e.getMessage());
                }
            }
            case "decompress" -> {
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    compressionService.decompressChunked(new ByteArrayInputStream(inputBytes), out);
                    String outName = filename.endsWith(".zip")
                            ? filename.substring(0, filename.length() - 4)
                            : filename + ".decompressed";
                    yield success(ENCODER.encodeToString(out.toByteArray()), outName);
                } catch (IOException e) {
                    yield error("Decompression failed: " + e.getMessage());
                }
            }
            default -> error("Unknown operation \"" + operation
                    + "\". Expected \"compress\" or \"decompress\".");
        };
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String extract(Pattern p, String json) {
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

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

    private static byte[] success(String resultBase64, String filename) {
        String escResult   = resultBase64.replace("\\", "\\\\").replace("\"", "\\\"");
        String escFilename = filename.replace("\\", "\\\\").replace("\"", "\\\"");
        return ("{\"status\":\"ok\",\"result\":\"" + escResult
                + "\",\"filename\":\"" + escFilename + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] error(String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return ("{\"status\":\"error\",\"message\":\"" + escaped + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Pattern A-streaming — override to avoid buffering the full payload
    // -----------------------------------------------------------------------

    @Override
    protected void handleRequestStreaming(InputStream rawIn, OutputStream rawOut) throws Exception {
        // Read header bytes until we've consumed the opening quote of "data":"..."
        StringBuilder header = new StringBuilder();
        String marker = "\"data\":\"";
        while (true) {
            int b = rawIn.read();
            if (b == -1) throw new IOException("Unexpected EOF before \"data\" field");
            header.append((char) b);
            int len = header.length();
            if (len >= marker.length() &&
                    header.substring(len - marker.length()).equals(marker)) break;
            if (len > 8192) throw new IOException("JSON header exceeds 8 KB");
        }

        String headerStr = header.toString();
        String operation = extractJsonString(headerStr, "operation");
        String filename  = extractJsonString(headerStr, "filename");

        if (operation == null || operation.isBlank()) {
            writeError(rawOut, "Missing \"operation\" field. Expected \"compress\" or \"decompress\".");
            return;
        }
        if (filename == null || filename.isBlank()) filename = "file";

        // rawIn is now at the first base64 character; stop reading at the closing '"'
        InputStream decoded = Base64.getDecoder().wrap(new Base64EndingInputStream(rawIn));

        switch (operation.trim().toLowerCase()) {
            case "compress"   -> streamCompress(decoded, rawOut, filename);
            case "decompress" -> streamDecompress(decoded, rawOut, filename);
            default -> writeError(rawOut, "Unknown operation \"" + operation + "\".");
        }
    }

    private static void streamCompress(InputStream decoded, OutputStream rawOut, String filename)
            throws IOException {
        rawOut.write("{\"status\":\"ok\",\"result\":\"".getBytes(StandardCharsets.UTF_8));
        OutputStream b64Out = Base64.getEncoder().wrap(new NonClosingOutputStream(rawOut));
        try (ZipOutputStream zip = new ZipOutputStream(b64Out)) {
            zip.putNextEntry(new ZipEntry(filename));
            decoded.transferTo(zip);
            zip.closeEntry();
        } // zip.close() → def.end() (releases native Deflater memory) → b64Out.close() (writes padding)
        // b64Out already closed by zip; NonClosingOutputStream kept rawOut open
        rawOut.write(("\",\"filename\":\"" + escapeJson(filename + ".zip") + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void streamDecompress(InputStream decoded, OutputStream rawOut, String filename)
            throws IOException {
        String outName = filename.endsWith(".zip")
                ? filename.substring(0, filename.length() - 4)
                : filename + ".decompressed";
        rawOut.write("{\"status\":\"ok\",\"result\":\"".getBytes(StandardCharsets.UTF_8));
        OutputStream b64Out = Base64.getEncoder().wrap(new NonClosingOutputStream(rawOut));
        ZipInputStream zip = new ZipInputStream(decoded);
        while (zip.getNextEntry() != null) {
            zip.transferTo(b64Out);
            zip.closeEntry();
        }
        b64Out.close(); // flushes base64 padding; NonClosingOutputStream keeps rawOut open
        rawOut.write(("\",\"filename\":\"" + escapeJson(outName) + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void writeError(OutputStream out, String message) throws IOException {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        out.write(("{\"status\":\"error\",\"message\":\"" + escaped + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Reads bytes from source until {@code '"'} (the JSON string terminator) or EOF. */
    private static final class Base64EndingInputStream extends InputStream {
        private final InputStream source;
        private boolean done;

        Base64EndingInputStream(InputStream source) { this.source = source; }

        @Override
        public int read() throws IOException {
            if (done) return -1;
            int b = source.read();
            if (b == -1 || b == '"') { done = true; return -1; }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (done) return -1;
            int count = 0;
            while (count < len) {
                int b = read();
                if (b == -1) break;
                buf[off + count++] = (byte) b;
            }
            return count == 0 ? -1 : count;
        }
    }

    /** Delegates writes and flush but does NOT forward close() to the underlying stream. */
    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream out) { super(out); }

        @Override
        public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }

        @Override
        public void close() throws IOException { flush(); }
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("[CompressionNode] Starting...");
        new CompressionNode().run();
    }
}
