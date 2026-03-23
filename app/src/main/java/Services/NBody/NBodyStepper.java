package Services.NBody;

import java.util.ArrayList;
import java.util.List;

/**
 * NBodyStepper — pure 3-D gravitational N-body physics engine.
 *
 * No networking, no JSON, no Node infrastructure — just the math.
 * The executor (whoever wires this into the SN listener) calls step()
 * and handles serialization on either side.
 *
 * Physics:
 *   Newton's law of gravitation:  F = G * m_i * m_j / r²
 *   Acceleration on body i due to body j:
 *     a_i = G * m_j * (r_j - r_i) / |r_j - r_i|³
 *
 *   Integration: Euler method.
 *     v(t + dt) = v(t) + a(t) * dt
 *     r(t + dt) = r(t) + v(t + dt) * dt
 *
 *   Softening: ε² is added to r² before taking the square root to
 *   prevent singularities when two bodies get very close.
 *
 * Complexity per step: O(N²) pairwise force evaluations.
 * Newton's third law is exploited so each pair is computed once.
 */
public class NBodyStepper {

    /** Gravitational constant (N·m²/kg²). */
    public static final double G = 6.674e-11;

    /**
     * Softening length (meters).
     * Added as ε² to r² before computing forces.
     * Prevents division by zero when bodies overlap.
     * 1e4 m ≈ 10 km — negligible at planetary/stellar scales.
     */
    public static final double SOFTENING = 1e4;

    /**
     * Runs the simulation and records every intermediate state.
     *
     * Returns a list of (steps + 1) snapshots:
     *   index 0   = initial state (before any integration)
     *   index 1   = state after step 1
     *   index N   = state after step N  (final state)
     *
     * Each snapshot is a deep copy of the body list — modifying a snapshot
     * does not affect the simulation or other snapshots.
     *
     * Used by NBodyExecutor to produce full-playback animation data.
     *
     * @param bodies list of bodies to simulate (must contain >= 2)
     * @param dt     time step in seconds (must be > 0)
     * @param steps  number of integration steps to perform (must be > 0)
     * @return list of (steps + 1) body-list snapshots
     * @throws IllegalArgumentException if inputs are invalid
     */
    public static List<List<Body>> stepAll(List<Body> bodies, double dt, int steps) {
        if (bodies == null || bodies.size() < 2)
            throw new IllegalArgumentException("Need at least 2 bodies.");
        if (dt <= 0)
            throw new IllegalArgumentException("dt must be positive.");
        if (steps <= 0)
            throw new IllegalArgumentException("steps must be positive.");

        int n = bodies.size();
        List<List<Body>> frames = new ArrayList<>(steps + 1);
        frames.add(snapshot(bodies));          // frame 0 — initial state

        double[] ax   = new double[n];
        double[] ay   = new double[n];
        double[] az   = new double[n];
        double   eps2 = SOFTENING * SOFTENING;

        for (int s = 0; s < steps; s++) {

            for (int i = 0; i < n; i++) { ax[i] = ay[i] = az[i] = 0.0; }

            for (int i = 0; i < n - 1; i++) {
                Body bi = bodies.get(i);
                for (int j = i + 1; j < n; j++) {
                    Body bj = bodies.get(j);
                    double dx = bj.x - bi.x, dy = bj.y - bi.y, dz = bj.z - bi.z;
                    double r2 = dx*dx + dy*dy + dz*dz + eps2;
                    double r  = Math.sqrt(r2);
                    double g_r3 = G / (r2 * r);
                    ax[i] += g_r3 * bj.mass * dx;  ay[i] += g_r3 * bj.mass * dy;  az[i] += g_r3 * bj.mass * dz;
                    ax[j] -= g_r3 * bi.mass * dx;  ay[j] -= g_r3 * bi.mass * dy;  az[j] -= g_r3 * bi.mass * dz;
                }
            }

            for (int i = 0; i < n; i++) {
                Body b = bodies.get(i);
                b.vx += ax[i] * dt;  b.vy += ay[i] * dt;  b.vz += az[i] * dt;
                b.x  += b.vx * dt;  b.y  += b.vy * dt;  b.z  += b.vz * dt;
            }

            frames.add(snapshot(bodies));      // frame s+1 — state after this step
        }

        return frames;
    }

    /** Returns a deep copy of the current body positions and velocities. */
    private static List<Body> snapshot(List<Body> bodies) {
        List<Body> copy = new ArrayList<>(bodies.size());
        for (Body b : bodies) copy.add(new Body(b.mass, b.x, b.y, b.z, b.vx, b.vy, b.vz));
        return copy;
    }

    // -----------------------------------------------------------------------

    /**
     * Advances the N-body simulation by the requested number of steps.
     *
     * Bodies are modified in-place — positions and velocities are updated
     * directly on each Body object in the list.
     *
     * @param bodies list of bodies to simulate (must contain >= 2)
     * @param dt     time step in seconds
     * @param steps  number of integration steps to perform
     * @throws IllegalArgumentException if bodies has fewer than 2 entries,
     *                                  or dt / steps are non-positive
     */
    public static void step(List<Body> bodies, double dt, int steps) {
        if (bodies == null || bodies.size() < 2)
            throw new IllegalArgumentException("Need at least 2 bodies.");
        if (dt <= 0)
            throw new IllegalArgumentException("dt must be positive.");
        if (steps <= 0)
            throw new IllegalArgumentException("steps must be positive.");

        int n = bodies.size();

        // Reusable acceleration accumulators — allocated once, zeroed each step.
        double[] ax = new double[n];
        double[] ay = new double[n];
        double[] az = new double[n];

        double eps2 = SOFTENING * SOFTENING;

        for (int s = 0; s < steps; s++) {

            // --- Zero accelerations -------------------------------------------
            for (int i = 0; i < n; i++) {
                ax[i] = 0.0;
                ay[i] = 0.0;
                az[i] = 0.0;
            }

            // --- Pairwise gravitational forces ---------------------------------
            // Iterate over unique pairs (i < j) and apply Newton's third law
            // so each pair is computed exactly once.
            for (int i = 0; i < n - 1; i++) {
                Body bi = bodies.get(i);

                for (int j = i + 1; j < n; j++) {
                    Body bj = bodies.get(j);

                    double dx = bj.x - bi.x;
                    double dy = bj.y - bi.y;
                    double dz = bj.z - bi.z;

                    // r² with softening; r; r³ = r² * r
                    double r2 = dx*dx + dy*dy + dz*dz + eps2;
                    double r  = Math.sqrt(r2);
                    double r3 = r2 * r;

                    // G / r³ — shared factor for both bodies in the pair
                    double g_over_r3 = G / r3;

                    // Acceleration on i toward j  (a = G * m_j * Δr / r³)
                    ax[i] += g_over_r3 * bj.mass * dx;
                    ay[i] += g_over_r3 * bj.mass * dy;
                    az[i] += g_over_r3 * bj.mass * dz;

                    // Acceleration on j toward i  (Newton's third law — opposite direction)
                    ax[j] -= g_over_r3 * bi.mass * dx;
                    ay[j] -= g_over_r3 * bi.mass * dy;
                    az[j] -= g_over_r3 * bi.mass * dz;
                }
            }

            // --- Euler integration --------------------------------------------
            // Update velocities first, then positions (semi-implicit Euler).
            for (int i = 0; i < n; i++) {
                Body b = bodies.get(i);

                b.vx += ax[i] * dt;
                b.vy += ay[i] * dt;
                b.vz += az[i] * dt;

                b.x += b.vx * dt;
                b.y += b.vy * dt;
                b.z += b.vz * dt;
            }
        }
    }
}
