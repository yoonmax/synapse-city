package com.synapsecity.simulation.roads;

import com.synapsecity.simulation.core.Vec2;

/**
 * A directed edge of the road graph: one travel direction of a {@link Road}.
 *
 * <p>Lane geometry runs from {@link #start()} to {@link #end()}, which are inset from the node
 * centres by each node's clearance radius. Routing uses {@link #graphLength()} (centre to centre)
 * so that the A* straight-line heuristic stays admissible.</p>
 *
 * <p>Lane 0 is the kerb (rightmost) lane; lane {@code lanes-1} is next to the centreline.</p>
 */
public final class RoadSegment {

    /** Average road space one queued vehicle occupies, used for jam-density capacity. */
    public static final double JAM_SPACING = 7.0;

    private final int id;
    private final Road road;
    private final RoadNode from;
    private final RoadNode to;
    private final int lanes;
    private final double speedLimit;

    private Vec2 start;
    private Vec2 end;
    private Vec2 direction;
    private Vec2 right;
    private double length;
    private double graphLength;

    // Dynamic road conditions, written by the engine.
    private double incidentSpeedFactor = 1.0;
    private double incidentUntil = -1;
    private double observedSpeed;
    private int load;
    private double congestion;

    RoadSegment(int id, Road road, RoadNode from, RoadNode to) {
        this.id = id;
        this.road = road;
        this.from = from;
        this.to = to;
        this.lanes = road.lanesPerDirection();
        this.speedLimit = road.roadClass().speedLimit();
        this.observedSpeed = speedLimit;
    }

    void computeGeometry() {
        Vec2 a = from.position();
        Vec2 b = to.position();
        direction = b.sub(a).normalized();
        right = direction.rightNormal();
        Vec2 centreA = a.add(direction.scale(from.radius()));
        Vec2 centreB = b.sub(direction.scale(to.radius()));
        start = centreA;
        end = centreB;
        length = Math.max(1.0, start.distance(end));
        graphLength = a.distance(b);
    }

    /** Lateral offset of a lane centre to the right of the road centreline. */
    public double laneOffset(int lane) {
        double median = road.roadClass().medianWidth() / 2;
        return median + (lanes - lane - 0.5) * Road.LANE_WIDTH;
    }

    /** World position of a point {@code s} metres along the given lane. */
    public Vec2 lanePoint(int lane, double s) {
        return start.add(direction.scale(s)).add(right.scale(laneOffset(lane)));
    }

    public int id() {
        return id;
    }

    public Road road() {
        return road;
    }

    public RoadNode from() {
        return from;
    }

    public RoadNode to() {
        return to;
    }

    public int lanes() {
        return lanes;
    }

    public double speedLimit() {
        return speedLimit;
    }

    public RoadClass roadClass() {
        return road.roadClass();
    }

    public Vec2 start() {
        return start;
    }

    public Vec2 end() {
        return end;
    }

    public Vec2 direction() {
        return direction;
    }

    public Vec2 right() {
        return right;
    }

    /** Drivable lane length in metres. */
    public double length() {
        return length;
    }

    /** Node-centre to node-centre distance, used as routing distance. */
    public double graphLength() {
        return graphLength;
    }

    /** Jam-density capacity in vehicles. */
    public double capacity() {
        return Math.max(1.0, lanes * length / JAM_SPACING);
    }

    public boolean isClosed() {
        return road.isClosed();
    }

    /** Free-flow speed after incident restrictions. */
    public double effectiveSpeedLimit() {
        return speedLimit * incidentSpeedFactor;
    }

    public double freeFlowTime() {
        return graphLength / speedLimit;
    }

    public boolean hasIncident() {
        return incidentSpeedFactor < 1.0;
    }

    public double incidentSpeedFactor() {
        return incidentSpeedFactor;
    }

    public double incidentUntil() {
        return incidentUntil;
    }

    public void setIncident(double speedFactor, double until) {
        this.incidentSpeedFactor = speedFactor;
        this.incidentUntil = until;
    }

    public void clearIncident() {
        this.incidentSpeedFactor = 1.0;
        this.incidentUntil = -1;
    }

    /** Exponentially smoothed mean speed of vehicles on the segment (relaxes to the limit when empty). */
    public double observedSpeed() {
        return observedSpeed;
    }

    public void setObservedSpeed(double observedSpeed) {
        this.observedSpeed = observedSpeed;
    }

    /** Number of vehicles currently on the segment's lanes. */
    public int load() {
        return load;
    }

    public void setLoad(int load) {
        this.load = load;
    }

    public double loadRatio() {
        return load / capacity();
    }

    /** Smoothed congestion intensity in [0, 1] used by the heatmap. */
    public double congestion() {
        return congestion;
    }

    public void setCongestion(double congestion) {
        this.congestion = congestion;
    }

    public void resetDynamicState() {
        clearIncident();
        observedSpeed = speedLimit;
        load = 0;
        congestion = 0;
    }

    @Override
    public String toString() {
        return "Seg#" + id + "(" + from.id() + "->" + to.id() + ")";
    }
}
