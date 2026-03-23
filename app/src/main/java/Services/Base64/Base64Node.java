package Services.Base64;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import Source.Node;
import Source.Service;


/**
 * Base64Node — Microservice node for BASE64_ENCODE_DECODE (service #2).
 *
 * Uses Pattern A: overrides handleRequest() and processes everything
 * in-process using the standard Java Base64 library. No subprocess needed.
 *
 * Expected inbound JSON (forwarded by Pipe):
 *   {
 *     "service"   : 2,
 *     "operation" : "encode" | "decode",
 *     "filename"  : "example.txt",
 *     "data"      : "<string to encode>  OR  <base64 string to decode>"
 *   }
 *
 * Response JSON written back to Pipe → client:
 *   { "status": "ok",    "result": "<processed string>" }
 *   { "status": "error", "message": "<details>" }
 *
 * Directory: Services/Base64/Base64Node.java
 */
public class Base64Node extends Node {

    private static final int NODE_ID = 2;

    private static final Pattern OPERATION_PATTERN =
            Pattern.compile("\"operation\"\\s*:\\s*\"([^\"]+)\"");

    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    // -----------------------------------------------------------------------
    // Node identity
    // -----------------------------------------------------------------------

    @Override
    protected int getNodeId() { return NODE_ID; }

    @Override
    protected Service getService() { return Service.BASE64_ENCODE_DECODE; }

    // -----------------------------------------------------------------------
    // Pattern A — in-process request handler
    // -----------------------------------------------------------------------

    /**
     * Parses the JSON payload, performs encode or decode, returns JSON result.
     * Runs entirely in Java — no subprocess, no classpath issues.
     */
    @Override
    protected byte[] handleRequest(byte[] payload) {
        String json = new String(payload, StandardCharsets.UTF_8);

        String operation = extract(OPERATION_PATTERN, json);
        String data      = extractJsonString(json, "data");

        if (operation == null || operation.isBlank()) {
            return error("Missing \"operation\" field. Expected \"encode\" or \"decode\".");
        }
        if (data == null) {
            return error("Missing \"data\" field.");
        }

        // Unescape JSON-escaped characters in the extracted data value
        // (e.g. \" → ", \\ → \) so we operate on the real string.
        data = data.replace("\\\"", "\"").replace("\\\\", "\\");

        return switch (operation.trim().toLowerCase()) {
            case "encode" -> {
                // data arrives as base64(file_bytes) from the browser's readAsDataURL.
                // The correct encode result IS that base64 string — return it as-is.
                yield success(data);
            }
            case "decode" -> {
                try {
                    // data = base64(b64_file_contents) — the browser wraps the .b64 file in base64.
                    // Step 1: unwrap browser encoding → get the .b64 file content (a base64 string).
                    byte[] b64FileContent = DECODER.decode(data);
                    String b64String = new String(b64FileContent, StandardCharsets.UTF_8).trim();
                    // Step 2: decode the actual base64 → original bytes.
                    byte[] originalBytes = DECODER.decode(b64String);
                    // Step 3: re-encode as base64 so the result travels safely in JSON.
                    //         The frontend will base64-decode it to reconstruct the original file.
                    yield success(ENCODER.encodeToString(originalBytes));
                } catch (IllegalArgumentException e) {
                    yield error("Invalid Base64 input: " + e.getMessage());
                }
            }
            default -> error("Unknown operation \"" + operation
                    + "\". Expected \"encode\" or \"decode\".");
        };
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String extract(Pattern p, String json) {
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Scans for {@code "key":"value"} and returns value. Handles large payloads safely. */
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

    private static byte[] success(String result) {
        String escaped = result.replace("\\", "\\\\").replace("\"", "\\\"");
        return ("{\"status\":\"ok\",\"result\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] error(String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return ("{\"status\":\"error\",\"message\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("[Base64Node] Starting...");
        new Base64Node().run();
    }
}