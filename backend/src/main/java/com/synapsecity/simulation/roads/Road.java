package com.synapsecity.simulation.roads;

/**
 * A physical road between two nodes. A two-way road owns one directed {@link RoadSegment}
 * per direction; closures apply to the physical road (both directions).
 */
public final class Road {

    public static final double LANE_WIDTH = 3.3;

    private final int id;
    private final RoadNode a;
    private final RoadNode b;
    private final RoadClass roadClass;
    private RoadSegment forward;
    private RoadSegment backward;
    private boolean closed;

    Road(int id, RoadNode a, RoadNode b, RoadClass roadClass) {
        this.id = id;
        this.a = a;
        this.b = b;
        this.roadClass = roadClass;
    }

    public int id() {
        return id;
    }

    public RoadNode a() {
        return a;
    }

    public RoadNode b() {
        return b;
    }

    public RoadClass roadClass() {
        return roadClass;
    }

    /** Segment travelling a -> b. */
    public RoadSegment forward() {
        return forward;
    }

    /** Segment travelling b -> a, or {@code null} for a one-way road. */
    public RoadSegment backward() {
        return backward;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public int lanesPerDirection() {
        return roadClass.lanesPerDirection();
    }

    /** Half of the carriageway width, from centreline to kerb. */
    public double halfWidth() {
        return roadClass.medianWidth() / 2 + roadClass.lanesPerDirection() * LANE_WIDTH;
    }

    public double centreLength() {
        return a.position().distance(b.position());
    }

    void setForward(RoadSegment s) {
        this.forward = s;
    }

    void setBackward(RoadSegment s) {
        this.backward = s;
    }

    @Override
    public String toString() {
        return "Road#" + id + "(" + a.id() + "-" + b.id() + ")";
    }
}
