package com.synapsecity.simulation.agents;

/** The reason behind a vehicle's current control action; surfaced verbatim in the inspector. */
public enum Decision {
    SPAWNING,
    FREE_FLOW,
    FOLLOWING,
    STOP_RED,
    STOP_YELLOW,
    YIELD_CONFLICT,
    YIELD_PRIORITY,
    EXIT_BLOCKED,
    COMMITTED,
    TURNING,
    ARRIVING,
    DWELLING,
    REROUTED,
    ROUTE_BLOCKED,
    PREEMPTING,
    DESPAWNING
}
