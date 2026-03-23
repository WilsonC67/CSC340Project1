package Services.NBody;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NBodyExecutor — Subprocess entry point for the N-body gravitational stepper.
 *
 * Contract (enforced by Node.handleRequest when used via Pattern B):
 *   STDIN  — full JSON payload forwarded by the server Pipe.
 *   STDOUT — JSON result that Node streams back to Pipe → client.
 *   Exit 0 — success.
 *   Exit 1 — handled error (response JSON still contains error details).
 *
 * The core logic lives in process(String json), which returns a JSON result
 * string and never throws. This allows it to be called directly by tests
 * (NBodyStandaloneTest) without subprocess overhead or System.exit() issues.
 *
 * Inbound JSON fields:
 *   "dt"     : double  (time step in seconds, must be > 0)
 *   "steps"  : int     (number of integration steps, must be > 0)
 *   "bodies" : array   (at least 2 body objects with mass, x, y, z, vx, vy, vz)
 *
 * Outbound JSON (success):
 *   { "status": "ok", "steps_completed": <int>, "dt": <double>,
 *     "bodies": [ { "mass":…, "x":…, "y":…, "z":…, "vx":…, "vy":…, "vz":… }, … ] }
 *
 * Outbound JSON (error):
 *   { "status": "error", "message": "<details>" }
 */
public class NBodyExecutor {

    // -----------------------------------------------------------------------
    // Regex patterns — request fields
    // -----------------------------------------------------------------------

    private static final Pattern DT_PATTERN    = Pattern.compile("\"dt\"\\s*:\\s*(-?[\\d.eE+\\-]+)");
    private static final Pattern STEPS_PATTERN = Pattern.compile("\"steps\"\\s*:\\s*(\\d+)");
    private static final Pattern MASS_PATTERN  = Pattern.compile("\"mass\"\\s*:\\s*(-?[\\d.eE+\\-]+)");
    private static final Pattern X_PATTERN     = Pattern.compile("\"x\"\\s*:\\s*(-?[\\d.eE+\\-]+)");
    private static final Pattern Y_PATTERN     = Pattern.compile("\"y\"\\s*:\\s*(-?[\\d.eE+\\-]+)");
    private static final Pattern Z_PATTERN     = Pattern.compile("\"z\"\\s*:\\s*(-?[\\d.eE+\\-]+)");
    private static final Pattern VX_PATTERN    = Pattern.compile("\"vx\"\\s*:\\s*(-?[\\d.eE+\\-]+)");
    private static final Pattern VY_PATTERN    = Pattern.compile("\"vy\"\\s*:\\s*(-?[\\d.eE+\\-]+)");
    private static final Pattern VZ_PATTERN    = Pattern.compile("\"vz\"\\s*:\\s*(-?[\\d.eE+\\-]+)");

    // -----------------------------------------------------------------------
    // Subprocess entry point
    // -----------------------------------------------------------------------

