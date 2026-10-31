package com.synapsecity.simulation.metrics;

/** One point of the simulation time series, recorded every {@link MetricsCollector#SAMPLE_INTERVAL} s. */
public record MetricsSample(double time, int active, double avgSpeed, double congestionIndex, int queued,
                            double throughputPerMin, double avgTripTime, double avgTripWait, long completed,
                            double travelTimeIndex) {
}
