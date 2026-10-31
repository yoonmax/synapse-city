package com.synapsecity.simulation;

import com.synapsecity.simulation.agents.Vehicle;
import com.synapsecity.simulation.agents.VehicleType;
import com.synapsecity.simulation.core.SimulationEngine;
import com.synapsecity.simulation.events.EventType;
import com.synapsecity.simulation.roads.CityLayout;
import com.synapsecity.simulation.signals.Intersection;
import com.synapsecity.simulation.signals.SignalMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmergencyPriorityTest {

    @Test
    void emergencyUnitGetsSignalPriorityWithoutBreakingSignalSafetyAndReportsResponseTime() {
        SimulationEngine e = new SimulationEngine(SimulationEngineTest.config(CityLayout.GRID, 260).withSignalMode(SignalMode.FIXED));
        e.runFor(40);
        Vehicle unit = e.dispatchEmergency(null);
        assertNotNull(unit);
        assertEquals(VehicleType.EMERGENCY, unit.type());
        assertTrue(unit.sirenActive());
        assertNotNull(unit.lastRoute(), "dispatch plans a congestion-weighted route");
        boolean preemptedDuringTrip = false;
        for (int tick = 0; tick < 4000 && e.metrics().emergencyResponses().isEmpty(); tick++) {
            e.step();
            for (Intersection ix : e.intersections()) {
                assertTrue(ix.satisfiesSignalInvariant(), "preemption must still clear through amber and all-red");
                if (ix.isPreempted() && ix.preemptVehicle() == unit.id()) {
                    preemptedDuringTrip = true;
                }
            }
        }
        assertTrue(preemptedDuringTrip, "unit should have requested priority at signalised junctions");
        assertEquals(1, e.metrics().emergencyResponses().size());
        double response = e.metrics().emergencyResponses().get(0);
        assertTrue(response > 0 && response < 400, "response time " + response);
        assertTrue(e.events().all().stream().anyMatch(ev -> ev.type() == EventType.EMERGENCY_DISPATCHED));
        assertTrue(e.events().all().stream().anyMatch(ev -> ev.type() == EventType.EMERGENCY_ARRIVED));
        assertTrue(e.events().all().stream().anyMatch(ev -> ev.type() == EventType.SIGNAL_PREEMPTED));
    }

    @Test
    void emergencyRoutingWeighsCongestionMoreHeavily() {
        SimulationEngine e = new SimulationEngine(SimulationEngineTest.config(CityLayout.GRID, 400));
        e.runFor(90);
        var router = e.router();
        var std = router.costModel(com.synapsecity.simulation.routing.RouteProfile.STANDARD);
        var emg = router.costModel(com.synapsecity.simulation.routing.RouteProfile.EMERGENCY);
        boolean sawCongested = false;
        for (var s : e.graph().segments()) {
            double free = router.costModel(com.synapsecity.simulation.routing.RouteProfile.FREE_FLOW).cost(s);
            assertTrue(emg.cost(s) >= std.cost(s) - 1e-9);
            assertTrue(std.cost(s) >= free - 1e-9);
            sawCongested |= std.cost(s) > free + 1;
        }
        assertTrue(sawCongested, "some segment should be measurably congested at 400 vehicles");
    }
}