    /**
     * Reads the full JSON payload from STDIN, processes it, writes the result
     * to STDOUT, and exits.
     *
     * Not intended to be called directly from tests — use process() instead.
     */
    public static void main(String[] args) {
        try {
            byte[] raw  = System.in.readAllBytes();
            String json = new String(raw, StandardCharsets.UTF_8);

            String result = process(json);
            System.out.print(result);
            System.out.flush();
            System.exit(result.contains("\"status\":\"ok\"") ? 0 : 1);

        } catch (IOException e) {
            System.out.print(error("IO error reading STDIN: " + e.getMessage()));
            System.out.flush();
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------
    // Core logic — call this directly from tests
    // -----------------------------------------------------------------------

    /**
     * Parses the request JSON, runs the N-body simulation, and returns the
     * response JSON as a String. Never throws — all errors are returned as
     * error-status JSON.
     *
     * @param json the full request JSON string (as received from Pipe)
     * @return     response JSON string ready to write to STDOUT / return to Node
     */
    public static String process(String json) {
        if (json == null || json.isBlank()) {
            return error("Empty payload.");
        }

        // -- Parse dt ------------------------------------------------------
        String dtStr = extract(DT_PATTERN, json);
        if (dtStr == null) return error("Missing \"dt\" field.");
        double dt;
        try { dt = Double.parseDouble(dtStr); }
        catch (NumberFormatException e) { return error("Invalid \"dt\" value: " + dtStr); }
        if (dt <= 0) return error("dt must be positive.");

        // -- Parse steps ---------------------------------------------------
        String stepsStr = extract(STEPS_PATTERN, json);
        if (stepsStr == null) return error("Missing \"steps\" field.");
        int steps;
        try { steps = Integer.parseInt(stepsStr); }
        catch (NumberFormatException e) { return error("Invalid \"steps\" value: " + stepsStr); }
        if (steps <= 0) return error("steps must be positive.");

        // -- Parse bodies --------------------------------------------------
        List<Body> bodies = parseBodies(json);
        if (bodies == null) return error("Missing \"bodies\" array.");
        if (bodies.size() < 2) return error("Need at least 2 bodies.");

        // -- Run simulation — collect every frame for playback ----------------
        List<List<Body>> frames;
        try {
            frames = NBodyStepper.stepAll(bodies, dt, steps);
        } catch (Exception e) {
            return error("Simulation error: " + e.getMessage());
        }

        // -- Serialize result ----------------------------------------------
        return serialize(frames, steps, dt);
    }

    // -----------------------------------------------------------------------
    // JSON serialization
    // -----------------------------------------------------------------------

    /**
     * Serializes the full simulation result.
     *
     * Response fields:
     *   "status"          : "ok"
     *   "steps_completed" : int
     *   "dt"              : double
     *   "bodies"          : final body states  (kept for test backward-compatibility)
     *   "frames"          : array of (steps+1) snapshots — index 0 = initial state,
     *                       index N = state after step N  (used by the renderer)
     */
    private static String serialize(List<List<Body>> frames, int stepsCompleted, double dt) {
        List<Body> finalBodies = frames.get(frames.size() - 1);

        StringBuilder sb = new StringBuilder();
        sb.append("{ ");
        sb.append("\"status\": \"ok\", ");
        sb.append("\"steps_completed\": ").append(stepsCompleted).append(", ");
        sb.append("\"dt\": ").append(dt).append(", ");

        // Final state — keeps existing tests and NBodyRenderer.html working
        sb.append("\"bodies\": [");
        appendBodyList(sb, finalBodies);
        sb.append("], ");

        // All frames for animation playback
        sb.append("\"frames\": [");
        for (int f = 0; f < frames.size(); f++) {
            sb.append("[");
            appendBodyList(sb, frames.get(f));
            sb.append("]");
            if (f < frames.size() - 1) sb.append(", ");
        }
        sb.append("]");

        sb.append(" }");
        return sb.toString();
    }

    private static void appendBodyList(StringBuilder sb, List<Body> bodies) {
        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            sb.append("{ ");
            sb.append("\"mass\": ").append(b.mass).append(", ");
            sb.append("\"x\": ").append(b.x).append(", ");
            sb.append("\"y\": ").append(b.y).append(", ");
            sb.append("\"z\": ").append(b.z).append(", ");
            sb.append("\"vx\": ").append(b.vx).append(", ");
            sb.append("\"vy\": ").append(b.vy).append(", ");
            sb.append("\"vz\": ").append(b.vz);
            sb.append(" }");
            if (i < bodies.size() - 1) sb.append(", ");
        }
    }

    private static String error(String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{ \"status\": \"error\", \"message\": \"" + escaped + "\" }";
    }

    // -----------------------------------------------------------------------
    // JSON parsing
    // -----------------------------------------------------------------------

    /**
     * Extracts all body objects from the "bodies" array in the JSON string.
     * Returns null if the array is not found; returns an empty list if the
     * array is present but contains no valid body objects.
     */
    private static List<Body> parseBodies(String json) {
        int arrStart = json.indexOf("\"bodies\"");
        if (arrStart < 0) return null;
        arrStart = json.indexOf('[', arrStart);
        if (arrStart < 0) return null;
        int arrEnd = json.lastIndexOf(']');
        if (arrEnd < arrStart) return null;

        String       arr     = json.substring(arrStart + 1, arrEnd);
        List<Body>   bodies  = new ArrayList<>();
        int          depth   = 0;
        int          start   = -1;

        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (c == '{') {
                if (depth++ == 0) start = i;
            } else if (c == '}') {
                if (--depth == 0 && start >= 0) {
                    parseBody(arr.substring(start, i + 1), bodies);
                    start = -1;
                }
            }
        }

        return bodies;
    }

    private static void parseBody(String bodyJson, List<Body> out) {
        try {
            double mass = parseDouble(MASS_PATTERN, bodyJson);
            double x    = parseDouble(X_PATTERN,    bodyJson);
            double y    = parseDouble(Y_PATTERN,    bodyJson);
            double z    = parseDouble(Z_PATTERN,    bodyJson);
            double vx   = parseDouble(VX_PATTERN,   bodyJson);
            double vy   = parseDouble(VY_PATTERN,   bodyJson);
            double vz   = parseDouble(VZ_PATTERN,   bodyJson);
            out.add(new Body(mass, x, y, z, vx, vy, vz));
        } catch (Exception e) {
            System.err.println("[NBodyExecutor] Skipping malformed body: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static String extract(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static double parseDouble(Pattern p, String s) {
        String val = extract(p, s);
        if (val == null) throw new IllegalArgumentException("Field not found: " + p.pattern());
        return Double.parseDouble(val);
    }
}
