package com.synapsecity.simulation.routing;

import com.synapsecity.simulation.roads.RoadNode;
import com.synapsecity.simulation.roads.RoadSegment;

/** Edge weights and heuristic for shortest-path search. Costs are seconds of travel time. */
public interface CostModel {

    /** Cost of traversing {@code segment}, or {@link Double#POSITIVE_INFINITY} if it is impassable. */
    double cost(RoadSegment segment);

    /**
     * Lower bound on the cost from {@code from} to {@code goal}. Must never overestimate
     * (admissible) and must satisfy the triangle inequality (consistent) for A* to be optimal.
     */
    double heuristic(RoadNode from, RoadNode goal);
}
