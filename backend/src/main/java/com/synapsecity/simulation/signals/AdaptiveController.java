package com.synapsecity.simulation.signals;

/**
 * Demand-responsive control based on a queue/wait "pressure" score per phase.
 *
 * <pre>
 *   pressure(p) = sum over approaches a in p of  queue(a) + 0.35 * demand(a) + maxWait(a) / 8
 * </pre>
 *
 * Rules, evaluated every tick while a phase is green:
 * <ol>
 *   <li>Minimum green: never end a phase before {@value #MIN_GREEN} s.</li>
 *   <li>Hold green when no other phase has any detected demand.</li>
 *   <li>Fairness: if another phase with demand has waited more than {@value #MAX_RED} s since it was
 *       last served, switch to it (prevents starvation of low-volume approaches).</li>
 *   <li>Maximum green: end the phase after {@value #MAX_GREEN} s if others are waiting.</li>
 *   <li>Gap-out: end the phase early when it has no demand left.</li>
 *   <li>Pressure switch: end the phase when the best competing pressure exceeds the current
 *       pressure by a hysteresis factor ({@value #SWITCH_RATIO}x + {@value #SWITCH_MARGIN}).</li>
 * </ol>
 * The next phase is the most starved phase if any, otherwise the competing phase with the
 * highest pressure.
 */
public final class AdaptiveController implements SignalController {

    public static final double MIN_GREEN = 7.0;
    public static final double MAX_GREEN = 48.0;
    public static final double MAX_RED = 75.0;
    public static final double SWITCH_RATIO = 1.3;
    public static final double SWITCH_MARGIN = 1.5;
    public static final double DEMAND_WEIGHT = 0.35;
    public static final double WAIT_WEIGHT = 1.0 / 8.0;

    public static double pressure(Intersection ix, int phase) {
        double total = 0;
        for (Approach a : ix.phaseApproaches(phase)) {
            total += a.queue() + DEMAND_WEIGHT * a.demand() + WAIT_WEIGHT * a.maxWait();
        }
        return total;
    }

    @Override
    public SignalMode mode() {
        return SignalMode.ADAPTIVE;
    }

    @Override
    public boolean shouldTerminateGreen(Intersection ix, double time) {
        double elapsed = ix.stageElapsed();
        if (elapsed < MIN_GREEN) {
            return false;
        }
        int current = ix.currentPhase();
        int best = -1;
        double bestPressure = -1;
        boolean starved = false;
        for (int p = 0; p < ix.phaseCount(); p++) {
            if (p == current || ix.phaseDemand(p) == 0) {
                continue;
            }
            if (ix.timeSinceServed(p, time) > MAX_RED) {
                starved = true;
            }
            double pr = pressure(ix, p);
            if (pr > bestPressure) {
                bestPressure = pr;
                best = p;
            }
        }
        if (best < 0) {
            return false;
        }
        if (starved || elapsed >= MAX_GREEN || ix.phaseDemand(current) == 0) {
            return true;
        }
        return bestPressure > pressure(ix, current) * SWITCH_RATIO + SWITCH_MARGIN;
    }

    @Override
    public int selectNextPhase(Intersection ix, double time) {
        int current = ix.currentPhase();
        int starvedPhase = -1;
        double longestWait = MAX_RED;
        int best = -1;
        double bestPressure = -1;
        for (int p = 0; p < ix.phaseCount(); p++) {
            if (p == current || ix.phaseDemand(p) == 0) {
                continue;
            }
            double since = ix.timeSinceServed(p, time);
            if (since > longestWait) {
                longestWait = since;
                starvedPhase = p;
            }
            double pr = pressure(ix, p);
            if (pr > bestPressure) {
                bestPressure = pr;
                best = p;
            }
        }
        if (starvedPhase >= 0) {
            return starvedPhase;
        }
        return best >= 0 ? best : (current + 1) % ix.phaseCount();
    }

    @Override
    public double plannedGreenRemaining(Intersection ix) {
        return -1;
    }
}
