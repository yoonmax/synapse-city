package com.synapsecity.simulation.signals;

/** Result of asking an intersection whether a vehicle may enter the junction box now. */
public enum EntryDecision {
    GO,
    STOP_RED,
    STOP_YELLOW,
    /** A conflicting movement already holds the box. */
    YIELD_CONFLICT,
    /** Unsignalised junction: a conflicting driver has been waiting longer. */
    YIELD_PRIORITY,
    /** Not enough room on the exit lane; entering would block the box. */
    EXIT_BLOCKED
}
