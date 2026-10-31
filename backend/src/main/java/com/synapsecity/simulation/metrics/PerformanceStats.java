package com.synapsecity.simulation.metrics;

/** Measured engine cost. Step timings are wall-clock nanoseconds around {@code SimulationEngine.step()}. */
public record PerformanceStats(long steps, double avgStepMicros, double p95StepMicros, double maxStepMicros,
                               long routingCalls, double avgRoutingMicros, double avgExploredNodes,
                               long routingFailures, long safetyClamps) {
}
