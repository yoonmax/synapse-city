package com.synapsecity.simulation.signals;

/**
 * Pre-timed control: phases are served in a fixed cyclic order with fixed green splits.
 * Phases that serve an arterial or collector get a longer split.
 */
public final class FixedTimeController implements SignalController {

    public static final double MAJOR_GREEN = 24.0;
    public static final double MINOR_GREEN = 15.0;

    public static double greenTime(Intersection ix, int phase) {
        return ix.phaseHasMajorRoad(phase) ? MAJOR_GREEN : MINOR_GREEN;
    }

    public static double cycleLength(Intersection ix) {
        double total = 0;
        for (int p = 0; p < ix.phaseCount(); p++) {
            total += greenTime(ix, p) + Intersection.YELLOW_TIME + Intersection.ALL_RED_TIME;
        }
        return total;
    }

    @Override
    public SignalMode mode() {
        return SignalMode.FIXED;
    }

    @Override
    public boolean shouldTerminateGreen(Intersection ix, double time) {
        return ix.stageElapsed() >= greenTime(ix, ix.currentPhase());
    }

    @Override
    public int selectNextPhase(Intersection ix, double time) {
        return (ix.currentPhase() + 1) % ix.phaseCount();
    }

    @Override
    public double plannedGreenRemaining(Intersection ix) {
        return Math.max(0, greenTime(ix, ix.currentPhase()) - ix.stageElapsed());
    }
}
