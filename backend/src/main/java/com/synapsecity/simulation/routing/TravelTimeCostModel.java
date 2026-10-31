package com.synapsecity.simulation.routing;

import com.synapsecity.simulation.roads.RoadNode;
import com.synapsecity.simulation.roads.RoadSegment;

/**
 * Congestion-aware travel-time cost.
 *
 * <pre>
 *   t_free = graphLength / effectiveSpeedLimit           (incidents lower the limit)
 *   t_bpr  = t_free * (1 + 0.15 * (load / practicalCap)^4)  (Bureau of Public Roads volume-delay)
 *   t_obs  = graphLength / max(observedSpeed, 0.8)        (live smoothed speed on the segment)
 *   cost   = t_free + w * (max(t_bpr, t_obs) - t_free) + signalDelay(to)
 * </pre>
 *
 * Practical capacity is taken as half the jam capacity. Every term is >= graphLength / maxSpeed,
 * so the straight-line heuristic {@code distance / maxSpeed} is admissible and consistent.
 */
public final class TravelTimeCostModel implements CostModel {

    /** Expected extra delay for passing through a signalised junction, in seconds. */
    public static final double SIGNAL_DELAY = 3.0;

    private final double maxSpeed;
    private final boolean[] signalized;
    private final double congestionWeight;

    public TravelTimeCostModel(double maxSpeed, boolean[] signalized, RouteProfile profile) {
        this.maxSpeed = maxSpeed;
        this.signalized = signalized;
        this.congestionWeight = profile.congestionWeight();
    }

    @Override
    public double cost(RoadSegment s) {
        if (s.isClosed()) {
            return Double.POSITIVE_INFINITY;
        }
        double tFree = s.graphLength() / s.effectiveSpeedLimit();
        double x = s.loadRatio() * 2.0;
        double tBpr = tFree * (1 + 0.15 * x * x * x * x);
        double tObs = s.graphLength() / Math.max(s.observedSpeed(), 0.8);
        double congested = Math.max(tBpr, tObs);
        double delay = signalized != null && signalized[s.to().id()] ? SIGNAL_DELAY : 0;
        return tFree + congestionWeight * Math.max(0, congested - tFree) + delay;
    }

    @Override
    public double heuristic(RoadNode from, RoadNode goal) {
        return from.position().distance(goal.position()) / maxSpeed;
    }
}
