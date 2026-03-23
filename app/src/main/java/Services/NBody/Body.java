package Services.NBody;

/**
 * Body — represents a single particle in the N-body simulation.
 *
 * All fields are public and mutable so NBodyStepper can update them
 * in-place each step without allocating new objects.
 *
 * Units (SI):
 *   mass        — kilograms (kg)
 *   x, y, z     — meters (m)
 *   vx, vy, vz  — meters per second (m/s)
 */
public class Body {

    public double mass;
    public double x,  y,  z;
    public double vx, vy, vz;

    public Body(double mass,
                double x,  double y,  double z,
                double vx, double vy, double vz) {
        this.mass = mass;
        this.x  = x;  this.y  = y;  this.z  = z;
        this.vx = vx; this.vy = vy; this.vz = vz;
    }

    @Override
    public String toString() {
        return String.format(
            "Body{mass=%.3e, pos=(%.3e,%.3e,%.3e), vel=(%.3e,%.3e,%.3e)}",
            mass, x, y, z, vx, vy, vz);
    }
}
