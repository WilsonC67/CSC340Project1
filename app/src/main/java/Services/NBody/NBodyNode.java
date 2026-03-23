package Services.NBody;

import java.nio.charset.StandardCharsets;

import Source.Node;
import Source.Service;

/**
 * NBodyNode — Microservice node for N_BODY_GRAVITATIONAL_STEPPER (service #1).
 *
 * Uses Pattern A: overrides handleRequest() and processes everything
 * in-process by delegating to NBodyExecutor.process(). No subprocess needed.
 *
 * Expected inbound JSON (forwarded by Pipe):
 *   {
 *     "service" : 1,
 *     "dt"      : <double>,     (time step in seconds, must be > 0)
 *     "steps"   : <int>,        (number of integration steps, must be > 0)
 *     "bodies"  : [             (at least 2 body objects)
 *       { "mass":…, "x":…, "y":…, "z":…, "vx":…, "vy":…, "vz":… }, …
 *     ]
 *   }
 *
 * Response JSON written back to Pipe → client:
 *   { "status": "ok",    "steps_completed": <int>, "dt": <double>, "bodies": [ … ] }
 *   { "status": "error", "message": "<details>" }
 *
 * HOW TO RUN (in order):
 *   1. java Source.Server
 *   2. java Services.NBody.NBodyNode
 *   3. Open Frontend/src/Gravitational.html  (or run NBodyClientTest)
 */
public class NBodyNode extends Node {

    private static final int NODE_ID = 1;

    // -----------------------------------------------------------------------
    // Node identity
    // -----------------------------------------------------------------------

    @Override
    protected int getNodeId() { return NODE_ID; }

    @Override
    protected Service getService() { return Service.N_BODY_GRAVITATIONAL_STEPPER; }

    // -----------------------------------------------------------------------
    // Pattern A — in-process request handler
    // -----------------------------------------------------------------------

    /**
     * Delegaes to NBodyExecutor.process(), which parses the JSON payload,
     * runs the N-body simulation, and returns a JSON result string.
     * Never throws — all errors are captured inside the returned JSON.
     */
    @Override
    protected byte[] handleRequest(byte[] payload) {
        String json   = new String(payload, StandardCharsets.UTF_8);
        String result = NBodyExecutor.process(json);
        return result.getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    //Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("[NBodyNode] Starting N_BODY_GRAVITATIONAL_STEPPER node (service #1)...");
        new NBodyNode().run();
    }
}
