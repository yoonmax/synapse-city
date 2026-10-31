package com.synapsecity.simulation.events;

/**
 * Something notable that happened inside the simulation. References are -1 when not applicable;
 * {@code x}/{@code y} locate the event in the city (NaN when it has no location).
 */
public record SimEvent(long id, long tick, double time, EventType type, Severity severity, String title,
                       String detail, int vehicleId, int roadId, int nodeId, double x, double y) {
}
