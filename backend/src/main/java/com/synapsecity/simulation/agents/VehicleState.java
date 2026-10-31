package com.synapsecity.simulation.agents;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Behavioural state machine. Transitions are validated: attempting an edge that is not listed
 * here throws, which the test suite relies on to prove the engine only takes legal transitions.
 */
public enum VehicleState {
    SPAWNING,
    CRUISING,
    APPROACHING_INTERSECTION,
    WAITING_AT_SIGNAL,
    CROSSING_INTERSECTION,
    REPLANNING,
    ARRIVING,
    DWELLING,
    DESPAWNING;

    private static final Map<VehicleState, Set<VehicleState>> NEXT = new EnumMap<>(VehicleState.class);

    static {
        NEXT.put(SPAWNING, EnumSet.of(CRUISING, APPROACHING_INTERSECTION, WAITING_AT_SIGNAL, ARRIVING, REPLANNING, DESPAWNING));
        NEXT.put(CRUISING, EnumSet.of(APPROACHING_INTERSECTION, WAITING_AT_SIGNAL, REPLANNING, ARRIVING, DESPAWNING));
        NEXT.put(APPROACHING_INTERSECTION, EnumSet.of(CRUISING, WAITING_AT_SIGNAL, CROSSING_INTERSECTION, REPLANNING, ARRIVING, DESPAWNING));
        NEXT.put(WAITING_AT_SIGNAL, EnumSet.of(CRUISING, APPROACHING_INTERSECTION, CROSSING_INTERSECTION, REPLANNING, DESPAWNING));
        NEXT.put(CROSSING_INTERSECTION, EnumSet.of(CRUISING, APPROACHING_INTERSECTION, WAITING_AT_SIGNAL, ARRIVING, DESPAWNING));
        NEXT.put(REPLANNING, EnumSet.of(CRUISING, APPROACHING_INTERSECTION, WAITING_AT_SIGNAL, CROSSING_INTERSECTION, ARRIVING, DESPAWNING));
        NEXT.put(ARRIVING, EnumSet.of(DWELLING, DESPAWNING, REPLANNING, CRUISING, APPROACHING_INTERSECTION, WAITING_AT_SIGNAL));
        NEXT.put(DWELLING, EnumSet.of(REPLANNING, CRUISING, APPROACHING_INTERSECTION, WAITING_AT_SIGNAL, DESPAWNING));
        NEXT.put(DESPAWNING, EnumSet.noneOf(VehicleState.class));
    }

    public boolean canTransitionTo(VehicleState next) {
        return next == this || NEXT.get(this).contains(next);
    }

    public Set<VehicleState> successors() {
        return EnumSet.copyOf(NEXT.get(this));
    }
}
