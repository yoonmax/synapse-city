package com.synapsecity.simulation;

import com.synapsecity.simulation.agents.DriverProfile;
import com.synapsecity.simulation.agents.Vehicle;
import com.synapsecity.simulation.agents.VehicleState;
import com.synapsecity.simulation.agents.VehicleType;
import org.junit.jupiter.api.Test;

import static com.synapsecity.simulation.agents.VehicleState.*;
import static org.junit.jupiter.api.Assertions.*;

class VehicleStateMachineTest {

    private Vehicle vehicle() {
        return new Vehicle(1, VehicleType.CAR, new DriverProfile(1, 1, 1, 0.5), 0);
    }

    @Test
    void typicalTripFollowsLegalTransitions() {
        Vehicle v = vehicle();
        assertEquals(SPAWNING, v.state());
        VehicleState[] path = {CRUISING, APPROACHING_INTERSECTION, WAITING_AT_SIGNAL, CROSSING_INTERSECTION,
                CRUISING, REPLANNING, APPROACHING_INTERSECTION, CROSSING_INTERSECTION, ARRIVING, DESPAWNING};
        double t = 0;
        for (VehicleState s : path) {
            v.transitionTo(s, t += 1);
            assertEquals(s, v.state());
            assertEquals(t, v.stateSince());
        }
    }

    @Test
    void illegalTransitionsThrow() {
        Vehicle v = vehicle();
        assertThrows(IllegalStateException.class, () -> v.transitionTo(CROSSING_INTERSECTION, 1), "cannot spawn inside a junction");
        v.transitionTo(CRUISING, 1);
        assertThrows(IllegalStateException.class, () -> v.transitionTo(DWELLING, 2), "must arrive before dwelling");
        v.transitionTo(APPROACHING_INTERSECTION, 2);
        v.transitionTo(CROSSING_INTERSECTION, 3);
        assertThrows(IllegalStateException.class, () -> v.transitionTo(REPLANNING, 4), "no replanning inside the box");
        v.transitionTo(DESPAWNING, 5);
        for (VehicleState s : VehicleState.values()) {
            if (s != DESPAWNING) {
                assertThrows(IllegalStateException.class, () -> v.transitionTo(s, 6), "DESPAWNING is terminal");
            }
        }
    }

    @Test
    void everyNonTerminalStateCanDespawn() {
        for (VehicleState s : VehicleState.values()) {
            if (s != DESPAWNING) {
                assertTrue(s.canTransitionTo(DESPAWNING), s + " must be able to leave the network");
            }
        }
        assertTrue(DESPAWNING.successors().isEmpty());
    }

    @Test
    void selfTransitionIsANoOp() {
        Vehicle v = vehicle();
        v.transitionTo(CRUISING, 3);
        v.transitionTo(CRUISING, 9);
        assertEquals(3, v.stateSince());
    }
}
