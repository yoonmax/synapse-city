package com.synapsecity.simulation.roads;

/** Functional road classification. Determines lanes, speed limit and visual width. */
public enum RoadClass {
    /** Multi-lane boulevards: 2 lanes per direction, 54 km/h, central median. */
    ARTERIAL(2, 15.0, 1.2),
    /** Distributor roads: 1 lane per direction, 45 km/h. */
    COLLECTOR(1, 12.5, 0.0),
    /** Neighbourhood streets: 1 lane per direction, 36 km/h. */
    LOCAL(1, 10.0, 0.0);

    private final int lanesPerDirection;
    private final double speedLimit;
    private final double medianWidth;

    RoadClass(int lanesPerDirection, double speedLimit, double medianWidth) {
        this.lanesPerDirection = lanesPerDirection;
        this.speedLimit = speedLimit;
        this.medianWidth = medianWidth;
    }

    public int lanesPerDirection() {
        return lanesPerDirection;
    }

    /** Speed limit in metres per second. */
    public double speedLimit() {
        return speedLimit;
    }

    public double medianWidth() {
        return medianWidth;
    }
}
