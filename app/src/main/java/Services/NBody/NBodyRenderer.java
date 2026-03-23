package Services.NBody;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NBodyRenderer — Console renderer for N-body simulation results.
 *
 * Parses the JSON response returned by the N-body service node and
 * produces two outputs:
 *   1. A formatted data table of final body states.
 *   2. An ASCII scatter plot of final positions projected onto the X-Y plane.
 *
 * No networking, no Node infrastructure — takes a raw JSON string and prints.
 * Intended to be called from NBodyClientTest after receiving the response.
 *
 * JSON parsing is done with regex (no external library), consistent with
 * the rest of the project.
 */
public class NBodyRenderer {

    // -----------------------------------------------------------------------
    // Grid dimensions for the ASCII plot
    // -----------------------------------------------------------------------

    private static final int GRID_WIDTH  = 64;
    private static final int GRID_HEIGHT = 24;

    // -----------------------------------------------------------------------
    // Regex patterns — top-level response fields
    // -----------------------------------------------------------------------

    private static final Pattern STATUS_PATTERN =
            Pattern.compile("\"status\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MESSAGE_PATTERN =
            Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STEPS_PATTERN =
            Pattern.compile("\"steps_completed\"\\s*:\\s*(\\d+)");
    private static final Pattern DT_PATTERN =
            Pattern.compile("\"dt\"\\s*:\\s*(-?[\\d.eE+\\-]+)");

    // -----------------------------------------------------------------------
    // Regex patterns — per-body numeric fields
    // Quoted field names prevent "x" from matching inside "vx", etc.
    // -----------------------------------------------------------------------

    private static final Pattern MASS_PATTERN = Pattern.compile("\"mass\"\\s*:\\s*(-?[\\d.eE+\\-]+)");
    private static final Pattern X_PATTERN    = Pattern.compile("\"x\"\\s*:\\s*(-?[\\d.eE+\\-]+)");
    private static final Pattern Y_PATTERN    = Pattern.compile("\"y\"\\s*:\\s*(-?[\\d.eE+\\-]+)");
    private static final Pattern Z_PATTERN    = Pattern.compile("\"z\"\\s*:\\s*(-?[\\d.eE+\\-]+)");
    private static final Pattern VX_PATTERN   = Pattern.compile("\"vx\"\\s*:\\s*(-?[\\d.eE+\\-]+)");
    private static final Pattern VY_PATTERN   = Pattern.compile("\"vy\"\\s*:\\s*(-?[\\d.eE+\\-]+)");
    private static final Pattern VZ_PATTERN   = Pattern.compile("\"vz\"\\s*:\\s*(-?[\\d.eE+\\-]+)");

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    /**
     * Parses the JSON response from the N-body service node and renders it.
     *
     * @param responseJson raw JSON string received from the server
     */
    public static void render(String responseJson) {
        String status = extract(STATUS_PATTERN, responseJson);

        if (!"ok".equals(status)) {
            String message = extract(MESSAGE_PATTERN, responseJson);
            System.out.println("[Renderer] Service returned an error: "
                    + (message != null ? message : responseJson));
            return;
        }

        // -- Parse metadata -----------------------------------------------
        String stepsStr = extract(STEPS_PATTERN, responseJson);
        String dtStr    = extract(DT_PATTERN,    responseJson);

        int    steps    = stepsStr != null ? Integer.parseInt(stepsStr) : -1;
        double dt       = dtStr    != null ? Double.parseDouble(dtStr)  : 0.0;

        // -- Parse bodies -------------------------------------------------
        List<Body> bodies = parseBodies(responseJson);

        // -- Render -------------------------------------------------------
        printHeader(steps, dt, bodies.size());
        printDataTable(bodies);
        drawAsciiPlot(bodies);
    }

    // -----------------------------------------------------------------------
    // Rendering helpers
    // -----------------------------------------------------------------------

    private static void printHeader(int steps, double dt, int bodyCount) {
        double totalTime = steps * dt;
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║         N-Body Simulation — Final State          ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.printf ("║  Bodies          : %-29d║%n", bodyCount);
        System.out.printf ("║  Steps completed : %-29d║%n", steps);
        System.out.printf ("║  Time step dt    : %-29s║%n", String.format("%.4e s", dt));
        System.out.printf ("║  Total sim time  : %-29s║%n", String.format("%.4e s  (%.2f days)", totalTime, totalTime / 86400.0));
        System.out.println("╚══════════════════════════════════════════════════╝");
    }

    private static void printDataTable(List<Body> bodies) {
        System.out.println();
        System.out.println("  Final Body States:");
        String header = String.format("  %-4s  %-12s  %-14s  %-14s  %-14s  %-13s  %-13s  %-13s",
                "#", "Mass (kg)", "X (m)", "Y (m)", "Z (m)", "Vx (m/s)", "Vy (m/s)", "Vz (m/s)");
        System.out.println("  " + "─".repeat(header.length() - 2));
        System.out.println(header);
        System.out.println("  " + "─".repeat(header.length() - 2));

        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            System.out.printf("  %-4d  %-12.4e  %-14.5e  %-14.5e  %-14.5e  %-13.5e  %-13.5e  %-13.5e%n",
                    i + 1, b.mass, b.x, b.y, b.z, b.vx, b.vy, b.vz);
        }
        System.out.println("  " + "─".repeat(header.length() - 2));
    }

