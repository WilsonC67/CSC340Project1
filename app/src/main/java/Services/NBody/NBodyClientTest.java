package Services.NBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * NBodyClientTest — Full lifecycle network test for the N-body gravitational stepper.
 *
 * Exercises the complete request pipeline end-to-end over raw TCP:
 *
 *   [Step 1]  Client connects to Server TCP 5101 (DoormanListener).
 *             Server spawns a Pipe for this connection.
 *
 *   [Step 2]  Client sends the N-body JSON request payload (service=1 + params).
 *
 *   [Step 3]  Client calls shutdownOutput() — signals EOF to the Pipe.
 *             Pipe's readAllBytes() returns; routing begins.
 *
 *   [Step 4]  Server Pipe sends "AVAILABLE_SERVICES:…" back to client.
 *             Client reads and displays it.
 *
 *   [Step 5]  Pipe finds a live NBodyNode in the NodeRegistry, opens TCP
 *             to it (port 5102), forwards the JSON, streams the result back.
 *
 *   [Step 6]  Client receives the result JSON and prints it.
 *
 * HOW TO RUN (in order):
 *   1. java Source.Server
 *   2. java Services.NBody.NBodyNode
 *   3. java Services.NBody.NBodyClientTest
 *
 * For the browser-based GUI and 3D renderer, open:
 *   Frontend/src/Gravitational.html  (requires a live-server or python3 -m http.server)
 */
public class NBodyClientTest {

    private static final String SERVER_IP   = "127.0.0.1";
    private static final int    SERVER_PORT = 5101;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        banner("N-Body Gravitational Stepper — Full Lifecycle Client Test");

        System.out.println("[Init] Waiting 6 s for NBodyNode to register via heartbeat…\n");
        Thread.sleep(6_000);
        System.out.println("[Init] Done. Starting scenarios.\n");

        runScenario(
            "A — Sun + Earth  (365 steps × 86400 s ≈ 1 year)",
            buildPayload(86400.0, 365,
                new Body(1.989e30,  0.0,      0.0, 0.0, 0.0,  0.0,   0.0),   // Sun
                new Body(5.972e24,  1.496e11, 0.0, 0.0, 0.0,  29780, 0.0))   // Earth
        );

        runScenario(
            "B — Sun + Earth + Jupiter  (100 steps × 86400 s)",
            buildPayload(86400.0, 100,
                new Body(1.989e30,  0.0,      0.0, 0.0, 0.0,  0.0,   0.0),   // Sun
                new Body(5.972e24,  1.496e11, 0.0, 0.0, 0.0,  29780, 0.0),   // Earth
                new Body(1.898e27,  7.783e11, 0.0, 0.0, 0.0,  13070, 0.0))   // Jupiter
        );

        System.out.println("══════════════════════════════════════════════");
        System.out.println("  All scenarios complete.");
        System.out.println("  Open Frontend/src/Gravitational.html for");
        System.out.println("  the interactive GUI and 3D renderer.");
        System.out.println("══════════════════════════════════════════════");
    }

    // -----------------------------------------------------------------------
    // Full lifecycle for one scenario
    // -----------------------------------------------------------------------

    private static void runScenario(String title, String payload) {
        System.out.println("┌─────────────────────────────────────────────────────────┐");
        System.out.println("│  Scenario " + title);
        System.out.println("└─────────────────────────────────────────────────────────┘\n");

        Socket socket = null;
        try {
            // ── Step 1: Connect ──────────────────────────────────────────
            System.out.printf("[Step 1] Connecting to Server at %s:%d…%n",
                    SERVER_IP, SERVER_PORT);
            socket = new Socket(SERVER_IP, SERVER_PORT);
            System.out.printf("[Step 1] Connected (local port %d).%n%n",
                    socket.getLocalPort());

            OutputStream out = socket.getOutputStream();
            InputStream  in  = socket.getInputStream();

            // ── Step 2: Send request ─────────────────────────────────────
            System.out.println("[Step 2] Sending N-body JSON payload:");
            System.out.println("         " + abbreviate(payload, 110));
            out.write(payload.getBytes());
            out.flush();

            // ── Step 3: Signal end of request ────────────────────────────
            socket.shutdownOutput();
            System.out.println("[Step 3] shutdownOutput() called — Pipe routing begins.\n");

            // ── Step 4: Read available services ──────────────────────────
            System.out.println("[Step 4] Reading AVAILABLE_SERVICES from Pipe…");
            String serviceList = readLine(in);
            System.out.println("[Step 4] " + serviceList + "\n");

            // ── Step 5: Receive result ────────────────────────────────────
            System.out.println("[Step 5] Waiting for result from NBodyNode…");
            byte[] resultBytes = in.readAllBytes();
            String result      = new String(resultBytes).trim();
            System.out.printf("[Step 5] Received %d bytes.%n%n", resultBytes.length);

            socket.close();

            // ── Step 6: Display (summary only — full JSON includes all frames) ───
            System.out.println("[Step 6] Result summary:");
            System.out.println("─".repeat(62));
            System.out.println("         " + abbreviate(result, 200));
            System.out.printf ("         (full response: %d bytes, includes all step frames)%n",
                    resultBytes.length);
            System.out.println("─".repeat(62) + "\n");

            boolean ok = result.contains("\"status\": \"ok\"") || result.contains("\"status\":\"ok\"");
            System.out.println(ok
                ? "[PASS] Scenario " + title.charAt(0) + " — service returned ok.\n"
                : "[FAIL] Scenario " + title.charAt(0) + " — unexpected response:\n       " + result + "\n");

        } catch (IOException e) {
            System.out.println("\n[FAIL] IO error: " + e.getMessage());
            System.out.println("       Is java Source.Server running?");
            System.out.println("       Is java Services.NBody.NBodyNode running?\n");
        } finally {
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    // -----------------------------------------------------------------------
    // JSON payload builder
    // -----------------------------------------------------------------------

    private static String buildPayload(double dt, int steps, Body... bodies) {
        StringBuilder sb = new StringBuilder();
        sb.append("{ \"service\": 1");
        sb.append(", \"dt\": ").append(dt);
        sb.append(", \"steps\": ").append(steps);
        sb.append(", \"bodies\": [");

        for (int i = 0; i < bodies.length; i++) {
            Body b = bodies[i];
            sb.append("{ ")
              .append("\"mass\": ").append(b.mass).append(", ")
              .append("\"x\": ")   .append(b.x)   .append(", ")
              .append("\"y\": ")   .append(b.y)   .append(", ")
              .append("\"z\": ")   .append(b.z)   .append(", ")
              .append("\"vx\": ")  .append(b.vx)  .append(", ")
              .append("\"vy\": ")  .append(b.vy)  .append(", ")
              .append("\"vz\": ")  .append(b.vz)
              .append(" }");
            if (i < bodies.length - 1) sb.append(", ");
        }

        sb.append("] }");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Networking helpers
    // -----------------------------------------------------------------------

    /** Reads one '\n'-terminated line from an InputStream. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Display helpers
    // -----------------------------------------------------------------------

    private static void banner(String title) {
        String bar = "═".repeat(title.length() + 4);
        System.out.println("╔" + bar + "╗");
        System.out.println("║  " + title + "  ║");
        System.out.println("╚" + bar + "╝\n");
    }

    private static String abbreviate(String s, int maxLen) {
        String flat = s.replaceAll("\\s+", " ");
        return flat.length() > maxLen ? flat.substring(0, maxLen - 1) + "…" : flat;
    }
}
