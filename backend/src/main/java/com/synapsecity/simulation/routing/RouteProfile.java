package com.synapsecity.simulation.routing;

/**
 * How strongly a router weighs congestion relative to free-flow travel time.
 * {@code congestionWeight = 0} plans on free-flow times only.
 */
public enum RouteProfile {
    STANDARD(1.0),
    /** Emergency vehicles weigh congestion 2.5x so they actively steer around queues. */
    EMERGENCY(2.5),
    FREE_FLOW(0.0);

    private final double congestionWeight;

    RouteProfile(double congestionWeight) {
        this.congestionWeight = congestionWeight;
    }

    public double congestionWeight() {
        return congestionWeight;
    }
}