    private static void drawAsciiPlot(List<Body> bodies) {
        if (bodies.isEmpty()) return;

        // -- Compute bounding box (X-Y plane) ------------------------------
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (Body b : bodies) {
            minX = Math.min(minX, b.x);
            maxX = Math.max(maxX, b.x);
            minY = Math.min(minY, b.y);
            maxY = Math.max(maxY, b.y);
        }

        // 10% padding on each side so bodies are never on the border.
        // +1e-10 prevents division by zero if all bodies share a coordinate.
        double padX = (maxX - minX) * 0.10 + 1e-10;
        double padY = (maxY - minY) * 0.10 + 1e-10;
        minX -= padX; maxX += padX;
        minY -= padY; maxY += padY;

        double rangeX = maxX - minX;
        double rangeY = maxY - minY;

        // -- Build character grid ------------------------------------------
        char[][] grid = new char[GRID_HEIGHT][GRID_WIDTH];
        for (char[] row : grid) Arrays.fill(row, ' ');

        // Draw origin axes if the origin is within the visible range.
        if (minX <= 0 && 0 <= maxX) {
            int col = clamp((int) ((0 - minX) / rangeX * (GRID_WIDTH - 1)), 0, GRID_WIDTH - 1);
            for (int r = 0; r < GRID_HEIGHT; r++) grid[r][col] = '│';
        }
        if (minY <= 0 && 0 <= maxY) {
            int row = clamp(GRID_HEIGHT - 1 - (int) ((0 - minY) / rangeY * (GRID_HEIGHT - 1)), 0, GRID_HEIGHT - 1);
            for (int c = 0; c < GRID_WIDTH; c++) grid[row][c] = '─';
            // Re-draw the intersection over the vertical axis if both are present.
            if (minX <= 0 && 0 <= maxX) {
                int col = clamp((int) ((0 - minX) / rangeX * (GRID_WIDTH - 1)), 0, GRID_WIDTH - 1);
                grid[row][col] = '┼';
            }
        }

        // Plot each body. Bodies are labeled 1–9, then A, B, C… for larger counts.
        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            int col = clamp((int) ((b.x - minX) / rangeX * (GRID_WIDTH  - 1)), 0, GRID_WIDTH  - 1);
            int row = clamp(GRID_HEIGHT - 1 - (int) ((b.y - minY) / rangeY * (GRID_HEIGHT - 1)), 0, GRID_HEIGHT - 1);
            grid[row][col] = (i < 9) ? (char) ('1' + i) : (char) ('A' + (i - 9));
        }

        // -- Print --------------------------------------------------------
        System.out.println();
        System.out.printf("  X-Y Plane Projection (final positions)%n");
        System.out.printf("  Y+ = %.3e m%n", maxY);
        System.out.println("  ┌" + "─".repeat(GRID_WIDTH) + "┐");
        for (char[] row : grid) {
            System.out.print("  │");
            for (char c : row) System.out.print(c);
            System.out.println("│");
        }
        System.out.println("  └" + "─".repeat(GRID_WIDTH) + "┘");
        System.out.printf("  X:  %.3e → %.3e (m)%n", minX, maxX);
        System.out.printf("  Y-  = %.3e m%n%n", minY);

        // -- Legend -------------------------------------------------------
        System.out.println("  Legend:");
        for (int i = 0; i < bodies.size(); i++) {
            char label = (i < 9) ? (char) ('1' + i) : (char) ('A' + (i - 9));
            System.out.printf("    [%c]  mass = %.4e kg%n", label, bodies.get(i).mass);
        }
        System.out.println();
    }

    // -----------------------------------------------------------------------
    // JSON parsing
    // -----------------------------------------------------------------------

    /**
     * Extracts the "bodies" array from the response JSON and parses each
     * body object into a Body instance.
     *
     * Brace-matching is used to find body object boundaries so that nested
     * or multi-character values (scientific notation, negatives) are handled
     * correctly without a full JSON parser.
     */
    private static List<Body> parseBodies(String json) {
        List<Body> bodies = new ArrayList<>();

        int arrStart = json.indexOf("\"bodies\"");
        if (arrStart < 0) return bodies;
        arrStart = json.indexOf('[', arrStart);
        if (arrStart < 0) return bodies;
        int arrEnd = json.lastIndexOf(']');
        if (arrEnd < arrStart) return bodies;

        String arr   = json.substring(arrStart + 1, arrEnd);
        int    depth = 0, start = -1;

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
            System.err.println("[Renderer] Could not parse body object: " + bodyJson);
        }
    }

    // -----------------------------------------------------------------------
    // Utility
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

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
