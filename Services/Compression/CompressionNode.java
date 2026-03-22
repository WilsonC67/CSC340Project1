package Services.Compression;

import Source.Node;
import Source.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                    compressionService.compress(new ByteArrayInputStream(inputBytes), out, filename);
                    String outName = filename + ".zip";
                    yield success(ENCODER.encodeToString(out.toByteArray()), outName);
                } catch (IOException e) {
                    yield error("Compression failed: " + e.getMessage());
                }
            }
            case "decompress" -> {
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    compressionService.decompress(new ByteArrayInputStream(inputBytes), out);
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
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("[CompressionNode] Starting...");
        new CompressionNode().run();
    }
}
