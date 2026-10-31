package com.synapsecity.simulation.routing;

import com.synapsecity.simulation.roads.RoadSegment;

import java.util.List;

/**
 * Outcome of one shortest-path query, with the instrumentation surfaced in the route-analysis panel.
 *
 * @param segments      ordered directed segments from start to goal (empty if start == goal)
 * @param cost          total cost in seconds under the cost model used
 * @param distance      total centre-line distance in metres
 * @param exploredNodes nodes settled (popped and finalised) by the search
 * @param computeNanos  wall-clock time spent inside the search
 */
public record RouteResult(boolean found, List<RoadSegment> segments, double cost, double distance,
                          int exploredNodes, long computeNanos, RoutingAlgorithm algorithm) {

    public static RouteResult notFound(int explored, long nanos, RoutingAlgorithm algorithm) {
        return new RouteResult(false, List.of(), Double.POSITIVE_INFINITY, 0, explored, nanos, algorithm);
    }
}
