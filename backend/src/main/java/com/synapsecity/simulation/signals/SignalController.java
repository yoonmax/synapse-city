package com.synapsecity.simulation.signals;

/**
 * Strategy deciding when a green phase ends and which phase is served next. Controllers never
 * set lights directly: the {@link Intersection} always inserts amber and all-red clearance between
 * phases, so every controller inherits the same conflict-safety guarantees.
 */
public interface SignalController {

    SignalMode mode();

    /** Called every tick while the current phase is green. */
    boolean shouldTerminateGreen(Intersection ix, double time);

    int selectNextPhase(Intersection ix, double time);

    /** Seconds of green remaining when known in advance, or -1 when it depends on demand. */
    double plannedGreenRemaining(Intersection ix);
}
