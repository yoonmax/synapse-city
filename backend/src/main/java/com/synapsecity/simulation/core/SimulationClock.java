package com.synapsecity.simulation.core;

/**
 * Fixed-timestep simulation clock. Simulated time is derived from the tick count
 * (never accumulated) so it is exact and independent of wall-clock time and speed multipliers.
 */
public final class SimulationClock {

    private final double dt;
    private long tick;

    public SimulationClock(double dt) {
        if (!(dt > 0)) {
            throw new IllegalArgumentException("dt must be positive");
        }
        this.dt = dt;
    }

    public void advance() {
        tick++;
    }

    public long tick() {
        return tick;
    }

    public double dt() {
        return dt;
    }

    /** Simulated seconds since the start of the run. */
    public double time() {
        return tick * dt;
    }
}
