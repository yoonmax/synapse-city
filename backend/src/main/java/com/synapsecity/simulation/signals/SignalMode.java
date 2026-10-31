package com.synapsecity.simulation.signals;

public enum SignalMode {
    /** Pre-timed cyclic plan with a position-based offset (a simple green wave). */
    FIXED,
    /** Demand-responsive control driven by measured queues and waiting times. */
    ADAPTIVE
}
