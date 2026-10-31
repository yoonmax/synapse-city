package com.synapsecity.simulation.routing;

import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PathReconstruction {

    private PathReconstruction() {
    }

    static RouteResult build(RoadGraph graph, int start, int goal, int[] viaSegment, double cost,
                             int explored, long nanos, RoutingAlgorithm algorithm) {
        List<RoadSegment> path = new ArrayList<>();
        double distance = 0;
        int cur = goal;
        while (cur != start) {
            RoadSegment s = graph.segment(viaSegment[cur]);
            path.add(s);
            distance += s.graphLength();
            cur = s.from().id();
        }
        Collections.reverse(path);
        return new RouteResult(true, List.copyOf(path), cost, distance, explored, nanos, algorithm);
    }
}
