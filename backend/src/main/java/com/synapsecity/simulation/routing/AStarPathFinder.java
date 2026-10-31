package com.synapsecity.simulation.routing;

import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadNode;
import com.synapsecity.simulation.roads.RoadSegment;

import java.util.Arrays;

/**
 * A* search ordered by f = g + h, where h is the cost model's straight-line travel-time bound.
 * With a consistent heuristic every node is settled at most once and the first time the goal is
 * settled its cost is optimal, so A* returns the same cost as Dijkstra while expanding fewer nodes.
 */
public final class AStarPathFinder implements PathFinder {

    @Override
    public RouteResult find(RoadGraph graph, RoadNode start, RoadNode goal, CostModel cost, RoadSegment forbiddenFirst) {
        long t0 = System.nanoTime();
        int n = graph.nodeCount();
        double[] gScore = new double[n];
        int[] via = new int[n];
        boolean[] closed = new boolean[n];
        Arrays.fill(gScore, Double.POSITIVE_INFINITY);
        Arrays.fill(via, -1);

        int s = start.id();
        int g = goal.id();
        gScore[s] = 0;
        SearchQueue open = new SearchQueue();
        open.push(cost.heuristic(start, goal), s);
        int explored = 0;

        while (!open.isEmpty()) {
            int u = open.pop();
            if (closed[u]) {
                continue;
            }
            closed[u] = true;
            explored++;
            if (u == g) {
                break;
            }
            for (RoadSegment seg : graph.node(u).outgoing()) {
                if (u == s && seg == forbiddenFirst) {
                    continue;
                }
                int v = seg.to().id();
                if (closed[v]) {
                    continue;
                }
                double c = cost.cost(seg);
                if (Double.isInfinite(c)) {
                    continue;
                }
                double tentative = gScore[u] + c;
                if (tentative < gScore[v]) {
                    gScore[v] = tentative;
                    via[v] = seg.id();
                    open.push(tentative + cost.heuristic(seg.to(), goal), v);
                }
            }
        }
        long nanos = System.nanoTime() - t0;
        if (!closed[g]) {
            return RouteResult.notFound(explored, nanos, RoutingAlgorithm.A_STAR);
        }
        return PathReconstruction.build(graph, s, g, via, gScore[g], explored, nanos, RoutingAlgorithm.A_STAR);
    }

    @Override
    public RoutingAlgorithm algorithm() {
        return RoutingAlgorithm.A_STAR;
    }
}
