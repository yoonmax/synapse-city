package com.synapsecity.simulation.signals;

import com.synapsecity.simulation.roads.RoadSegment;

/** One incoming segment at an intersection, with its signal indication and live measurements. */
public final class Approach {

    private final int index;
    private final RoadSegment segment;
    private int phase;
    private LightColor color = LightColor.RED;

    // Measurements refreshed every tick.
    int queue;
    int demand;
    double totalWait;
    double maxWait;

    Approach(int index, RoadSegment segment) {
        this.index = index;
        this.segment = segment;
    }

    public int index() {
        return index;
    }

    public RoadSegment segment() {
        return segment;
    }

    public int phase() {
        return phase;
    }

    void setPhase(int phase) {
        this.phase = phase;
    }

    public LightColor color() {
        return color;
    }

    void setColor(LightColor color) {
        this.color = color;
    }

    /** Stopped vehicles (speed below 1 m/s) on the approach. */
    public int queue() {
        return queue;
    }

    /** Vehicles within the detection zone upstream of the stop line. */
    public int demand() {
        return demand;
    }

    public double totalWait() {
        return totalWait;
    }

    public double maxWait() {
        return maxWait;
    }

    /** Direction the traffic on this approach travels, as an angle. */
    public double headingAngle() {
        return segment.direction().angle();
    }
}
