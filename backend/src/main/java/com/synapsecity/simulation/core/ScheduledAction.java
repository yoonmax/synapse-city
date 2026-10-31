package com.synapsecity.simulation.core;

import java.util.function.Consumer;

/** A scripted scenario step executed at an exact simulated time, which keeps scenarios reproducible. */
public record ScheduledAction(double time, String label, Consumer<SimulationEngine> action) {
}
