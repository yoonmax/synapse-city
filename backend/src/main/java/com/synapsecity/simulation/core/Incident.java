package com.synapsecity.simulation.core;

/** A temporary capacity-reducing event (crash, breakdown) on one directed segment. */
public record Incident(int id, int roadId, int segmentId, double startTime, double endTime, double x, double y) {
}
