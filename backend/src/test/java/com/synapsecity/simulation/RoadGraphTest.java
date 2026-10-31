package com.synapsecity.simulation;

import com.synapsecity.simulation.core.Vec2;
import com.synapsecity.simulation.roads.Road;
import com.synapsecity.simulation.roads.RoadClass;
import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadNode;
import com.synapsecity.simulation.roads.RoadSegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoadGraphTest {

    @Test
    void twoWayRoadsCreateTwoDirectedSegmentsWithConsistentGeometry() {
        RoadGraph g = TestCities.grid3();
        assertEquals(9, g.nodeCount());
        assertEquals(12, g.roads().size());
        assertEquals(24, g.segments().size());
        for (Road r : g.roads()) {
            RoadSegment f = r.forward();
            RoadSegment b = r.backward();
            assertSame(b, g.reverseOf(f));
            assertSame(f, g.reverseOf(b));
            assertSame(f.from(), b.to());
            assertEquals(f.graphLength(), b.graphLength(), 1e-9);
        }
        for (RoadSegment s : g.segments()) {
            assertTrue(s.length() > 0 && s.length() < s.graphLength(), "lane geometry is inset from junction centres");
            assertTrue(s.capacity() >= 1);
            assertTrue(s.speedLimit() > 0);
            assertEquals(s.roadClass().lanesPerDirection(), s.lanes());
            assertTrue(s.freeFlowTime() > 0);
            // Right-hand traffic: every lane centre lies to the right of the travel direction.
            Vec2 lane = s.lanePoint(0, s.length() / 2);
            Vec2 centre = s.start().lerp(s.end(), 0.5);
            assertTrue(s.direction().cross(lane.sub(centre)) < 0);
        }
    }

    @Test
    void topologyIsFrozenAfterFinalize() {
        RoadGraph g = TestCities.grid3();
        assertThrows(IllegalStateException.class, () -> g.addNode(new Vec2(1, 1)));
    }

    @Test
    void selfLoopsAreRejected() {
        RoadGraph g = new RoadGraph();
        RoadNode a = g.addNode(new Vec2(0, 0));
        assertThrows(IllegalArgumentException.class, () -> g.addRoad(a, a, RoadClass.LOCAL));
    }

    @Test
    void stronglyConnectedComponentsDetectDisconnectedAndOneWayNetworks() {
        assertTrue(TestCities.grid3().isStronglyConnected());
        assertFalse(TestCities.disconnected().isStronglyConnected());

        RoadGraph oneWay = new RoadGraph();
        RoadNode a = oneWay.addNode(new Vec2(0, 0));
        RoadNode b = oneWay.addNode(new Vec2(100, 0));
        oneWay.addOneWayRoad(a, b, RoadClass.LOCAL);
        oneWay.finalizeGeometry();
        int[] scc = oneWay.stronglyConnectedComponents(false);
        assertNotEquals(scc[0], scc[1], "a one-way edge is not strongly connected");
    }

    @Test
    void closuresSplitComponentsWhenRespected() {
        RoadGraph g = new RoadGraph();
        RoadNode a = g.addNode(new Vec2(0, 0));
        RoadNode b = g.addNode(new Vec2(100, 0));
        RoadNode c = g.addNode(new Vec2(200, 0));
        g.addRoad(a, b, RoadClass.LOCAL);
        Road bridge = g.addRoad(b, c, RoadClass.LOCAL);
        g.finalizeGeometry();
        bridge.setClosed(true);
        int[] open = g.stronglyConnectedComponents(false);
        int[] closed = g.stronglyConnectedComponents(true);
        assertEquals(open[0], open[2]);
        assertNotEquals(closed[0], closed[2]);
    }
}
