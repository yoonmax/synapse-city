package com.synapsecity.simulation.routing;

import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadNode;
import com.synapsecity.simulation.roads.RoadSegment;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Routing facade used by the engine: selects the active algorithm, builds cost models per profile
 * and aggregates routing instrumentation (calls, explored nodes, compute time).
 */
public final class Router {

    private final RoadGraph graph;
    private final Map<RoutingAlgorithm, PathFinder> finders = new EnumMap<>(RoutingAlgorithm.class);
    private final Map<RouteProfile, CostModel> costModels = new EnumMap<>(RouteProfile.class);
    private RoutingAlgorithm algorithm;

    private long calls;
    private long failures;
    private long totalNanos;
    private long totalExplored;

    public Router(RoadGraph graph, boolean[] signalized, RoutingAlgorithm algorithm) {
        this.graph = graph;
        this.algorithm = algorithm;
        finders.put(RoutingAlgorithm.DIJKSTRA, new DijkstraPathFinder());
        finders.put(RoutingAlgorithm.A_STAR, new AStarPathFinder());
        double maxSpeed = graph.maxSpeedLimit();
        for (RouteProfile p : RouteProfile.values()) {
            costModels.put(p, new TravelTimeCostModel(maxSpeed, signalized, p));
        }
    }

    public RouteResult route(RoadNode from, RoadNode to, RouteProfile profile, RoadSegment forbiddenFirst) {
        return route(from, to, profile, forbiddenFirst, algorithm);
    }

    public RouteResult route(RoadNode from, RoadNode to, RouteProfile profile, RoadSegment forbiddenFirst,
                             RoutingAlgorithm override) {
        RouteResult r = finders.get(override).find(graph, from, to, costModels.get(profile), forbiddenFirst);
        calls++;
        totalNanos += r.computeNanos();
        totalExplored += r.exploredNodes();
        if (!r.found()) {
            failures++;
        }
        return r;
    }

    /** Current cost of following {@code path} over indices [{@code fromIndex}, {@code toExclusive}). */
    public double pathCost(List<RoadSegment> path, int fromIndex, int toExclusive, RouteProfile profile) {
        CostModel model = costModels.get(profile);
        double total = 0;
        for (int i = fromIndex; i < Math.min(toExclusive, path.size()); i++) {
            total += model.cost(path.get(i));
        }
        return total;
    }

    public CostModel costModel(RouteProfile profile) {
        return costModels.get(profile);
    }

    public RoutingAlgorithm algorithm() {
        return algorithm;
    }

    public void setAlgorithm(RoutingAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public long calls() {
        return calls;
    }

    public long failures() {
        return failures;
    }

    public double averageMicros() {
        return calls == 0 ? 0 : totalNanos / 1000.0 / calls;
    }

    public double averageExplored() {
        return calls == 0 ? 0 : (double) totalExplored / calls;
    }
}
