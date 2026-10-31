package com.synapsecity.simulation.metrics;

/**
 * Instantaneous network statistics. All values are computed from the live simulation state or from
 * recorded trip completions; none are synthesised.
 *
 * @param congestionIndex 100 * (1 - sum(v) / sum(v_limit)) over vehicles on the road, so 0 means
 *                        everyone drives at the limit and 100 means total standstill
 * @param travelTimeIndex mean ratio of actual to free-flow trip time over recent trips
 * @param utilization     mean load / jam capacity over occupied segments
 */
public record LiveMetrics(int active, int moving, int stopped, double avgSpeed, double congestionIndex,
                          String condition, double avgCurrentWait, int queuedAtSignals, long completedTrips,
                          long abandonedTrips, long spawned, double avgTripTime, double avgTripWait,
                          double p90TripTime, double travelTimeIndex, double throughputPerMin,
                          double emergencyAvgResponse, int emergencyResponses, long reroutes,
                          double utilization, int closedRoads, int activeIncidents) {
}
