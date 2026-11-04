package com.synapsecity.simulation.agents;

import com.synapsecity.simulation.roads.RoadSegment;
import com.synapsecity.simulation.routing.RouteResult;
import com.synapsecity.simulation.signals.Intersection;
import com.synapsecity.simulation.signals.Movement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * An autonomous vehicle agent.
 *
 * <p>Position is lane-based: on a segment the vehicle is at {@code s} metres (front bumper) along
 * {@code lane}; inside a junction it is at {@code cs} metres along its {@link Movement} connector.
 * The planned route is a list of directed segments whose last element is the destination
 * segment; {@code routeIndex} points at the segment currently being driven.</p>
 */
public final class Vehicle {

    public record DecisionRecord(double time, Decision decision, int ref, double value) {
    }

    private final int id;
    private final VehicleType type;
    private final DriverProfile driver;
    private final double spawnTime;

    VehicleState state = VehicleState.SPAWNING;
    double stateSince;

    // Kinematics.
    RoadSegment segment;
    int lane;
    double s;
    double speed;
    double accel;
    Movement connector;
    double cs;

    // Junction reservation (held from commit until the rear clears the box).
    Movement movement;
    Intersection committedAt;
    // A long vehicle on a short road can reserve the next junction while its rear is still leaving
    // this one, so the older reservation is kept here until it clears.
    Movement clearing;
    Intersection clearingAt;

    // Plan.
    List<RoadSegment> route = List.of();
    int routeIndex;
    double destS;
    RouteResult lastRoute;
    double plannedCost;
    int routeChanges;
    double lastRouteCheck;
    boolean replanRequested;
    double lastReplanTime = -1e9;

    // Trip statistics (taxis start a new trip per fare, so per-trip bases are tracked).
    double tripStart;
    double tripWaitBase;
    double tripDistanceBase;
    double waitTime;
    double stoppedFor;
    double distance;
    double freeFlowEstimate;

    // Multi-stop agents (buses, deliveries) and emergency missions.
    final List<Stop> stops = new ArrayList<>();
    int stopIndex;
    double dwellRemaining;
    boolean persistent;
    int missionRoadId = -1;
    double dispatchTime = -1;
    boolean sirenActive;

    // Explainability.
    Decision decision = Decision.SPAWNING;
    int decisionRef = -1;
    double decisionValue;
    private final Deque<DecisionRecord> history = new ArrayDeque<>();

    // Render pose (world-space centre of the vehicle).
    double x;
    double y;
    double heading;
    double fade;

    public Vehicle(int id, VehicleType type, DriverProfile driver, double spawnTime) {
        this.id = id;
        this.type = type;
        this.driver = driver;
        this.spawnTime = spawnTime;
        this.stateSince = spawnTime;
        this.lastRouteCheck = spawnTime;
        this.tripStart = spawnTime;
    }

    /** Validated state change; throws on an edge not defined by {@link VehicleState}. */
    public void transitionTo(VehicleState next, double time) {
        if (next == state) {
            return;
        }
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("Vehicle " + id + ": illegal transition " + state + " -> " + next);
        }
        state = next;
        stateSince = time;
    }

    void setDecision(Decision d, int ref, double value, double time) {
        boolean changed = d != decision || ref != decisionRef;
        decision = d;
        decisionRef = ref;
        decisionValue = value;
        if (changed) {
            history.addFirst(new DecisionRecord(time, d, ref, value));
            while (history.size() > 8) {
                history.removeLast();
            }
        }
    }

    public void recordDecision(Decision d, int ref, double value, double time) {
        setDecision(d, ref, value, time);
    }

    public void assignMovement(Intersection ix, Movement m) {
        if (movement != null) {
            if (clearingAt != null) {
                clearingAt.release(this);
            }
            clearing = movement;
            clearingAt = committedAt;
        }
        this.committedAt = ix;
        this.movement = m;
    }

    public void requestReplan() {
        replanRequested = true;
    }

    public boolean isInConnector() {
        return connector != null;
    }

    public boolean onFinalSegment() {
        return routeIndex >= route.size() - 1;
    }

    public RoadSegment nextSegment() {
        return routeIndex + 1 < route.size() ? route.get(routeIndex + 1) : null;
    }

    public RoadSegment destinationSegment() {
        return route.isEmpty() ? segment : route.get(route.size() - 1);
    }

    /** Remaining planned segments including the current one. */
    public List<RoadSegment> remainingRoute() {
        if (route.isEmpty()) {
            return List.of();
        }
        return route.subList(Math.min(routeIndex, route.size()), route.size());
    }

    // ------------------------------------------------------------------ accessors

    public int id() {
        return id;
    }

    public VehicleType type() {
        return type;
    }

    public DriverProfile driver() {
        return driver;
    }

    public double length() {
        return type.length();
    }

    public VehicleState state() {
        return state;
    }

    public double stateSince() {
        return stateSince;
    }

    public RoadSegment segment() {
        return segment;
    }

    public int lane() {
        return lane;
    }

    public double s() {
        return s;
    }

    public double cs() {
        return cs;
    }

    public double speed() {
        return speed;
    }

    public double accel() {
        return accel;
    }

    public Movement connector() {
        return connector;
    }

    public Movement movement() {
        return movement;
    }

    public Intersection committedAt() {
        return committedAt;
    }

    public Movement clearing() {
        return clearing;
    }

    public List<RoadSegment> route() {
        return Collections.unmodifiableList(route);
    }

    public int routeIndex() {
        return routeIndex;
    }

    public double destS() {
        return destS;
    }

    public RouteResult lastRoute() {
        return lastRoute;
    }

    public double plannedCost() {
        return plannedCost;
    }

    public int routeChanges() {
        return routeChanges;
    }

    public double spawnTime() {
        return spawnTime;
    }

    public double tripStart() {
        return tripStart;
    }

    /** Time stopped during the current trip (taxis start a new trip per fare). */
    public double tripWaitTime() {
        return waitTime - tripWaitBase;
    }

    public double tripDistance() {
        return distance - tripDistanceBase;
    }

    public double waitTime() {
        return waitTime;
    }

    public double stoppedFor() {
        return stoppedFor;
    }

    public double distance() {
        return distance;
    }

    public double freeFlowEstimate() {
        return freeFlowEstimate;
    }

    public List<Stop> stops() {
        return Collections.unmodifiableList(stops);
    }

    public int stopIndex() {
        return stopIndex;
    }

    public boolean isPersistent() {
        return persistent;
    }

    public int missionRoadId() {
        return missionRoadId;
    }

    public double dispatchTime() {
        return dispatchTime;
    }

    public boolean sirenActive() {
        return sirenActive;
    }

    public Decision decision() {
        return decision;
    }

    public int decisionRef() {
        return decisionRef;
    }

    public double decisionValue() {
        return decisionValue;
    }

    public List<DecisionRecord> history() {
        return List.copyOf(history);
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double heading() {
        return heading;
    }

    public double fade() {
        return fade;
    }
}
