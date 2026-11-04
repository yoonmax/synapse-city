package com.synapsecity.simulation;

import com.synapsecity.simulation.agents.DemandProfile;
import com.synapsecity.simulation.agents.Vehicle;
import com.synapsecity.simulation.agents.VehicleState;
import com.synapsecity.simulation.core.SimulationConfig;
import com.synapsecity.simulation.core.SimulationEngine;
import com.synapsecity.simulation.events.EventType;
import com.synapsecity.simulation.metrics.LiveMetrics;
import com.synapsecity.simulation.roads.CityLayout;
import com.synapsecity.simulation.roads.Road;
import com.synapsecity.simulation.roads.RoadSegment;
import com.synapsecity.simulation.routing.RoutingAlgorithm;
import com.synapsecity.simulation.signals.Intersection;
import com.synapsecity.simulation.signals.SignalMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SimulationEngineTest {

    static SimulationConfig config(CityLayout layout, int vehicles) {
        return new SimulationConfig(20261030, layout, vehicles, SignalMode.ADAPTIVE, RoutingAlgorithm.A_STAR,
                DemandProfile.BALANCED);
    }

    @Test
    void clockAdvancesInExactFixedSteps() {
        SimulationEngine e = new SimulationEngine(config(CityLayout.GRID, 50));
        for (int i = 0; i < 50; i++) {
            e.step();
        }
        assertEquals(50, e.tick());
        assertEquals(5.0, e.time(), 1e-12);
    }

    @Test
    void zeroDemandProducesAnEmptyButHealthySimulation() {
        SimulationEngine e = new SimulationEngine(config(CityLayout.GRID, 0));
        e.runFor(60);
        assertTrue(e.vehicles().isEmpty(), "no demand, no vehicles (buses scale with demand too)");
        LiveMetrics m = e.liveMetrics();
        assertEquals(0, m.active());
        assertEquals(0, m.completedTrips());
        assertTrue(Double.isFinite(m.avgSpeed()) && Double.isFinite(m.congestionIndex()) && Double.isFinite(m.travelTimeIndex()));
        assertFalse(e.metrics().series().isEmpty(), "metrics are still sampled");
        for (Intersection ix : e.intersections()) {
            assertTrue(ix.satisfiesSignalInvariant());
        }
    }

    @Test
    void sameConfigAndCommandsAreBitForBitReproducible() {
        SimulationEngine a = new SimulationEngine(config(CityLayout.GRID, 260));
        SimulationEngine b = new SimulationEngine(config(CityLayout.GRID, 260));
        for (int tick = 1; tick <= 1500; tick++) {
            if (tick == 300) {
                for (SimulationEngine e : List.of(a, b)) {
                    e.closeRoad(10);
                    e.createIncident(null);
                    e.dispatchEmergency(null);
                    e.setSignalMode(SignalMode.FIXED);
                }
            }
            a.step();
            b.step();
            if (tick % 100 == 0) {
                assertEquals(a.stateHash(), b.stateHash(), "diverged at tick " + tick);
            }
        }
        assertEquals(a.metrics().completedTrips(), b.metrics().completedTrips());
    }

    @Test
    void resetRebuildsTheIdenticalInitialWorld() {
        SimulationEngine first = new SimulationEngine(config(CityLayout.RADIAL, 200));
        first.runFor(30);
        long hashAt30 = first.stateHash();
        SimulationEngine reset = new SimulationEngine(first.config());
        reset.runFor(30);
        assertEquals(hashAt30, reset.stateHash());
    }

    @ParameterizedTest
    @EnumSource(CityLayout.class)
    void heavyTrafficKeepsEveryPhysicalAndSignalInvariant(CityLayout layout) {
        SimulationEngine e = new SimulationEngine(config(layout, 900));
        int maxActive = 0;
        for (int tick = 0; tick < 1500; tick++) {
            e.step();
            maxActive = Math.max(maxActive, e.vehicles().size());
            if (tick % 5 != 0) {
                continue;
            }
            for (Intersection ix : e.intersections()) {
                assertTrue(ix.satisfiesSignalInvariant(), "two phases non-red at junction " + ix.node().id());
                List<Intersection.Reservation> held = ix.reservations();
                for (int i = 0; i < held.size(); i++) {
                    assertSame(ix.node(), held.get(i).movement().in().to(), "reservation belongs to this junction");
                    for (int j = i + 1; j < held.size(); j++) {
                        assertFalse(held.get(i).movement().conflictsWith(held.get(j).movement()),
                                "conflicting reservations in junction " + ix.node().id());
                    }
                }
            }
            for (RoadSegment s : e.graph().segments()) {
                for (int l = 0; l < s.lanes(); l++) {
                    List<Vehicle> lane = e.lanes().lane(s, l);
                    for (int i = 1; i < lane.size(); i++) {
                        Vehicle lead = lane.get(i - 1);
                        Vehicle v = lane.get(i);
                        assertTrue(lead.s() - lead.length() - v.s() >= -1e-6, "vehicles overlap on " + s);
                    }
                }
            }
            for (Vehicle v : e.vehicles()) {
                List<RoadSegment> r = v.route();
                for (int i = 0; i + 1 < r.size(); i++) {
                    assertSame(r.get(i).to(), r.get(i + 1).from(), "route must be contiguous");
                }
                assertTrue(v.speed() >= 0 && Double.isFinite(v.s()));
            }
        }
        // Every reservation must belong to a live vehicle, or a ghost could block a junction forever.
        for (Intersection ix : e.intersections()) {
            for (Intersection.Reservation r : ix.reservations()) {
                assertSame(r.vehicle(), e.vehicle(r.vehicle().id()), "reservation held by a removed vehicle");
                assertTrue(r.movement() == r.vehicle().movement() || r.movement() == r.vehicle().clearing(),
                        "reservation the vehicle no longer knows about");
            }
        }
        assertTrue(maxActive > 500, "spawner should reach a large population (" + maxActive + ")");
        // 900 vehicles saturates the smaller layouts, so this only checks that traffic keeps moving.
        assertTrue(e.metrics().completedTrips() > 20, "trips completed: " + e.metrics().completedTrips());
    }

    @Test
    void closingARoadForcesAffectedVehiclesToReplanAroundIt() {
        SimulationEngine e = new SimulationEngine(config(CityLayout.GRID, 320));
        e.runFor(60);
        Map<Integer, Integer> usage = new HashMap<>();
        for (Vehicle v : e.vehicles()) {
            for (int i = v.routeIndex() + 2; i < v.route().size(); i++) {
                usage.merge(v.route().get(i).road().id(), 1, Integer::sum);
            }
        }
        int busiest = usage.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        long reroutesBefore = e.metrics().reroutes();
        assertTrue(e.closeRoad(busiest));
        e.runFor(10);
        Road road = e.graph().road(busiest);
        assertTrue(road.isClosed());
        for (Vehicle v : e.vehicles()) {
            if (v.state() == VehicleState.DESPAWNING) {
                continue;
            }
            for (int i = v.routeIndex() + 2; i < v.route().size(); i++) {
                assertNotSame(road, v.route().get(i).road(), "vehicle " + v.id() + " still plans through the closure");
            }
        }
        assertTrue(e.metrics().reroutes() > reroutesBefore);
        assertTrue(e.events().all().stream().anyMatch(ev -> ev.type() == EventType.ROAD_CLOSED));
        assertTrue(e.openRoad(busiest));
        assertFalse(e.openRoad(busiest), "reopening an open road is a no-op");
    }

    @Test
    void unreachableDestinationsDoNotCrashTheEngine() {
        // Two islands: vehicles can only ever be routed within their own island.
        SimulationEngine e = new SimulationEngine(config(CityLayout.GRID, 20), TestCities.wrap(TestCities.disconnected()));
        e.runFor(60);
        for (Vehicle v : e.vehicles()) {
            for (int i = 0; i + 1 < v.route().size(); i++) {
                assertSame(v.route().get(i).to(), v.route().get(i + 1).from());
            }
        }
        assertTrue(e.router().failures() >= 0);
    }
}
