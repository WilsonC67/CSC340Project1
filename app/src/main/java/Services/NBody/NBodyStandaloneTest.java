package Services.NBody;

/**
 * NBodyStandaloneTest — End-to-end test of the N-body pipeline without networking.
 *
 * Tests the full chain:
 *   JSON payload → NBodyExecutor.process() → NBodyStepper.step() → JSON result → NBodyRenderer
 *
 * HOW TO RUN:
 *   No server, no node, no heartbeat needed.
 *   Compile all classes in Services/NBody/ then run:
 *     java Services.NBody.NBodyStandaloneTest
 *
 * Test suite:
 *   Test 1 — Sun + Earth (2 bodies, 365 steps × 86400 s ≈ 1 year)
 *             Physics check: Earth moved, Sun barely moved.
 *   Test 2 — Sun + Earth + Jupiter (3 bodies, 100 steps × 86400 s)
 *             Checks three-body simulation runs without error.
 *   Test 3 — Only 1 body (error: need at least 2)
 *   Test 4 — dt = 0  (error: dt must be positive)
 *   Test 5 — Empty JSON (error: empty payload)
 */
public class NBodyStandaloneTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== N-Body Standalone Pipeline Test ===");
        System.out.println("Chain: JSON → NBodyExecutor.process() → NBodyStepper → JSON → NBodyRenderer\n");

        testSunEarth();
        testThreeBody();
        testOneBody();
        testZeroDt();
        testEmptyPayload();

        System.out.println("\n═══════════════════════════════════════");
        System.out.printf ("  Results: %d passed, %d failed%n", passed, failed);
        System.out.println("═══════════════════════════════════════");
    }

    // -----------------------------------------------------------------------
    // Test 1 — Sun + Earth, 1 simulated year
    // -----------------------------------------------------------------------

    private static void testSunEarth() {
        printSeparator("Test 1: Sun + Earth  |  365 steps × 86400 s (≈ 1 year)");

        // Initial conditions (SI units)
        Body sun   = new Body(1.989e30,  0.0,      0.0, 0.0,  0.0,  0.0,   0.0);
        Body earth = new Body(5.972e24,  1.496e11, 0.0, 0.0,  0.0,  29780, 0.0);

        String payload = buildPayload(86400.0, 365, sun, earth);
        String result  = NBodyExecutor.process(payload);

        System.out.println("[Test 1] Executor returned: "
                + result.substring(0, Math.min(80, result.length())) + "…");

        // Must succeed
        if (!assertContains(result, "\"status\": \"ok\"", "Test 1 — status ok")) return;

        // Physics sanity: after one full orbit Earth is back near its starting position
        // on the X-axis, so checking Y alone is phase-dependent and unreliable.
        // Instead verify the Earth-Sun distance is still roughly 1 AU (1.496e11 m).
        // We accept anything between 0.5 AU and 2.0 AU — Euler drift is expected
        // to be modest over a single year at dt = 1 day.
        double[] sunPos   = extractBodyXY(result, 0);
        double[] earthPos = extractBodyXY(result, 1);
        double dist = Math.sqrt(Math.pow(earthPos[0] - sunPos[0], 2)
                              + Math.pow(earthPos[1] - sunPos[1], 2));
        double AU = 1.496e11;
        assertTrue(dist > 0.5 * AU && dist < 2.0 * AU,
                String.format("Test 1 — Earth-Sun distance ≈ 1 AU: %.4e m (expected 0.5–2.0 AU)", dist));

        // Sun should have barely moved (recoil due to Earth's gravity is tiny).
        assertTrue(Math.abs(sunPos[0]) < 1e9 && Math.abs(sunPos[1]) < 1e9,
                String.format("Test 1 — Sun near origin: x=%.3e m, y=%.3e m", sunPos[0], sunPos[1]));

        // Render for visual inspection
        NBodyRenderer.render(result);
    }

    // -----------------------------------------------------------------------
    // Test 2 — Sun + Earth + Jupiter, 100 days
    // -----------------------------------------------------------------------

    private static void testThreeBody() {
        printSeparator("Test 2: Sun + Earth + Jupiter  |  100 steps × 86400 s");

        Body sun     = new Body(1.989e30,  0.0,      0.0, 0.0,  0.0,  0.0,   0.0);
        Body earth   = new Body(5.972e24,  1.496e11, 0.0, 0.0,  0.0,  29780, 0.0);
        Body jupiter = new Body(1.898e27,  7.783e11, 0.0, 0.0,  0.0,  13070, 0.0);

        String payload = buildPayload(86400.0, 100, sun, earth, jupiter);
        String result  = NBodyExecutor.process(payload);

        assertContains(result, "\"status\": \"ok\"", "Test 2 — status ok");
        assertContains(result, "\"steps_completed\": 100", "Test 2 — steps_completed = 100");

        NBodyRenderer.render(result);
    }

    // -----------------------------------------------------------------------
    // Test 3 — Only 1 body (error case)
    // -----------------------------------------------------------------------

    private static void testOneBody() {
        printSeparator("Test 3: Only 1 body (should return error)");

        Body lone = new Body(1.989e30, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        String payload = buildPayload(86400.0, 10, lone);
        String result  = NBodyExecutor.process(payload);

        System.out.println("[Test 3] Executor returned: " + result);
        assertContains(result, "\"status\": \"error\"", "Test 3 — status error");
        assertContains(result, "2 bodies",             "Test 3 — error mentions '2 bodies'");
    }

    // -----------------------------------------------------------------------
    // Test 4 — dt = 0 (error case)
    // -----------------------------------------------------------------------

    private static void testZeroDt() {
        printSeparator("Test 4: dt = 0  (should return error)");

        Body a = new Body(1.0e30, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        Body b = new Body(1.0e30, 1.0e11, 0.0, 0.0, 0.0, 0.0, 0.0);
        String payload = buildPayload(0.0, 10, a, b);
        String result  = NBodyExecutor.process(payload);

        System.out.println("[Test 4] Executor returned: " + result);
        assertContains(result, "\"status\": \"error\"", "Test 4 — status error");
        assertContains(result, "positive",             "Test 4 — error mentions 'positive'");
    }

    // -----------------------------------------------------------------------
    // Test 5 — Empty JSON (error case)
    // -----------------------------------------------------------------------

    private static void testEmptyPayload() {
        printSeparator("Test 5: Empty payload (should return error)");

        String result = NBodyExecutor.process("");

        System.out.println("[Test 5] Executor returned: " + result);
        assertContains(result, "\"status\": \"error\"", "Test 5 — status error");
    }

    // -----------------------------------------------------------------------
    // JSON builder
    // -----------------------------------------------------------------------

    private static String buildPayload(double dt, int steps, Body... bodies) {
        StringBuilder sb = new StringBuilder();
        sb.append("{ \"service\": 1");
        sb.append(", \"dt\": ").append(dt);
        sb.append(", \"steps\": ").append(steps);
        sb.append(", \"bodies\": [");

        for (int i = 0; i < bodies.length; i++) {
            Body b = bodies[i];
            sb.append("{ ");
            sb.append("\"mass\": ").append(b.mass).append(", ");
            sb.append("\"x\": ").append(b.x).append(", ");
            sb.append("\"y\": ").append(b.y).append(", ");
            sb.append("\"z\": ").append(b.z).append(", ");
            sb.append("\"vx\": ").append(b.vx).append(", ");
            sb.append("\"vy\": ").append(b.vy).append(", ");
            sb.append("\"vz\": ").append(b.vz);
            sb.append(" }");
            if (i < bodies.length - 1) sb.append(", ");
        }

        sb.append("] }");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Physics sanity helpers
    // -----------------------------------------------------------------------

    /**
     * Extracts the X and Y positions of the body at the given 0-based index
     * from the result JSON. Locates the Nth body object by brace-counting,
     * then pulls "x" and "y" fields with indexOf.
     * Returns {0.0, 0.0} if parsing fails.
     */
    private static double[] extractBodyXY(String json, int bodyIndex) {
        // Find the "bodies" array
        int arrStart = json.indexOf("\"bodies\"");
        if (arrStart < 0) return new double[]{0.0, 0.0};
        arrStart = json.indexOf('[', arrStart);
        if (arrStart < 0) return new double[]{0.0, 0.0};
        int arrEnd = json.lastIndexOf(']');
        if (arrEnd < arrStart) return new double[]{0.0, 0.0};

        String arr   = json.substring(arrStart + 1, arrEnd);
        int    depth = 0, start = -1, count = 0;

        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (c == '{') {
                if (depth++ == 0) start = i;
            } else if (c == '}') {
                if (--depth == 0 && start >= 0) {
                    if (count == bodyIndex) {
                        String body = arr.substring(start, i + 1);
                        return new double[]{ extractField(body, "\"x\""), extractField(body, "\"y\"") };
                    }
                    count++;
                    start = -1;
                }
            }
        }
        return new double[]{0.0, 0.0};
    }

    /** Extracts a numeric field value from a body JSON object string. */
    private static double extractField(String bodyJson, String fieldKey) {
        int idx = bodyJson.indexOf(fieldKey);
        if (idx < 0) return 0.0;
        int colon = bodyJson.indexOf(':', idx);
        if (colon < 0) return 0.0;
        int vs = colon + 1;
        int ve = vs;
        while (ve < bodyJson.length()) {
            char c = bodyJson.charAt(ve);
            if (c == ',' || c == '}') break;
            ve++;
        }
        try { return Double.parseDouble(bodyJson.substring(vs, ve).trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    // -----------------------------------------------------------------------
    // Assertion helpers
    // -----------------------------------------------------------------------

    private static boolean assertContains(String actual, String expected, String label) {
        if (actual.contains(expected)) {
            System.out.printf("  [PASS] %s%n", label);
            passed++;
            return true;
        } else {
            System.out.printf("  [FAIL] %s%n", label);
            System.out.printf("         Expected to contain: \"%s\"%n", expected);
            System.out.printf("         Actual: %s%n", actual.substring(0, Math.min(120, actual.length())));
            failed++;
            return false;
        }
    }

    private static void assertTrue(boolean condition, String label) {
        if (condition) {
            System.out.printf("  [PASS] %s%n", label);
            passed++;
        } else {
            System.out.printf("  [FAIL] %s%n", label);
            failed++;
        }
    }

    private static void printSeparator(String title) {
        System.out.println("\n────────────────────────────────────────────────────");
        System.out.println("  " + title);
        System.out.println("────────────────────────────────────────────────────");
    }
}
