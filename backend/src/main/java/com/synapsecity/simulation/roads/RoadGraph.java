package com.synapsecity.simulation.roads;

import com.synapsecity.simulation.core.Vec2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Directed road graph. Nodes, roads and segments are stored in dense lists indexed by id,
 * which keeps iteration order (and therefore the simulation) deterministic.
 */
public final class RoadGraph {

    private final List<RoadNode> nodes = new ArrayList<>();
    private final List<Road> roads = new ArrayList<>();
    private final List<RoadSegment> segments = new ArrayList<>();
    private boolean finalized;

    public RoadNode addNode(Vec2 position) {
        ensureMutable();
        RoadNode n = new RoadNode(nodes.size(), position);
        nodes.add(n);
        return n;
    }

    /** Adds a two-way road and its two directed segments. */
    public Road addRoad(RoadNode a, RoadNode b, RoadClass roadClass) {
        Road r = addRoadInternal(a, b, roadClass);
        r.setForward(addSegment(r, a, b));
        r.setBackward(addSegment(r, b, a));
        return r;
    }

    /** Adds a one-way road a -> b (used by tests and for disconnected-network edge cases). */
    public Road addOneWayRoad(RoadNode a, RoadNode b, RoadClass roadClass) {
        Road r = addRoadInternal(a, b, roadClass);
        r.setForward(addSegment(r, a, b));
        return r;
    }

    private Road addRoadInternal(RoadNode a, RoadNode b, RoadClass roadClass) {
        ensureMutable();
        if (a == b) {
            throw new IllegalArgumentException("Self-loop roads are not allowed");
        }
        Road r = new Road(roads.size(), a, b, roadClass);
        roads.add(r);
        a.addRoad(r);
        b.addRoad(r);
        return r;
    }

    private RoadSegment addSegment(Road road, RoadNode from, RoadNode to) {
        RoadSegment s = new RoadSegment(segments.size(), road, from, to);
        segments.add(s);
        from.addOutgoing(s);
        to.addIncoming(s);
        return s;
    }

    /**
     * Computes junction clearance radii and inset lane geometry. Must be called once after
     * construction; the topology is frozen afterwards.
     */
    public void finalizeGeometry() {
        ensureMutable();
        for (RoadNode n : nodes) {
            double maxHalf = 0;
            for (Road r : n.roads()) {
                maxHalf = Math.max(maxHalf, r.halfWidth());
            }
            double radius;
            if (n.degree() >= 3) {
                radius = maxHalf + 3.5;
            } else if (n.degree() == 2 && isStraightThrough(n)) {
                radius = 1.0;
            } else {
                radius = maxHalf + 1.0;
            }
            // Never let the junction box swallow a short road.
            for (Road r : n.roads()) {
                radius = Math.min(radius, r.centreLength() * 0.3);
            }
            n.setRadius(radius);
        }
        for (RoadSegment s : segments) {
            s.computeGeometry();
        }
        finalized = true;
    }

    private static boolean isStraightThrough(RoadNode n) {
        Road r1 = n.roads().get(0);
        Road r2 = n.roads().get(1);
        Vec2 d1 = other(r1, n).position().sub(n.position()).normalized();
        Vec2 d2 = other(r2, n).position().sub(n.position()).normalized();
        return d1.dot(d2) < -0.97;
    }

    private static RoadNode other(Road r, RoadNode n) {
        return r.a() == n ? r.b() : r.a();
    }

    private void ensureMutable() {
        if (finalized) {
            throw new IllegalStateException("Road graph topology is frozen after finalizeGeometry()");
        }
    }

    public List<RoadNode> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<Road> roads() {
        return Collections.unmodifiableList(roads);
    }

    public List<RoadSegment> segments() {
        return Collections.unmodifiableList(segments);
    }

    public RoadNode node(int id) {
        return nodes.get(id);
    }

    public Road road(int id) {
        return roads.get(id);
    }

    public RoadSegment segment(int id) {
        return segments.get(id);
    }

    public int nodeCount() {
        return nodes.size();
    }

    public boolean isFinalized() {
        return finalized;
    }

    /** The opposite-direction segment of the same road, or {@code null} for one-way roads. */
    public RoadSegment reverseOf(RoadSegment s) {
        Road r = s.road();
        return r.forward() == s ? r.backward() : r.forward();
    }

    /** The fastest speed limit anywhere in the network; bounds the A* heuristic. */
    public double maxSpeedLimit() {
        double max = 1;
        for (RoadSegment s : segments) {
            max = Math.max(max, s.speedLimit());
        }
        return max;
    }

    /** Directed segment from {@code a} to {@code b}, if one exists. */
    public RoadSegment segmentBetween(RoadNode a, RoadNode b) {
        for (RoadSegment s : a.outgoing()) {
            if (s.to() == b) {
                return s;
            }
        }
        return null;
    }

    /**
     * Strongly connected component id for every node (Kosaraju's algorithm, iterative so that
     * large generated cities cannot overflow the stack). Closed roads are ignored when
     * {@code respectClosures} is true.
     */
    public int[] stronglyConnectedComponents(boolean respectClosures) {
        int n = nodes.size();
        boolean[] visited = new boolean[n];
        int[] order = new int[n];
        int orderSize = 0;

        // Pass 1: iterative DFS on the forward graph recording finish order.
        int[] edgeCursor = new int[n];
        Deque<Integer> stack = new ArrayDeque<>();
        for (int root = 0; root < n; root++) {
            if (visited[root]) {
                continue;
            }
            visited[root] = true;
            stack.push(root);
            while (!stack.isEmpty()) {
                int u = stack.peek();
                List<RoadSegment> out = nodes.get(u).outgoing();
                boolean pushed = false;
                while (edgeCursor[u] < out.size()) {
                    RoadSegment s = out.get(edgeCursor[u]++);
                    if (respectClosures && s.isClosed()) {
                        continue;
                    }
                    int v = s.to().id();
                    if (!visited[v]) {
                        visited[v] = true;
                        stack.push(v);
                        pushed = true;
                        break;
                    }
                }
                if (!pushed) {
                    stack.pop();
                    order[orderSize++] = u;
                }
            }
        }

        // Pass 2: DFS on the transposed graph in reverse finish order.
        int[] component = new int[n];
        Arrays.fill(component, -1);
        int componentCount = 0;
        for (int i = orderSize - 1; i >= 0; i--) {
            int root = order[i];
            if (component[root] != -1) {
                continue;
            }
            stack.push(root);
            component[root] = componentCount;
            while (!stack.isEmpty()) {
                int u = stack.pop();
                for (RoadSegment s : nodes.get(u).incoming()) {
                    if (respectClosures && s.isClosed()) {
                        continue;
                    }
                    int v = s.from().id();
                    if (component[v] == -1) {
                        component[v] = componentCount;
                        stack.push(v);
                    }
                }
            }
            componentCount++;
        }
        return component;
    }

    public boolean isStronglyConnected() {
        if (nodes.isEmpty()) {
            return true;
        }
        int[] c = stronglyConnectedComponents(false);
        for (int id : c) {
            if (id != c[0]) {
                return false;
            }
        }
        return true;
    }
}
