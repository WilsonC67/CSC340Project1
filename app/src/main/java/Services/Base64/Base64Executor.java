package Services.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base64Executor — Subprocess invoked by Base64Node for each inbound request.
 *
 * Contract (enforced by Node.handleRequest):
 *   STDIN  — full JSON payload forwarded by the server Pipe.
 *   STDOUT — JSON result that Node streams back to Pipe → client.
 *   Exit 0 — success.
 *   Exit 1 — handled error (response JSON contains error details).
 *
 * Inbound JSON fields read:
 *   "operation" : "encode" | "decode"   (required)
 *   "data"      : <string>              (required — the value to process)
 *
 * All other fields ("service", "filename", …) are accepted but ignored.
 *
 * This class intentionally uses only the standard library (regex field
 * extraction) to avoid any dependency on a JSON library, consistent with
 * the rest of the project (see Pipe.java which does the same for "service").
 */
public class Base64Executor {

    // Simple field extractors — mirrors the pattern used in Pipe.java.
    private static final Pattern OPERATION_PATTERN =
            Pattern.compile("\"operation\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DATA_PATTERN =
            Pattern.compile("\"data\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    public static void main(String[] args) {
        try {
            // ----------------------------------------------------------------
            // 1. Read the full JSON payload from STDIN.
            //    Node closes STDIN after writing, so readAllBytes() terminates.
            // ----------------------------------------------------------------
            byte[] raw  = System.in.readAllBytes();
            String json = new String(raw, StandardCharsets.UTF_8);

            // ----------------------------------------------------------------
            // 2. Extract "operation" and "data" fields.
            // ----------------------------------------------------------------
            String operation = extract(OPERATION_PATTERN, json);
            String data      = extract(DATA_PATTERN,      json);

            if (operation == null || operation.isBlank()) {
                writeError("Missing or empty \"operation\" field. Expected \"encode\" or \"decode\".");
                System.exit(1);
            }
            if (data == null) {
                writeError("Missing \"data\" field.");
                System.exit(1);
            }

            // ----------------------------------------------------------------
            // 3. Perform encode or decode using Base64Service logic.
            // ----------------------------------------------------------------
            String result;
            switch (operation.trim().toLowerCase()) {
                case "encode" -> {
                    // Treat "data" as a plain UTF-8 string and encode it.
                    result = ENCODER.encodeToString(data.getBytes(StandardCharsets.UTF_8));
                }
                case "decode" -> {
                    // Treat "data" as a Base64 string and decode it back to UTF-8.
                    try {
                        byte[] decoded = DECODER.decode(data);
                        result = new String(decoded, StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        writeError("Invalid Base64 input: " + e.getMessage());
                        System.exit(1);
                        return; // unreachable, satisfies compiler
                    }
                }
                default -> {
                    writeError("Unknown operation \"" + operation
                            + "\". Expected \"encode\" or \"decode\".");
                    System.exit(1);
                    return;
                }
            }

            // ----------------------------------------------------------------
            // 4. Write the success JSON to STDOUT.
            //    Node.handleRequest() reads this and forwards it to Pipe.
            // ----------------------------------------------------------------
            writeSuccess(result);
            System.exit(0);

        } catch (IOException e) {
            writeError("IO error reading STDIN: " + e.getMessage());
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String extract(Pattern p, String json) {
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Writes a success JSON response to STDOUT. */
    private static void writeSuccess(String result) {
        // Escape any double-quotes or backslashes in the result string.
        String escaped = result.replace("\\", "\\\\").replace("\"", "\\\"");
        System.out.print("{\"status\":\"ok\",\"result\":\"" + escaped + "\"}");
        System.out.flush();
    }

    /** Writes an error JSON response to STDOUT (not stderr — Node reads STDOUT). */
    private static void writeError(String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        System.out.print("{\"status\":\"error\",\"message\":\"" + escaped + "\"}");
        System.out.flush();
    }
}