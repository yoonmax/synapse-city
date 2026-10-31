package com.synapsecity.simulation.routing;

import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadNode;
import com.synapsecity.simulation.roads.RoadSegment;

import java.util.Arrays;

/**
 * Dijkstra's algorithm over directed segments with a binary heap: O((V + E) log V).
 * Settles nodes in non-decreasing cost order and stops as soon as the goal is settled.
 */
public final class DijkstraPathFinder implements PathFinder {

    @Override
    public RouteResult find(RoadGraph graph, RoadNode start, RoadNode goal, CostModel cost, RoadSegment forbiddenFirst) {
        long t0 = System.nanoTime();
        int n = graph.nodeCount();
        double[] dist = new double[n];
        int[] via = new int[n];
        boolean[] settled = new boolean[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(via, -1);

        int s = start.id();
        int g = goal.id();
        dist[s] = 0;
        SearchQueue open = new SearchQueue();
        open.push(0, s);
        int explored = 0;

        while (!open.isEmpty()) {
            double key = open.peekKey();
            int u = open.pop();
            if (settled[u] || key > dist[u]) {
                continue;
            }
            settled[u] = true;
            explored++;
            if (u == g) {
                break;
            }
            for (RoadSegment seg : graph.node(u).outgoing()) {
                if (u == s && seg == forbiddenFirst) {
                    continue;
                }
                double c = cost.cost(seg);
                if (Double.isInfinite(c)) {
                    continue;
                }
                int v = seg.to().id();
                double nd = dist[u] + c;
                if (nd < dist[v]) {
                    dist[v] = nd;
                    via[v] = seg.id();
                    open.push(nd, v);
                }
            }
        }
        long nanos = System.nanoTime() - t0;
        if (!settled[g]) {
            return RouteResult.notFound(explored, nanos, RoutingAlgorithm.DIJKSTRA);
        }
        return PathReconstruction.build(graph, s, g, via, dist[g], explored, nanos, RoutingAlgorithm.DIJKSTRA);
    }

    @Override
    public RoutingAlgorithm algorithm() {
        return RoutingAlgorithm.DIJKSTRA;
    }
}
