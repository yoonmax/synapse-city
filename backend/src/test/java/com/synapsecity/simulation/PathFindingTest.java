package com.synapsecity.simulation;

import com.synapsecity.simulation.roads.CityGenerator;
import com.synapsecity.simulation.roads.CityLayout;
import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadNode;
import com.synapsecity.simulation.roads.RoadSegment;
import com.synapsecity.simulation.routing.AStarPathFinder;
import com.synapsecity.simulation.routing.CostModel;
import com.synapsecity.simulation.routing.DijkstraPathFinder;
import com.synapsecity.simulation.routing.RouteProfile;
import com.synapsecity.simulation.routing.RouteResult;
import com.synapsecity.simulation.routing.TravelTimeCostModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class PathFindingTest {

    /** Pure distance cost so expected answers can be computed by hand. */
    static final CostModel DISTANCE = new CostModel() {
        @Override
        public double cost(RoadSegment s) {
            return s.isClosed() ? Double.POSITIVE_INFINITY : s.graphLength();
        }

        @Override
        public double heuristic(RoadNode from, RoadNode goal) {
            return from.position().distance(goal.position());
        }
    };

    @Test
    void dijkstraFindsKnownShortestPath() {
        RoadGraph g = TestCities.grid3();
        RouteResult r = new DijkstraPathFinder().find(g, g.node(0), g.node(8), DISTANCE, null);
        assertTrue(r.found());
        assertEquals(400, r.cost(), 1e-6, "Manhattan distance across a 3x3 grid");
        assertEquals(4, r.segments().size());
        assertContiguous(r, g.node(0), g.node(8));
    }

    @Test
    void travelTimeCostPrefersTheFasterArterial() {
        RoadGraph g = TestCities.grid3();
        boolean[] noSignals = new boolean[g.nodeCount()];
        CostModel time = new TravelTimeCostModel(g.maxSpeedLimit(), noSignals, RouteProfile.FREE_FLOW);
        // Node 3 -> 5 straight along the middle (arterial) row beats any detour.
        RouteResult r = new AStarPathFinder().find(g, g.node(3), g.node(5), time, null);
        assertEquals(2, r.segments().size());
        assertTrue(r.segments().stream().allMatch(s -> s.roadClass().name().equals("ARTERIAL")));
    }

    @Test
    void startEqualsGoalYieldsEmptyRoute() {
        RoadGraph g = TestCities.grid3();
        RouteResult r = new AStarPathFinder().find(g, g.node(4), g.node(4), DISTANCE, null);
        assertTrue(r.found());
        assertTrue(r.segments().isEmpty());
        assertEquals(0, r.cost());
    }

    @Test
    void unreachableDestinationsAreReportedNotFound() {
        RoadGraph g = TestCities.disconnected();
        assertFalse(new DijkstraPathFinder().find(g, g.node(0), g.node(3), DISTANCE, null).found());
        assertFalse(new AStarPathFinder().find(g, g.node(0), g.node(3), DISTANCE, null).found());
    }

    @Test
    void closedRoadsAreAvoidedAndForbiddenFirstEdgeIsRespected() {
        RoadGraph g = TestCities.grid3();
        RoadSegment direct = g.segmentBetween(g.node(0), g.node(1));
        direct.road().setClosed(true);
        RouteResult r = new DijkstraPathFinder().find(g, g.node(0), g.node(1), DISTANCE, null);
        assertTrue(r.found());
        assertTrue(r.segments().stream().noneMatch(RoadSegment::isClosed));
        assertEquals(300, r.cost(), 1e-6, "detour around the closed block");
        direct.road().setClosed(false);

        RoadSegment up = g.segmentBetween(g.node(0), g.node(3));
        RouteResult noUp = new AStarPathFinder().find(g, g.node(0), g.node(3), DISTANCE, up);
        assertNotSame(up, noUp.segments().get(0));
        assertEquals(300, noUp.cost(), 1e-6);
    }

    @ParameterizedTest
    @EnumSource(CityLayout.class)
    void aStarMatchesDijkstraCostAndExploresNoMoreNodes(CityLayout layout) {
        RoadGraph g = CityGenerator.generate(99, layout).graph();
        CostModel time = new TravelTimeCostModel(g.maxSpeedLimit(), new boolean[g.nodeCount()], RouteProfile.STANDARD);
        SplittableRandom rng = new SplittableRandom(5);
        long dijkstraExplored = 0;
        long aStarExplored = 0;
        for (int i = 0; i < 200; i++) {
            RoadNode a = g.node(rng.nextInt(g.nodeCount()));
            RoadNode b = g.node(rng.nextInt(g.nodeCount()));
            RouteResult d = new DijkstraPathFinder().find(g, a, b, time, null);
            RouteResult s = new AStarPathFinder().find(g, a, b, time, null);
            assertTrue(d.found() && s.found(), "generated cities are strongly connected");
            assertEquals(d.cost(), s.cost(), 1e-6, "A* with an admissible, consistent heuristic is optimal");
            assertTrue(s.exploredNodes() <= d.exploredNodes());
            assertContiguous(s, a, b);
            dijkstraExplored += d.exploredNodes();
            aStarExplored += s.exploredNodes();
        }
        assertTrue(aStarExplored < dijkstraExplored, "the heuristic should prune the search overall");
    }

    static void assertContiguous(RouteResult r, RoadNode from, RoadNode to) {
        RoadNode cur = from;
        double distance = 0;
        for (RoadSegment s : r.segments()) {
            assertSame(cur, s.from());
            cur = s.to();
            distance += s.graphLength();
        }
        assertSame(to, cur);
        assertEquals(r.distance(), distance, 1e-6);
    }
}
