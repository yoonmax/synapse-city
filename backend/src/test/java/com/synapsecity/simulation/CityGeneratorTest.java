package com.synapsecity.simulation;

import com.synapsecity.simulation.roads.Building;
import com.synapsecity.simulation.roads.CityGenerator;
import com.synapsecity.simulation.roads.CityLayout;
import com.synapsecity.simulation.roads.CityModel;
import com.synapsecity.simulation.roads.Road;
import com.synapsecity.simulation.roads.RoadNode;
import com.synapsecity.simulation.roads.RoadSegment;
import com.synapsecity.simulation.core.Vec2;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CityGeneratorTest {

    @ParameterizedTest
    @EnumSource(CityLayout.class)
    void sameSeedProducesIdenticalCity(CityLayout layout) {
        CityModel a = CityGenerator.generate(4242, layout);
        CityModel b = CityGenerator.generate(4242, layout);
        assertEquals(a.name(), b.name());
        assertEquals(a.graph().nodeCount(), b.graph().nodeCount());
        for (int i = 0; i < a.graph().nodeCount(); i++) {
            assertEquals(a.graph().node(i).position(), b.graph().node(i).position());
        }
        assertEquals(a.graph().roads().size(), b.graph().roads().size());
        assertEquals(a.buildings(), b.buildings());
        assertEquals(a.parks(), b.parks());
        assertEquals(a.busStopSegments(), b.busStopSegments());
    }

    @ParameterizedTest
    @EnumSource(CityLayout.class)
    void differentSeedsProduceDifferentCities(CityLayout layout) {
        CityModel a = CityGenerator.generate(1, layout);
        CityModel b = CityGenerator.generate(2, layout);
        assertNotEquals(a.buildings(), b.buildings());
    }

    @ParameterizedTest
    @EnumSource(CityLayout.class)
    void everyLayoutIsAValidTraversableGraph(CityLayout layout) {
        for (long seed : new long[]{1, 77, 9001, 123456789}) {
            CityModel city = CityGenerator.generate(seed, layout);
            var g = city.graph();
            assertTrue(g.nodeCount() >= 30, "city should not collapse after pruning");
            assertTrue(g.isStronglyConnected(), layout + " seed " + seed + " must be strongly connected");
            Set<Integer> ids = new HashSet<>();
            for (RoadSegment s : g.segments()) {
                assertTrue(ids.add(s.id()), "unique segment ids");
                assertTrue(s.length() > 2, "drivable length");
                assertTrue(s.speedLimit() > 0 && s.capacity() > 0 && s.lanes() >= 1);
                assertEquals(0, s.load());
                assertTrue(Double.isFinite(s.freeFlowTime()));
            }
            for (RoadNode n : g.nodes()) {
                assertTrue(n.degree() >= 2, "dead ends are pruned");
            }
            assertTrue(city.emergencyStationNode() >= 0 && city.emergencyStationNode() < g.nodeCount());
            assertFalse(city.busStopSegments().isEmpty());
            assertTrue(city.buildings().size() > 100);
        }
    }

    @ParameterizedTest
    @EnumSource(CityLayout.class)
    void buildingsDoNotSitOnRoads(CityLayout layout) {
        CityModel city = CityGenerator.generate(31, layout);
        for (Building b : city.buildings()) {
            Vec2 c = new Vec2(b.x(), b.y());
            for (Road r : city.graph().roads()) {
                double d = Vec2.pointSegmentDistance(c, r.a().position(), r.b().position());
                assertTrue(d > r.halfWidth(), "building " + b.id() + " centre lies on road " + r.id());
            }
        }
    }
}
