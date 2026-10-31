package com.synapsecity.simulation.routing;

import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadNode;
import com.synapsecity.simulation.roads.RoadSegment;

public interface PathFinder {

    /**
     * Finds a least-cost path from {@code start} to {@code goal}.
     *
     * @param forbiddenFirst a segment that may not be used as the first edge (prevents immediate
     *                       U-turns when replanning mid-trip); may be {@code null}
     */
    RouteResult find(RoadGraph graph, RoadNode start, RoadNode goal, CostModel cost, RoadSegment forbiddenFirst);

    RoutingAlgorithm algorithm();
}
