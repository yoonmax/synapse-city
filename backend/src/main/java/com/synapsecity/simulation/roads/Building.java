package com.synapsecity.simulation.roads;

/**
 * Oriented rectangular building footprint. {@code rotation} is the CCW angle (radians) of the
 * footprint's width axis; {@code variant} is a stable per-building hash used for facade detail.
 */
public record Building(int id, double x, double y, double width, double depth, double height,
                       double rotation, BuildingStyle style, District district, int variant) {
}
