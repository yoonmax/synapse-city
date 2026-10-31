package com.synapsecity.simulation.agents;

import com.synapsecity.simulation.core.Vec2;
import com.synapsecity.simulation.events.EventType;
import com.synapsecity.simulation.events.Severity;
import com.synapsecity.simulation.roads.RoadClass;
import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadSegment;
import com.synapsecity.simulation.routing.RouteProfile;
import com.synapsecity.simulation.routing.RouteResult;
import com.synapsecity.simulation.routing.Router;
import com.synapsecity.simulation.signals.Intersection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Owns the vehicle population and every lifecycle decision: demand-driven spawning, initial
 * routing, periodic and event-driven replanning, arrival handling per vehicle class, dwell at
 * stops, emergency missions, gridlock relief and removal.
 */
public final class FleetManager {

    public static final double GRIDLOCK_TIMEOUT = 180;
    public static final double REPLAN_INTERVAL = 20;
    public static final double EMERGENCY_REPLAN_INTERVAL = 4;
    public static final double STUCK_REPLAN_AFTER = 45;
    /** A periodic reroute must save at least 15% and 3 s of remaining travel time. */
    public static final double REROUTE_RATIO = 0.85;
    public static final double REROUTE_MARGIN = 3.0;
    public static final double PREEMPT_RANGE = 160;
    private static final int MAX_SPAWNS_PER_TICK = 6;

    private final TrafficContext ctx;
    private final VehicleDynamics dynamics;
    private final DemandModel demand;
    private final List<Vehicle> vehicles = new ArrayList<>();
    private final Map<Integer, Vehicle> byId = new HashMap<>();
    private int nextId = 1;
    private int targetVehicles;
    private double lastGridlockEvent = -1e9;
    private int gridlockSinceEvent;

    public FleetManager(TrafficContext ctx, VehicleDynamics dynamics, DemandModel demand, int targetVehicles) {
        this.ctx = ctx;
        this.dynamics = dynamics;
        this.demand = demand;
        this.targetVehicles = targetVehicles;
    }

    // ------------------------------------------------------------------ spawning

    /** Spawns towards the target population (expected fill time constant of 2.5 s) and keeps buses running. */
    public void spawnTraffic() {
        SplittableRandom rng = ctx.rng();
        int regular = 0;
        for (Vehicle v : vehicles) {
            if (!v.persistent && v.type() != VehicleType.EMERGENCY && v.state != VehicleState.DESPAWNING) {
                regular++;
            }
        }
        int deficit = targetVehicles - regular;
        if (deficit > 0 && !demand.isEmpty()) {
            double expected = deficit * ctx.dt() / 2.5;
            int n = (int) expected;
            if (rng.nextDouble() < expected - n) {
                n++;
            }
            n = Math.min(n, MAX_SPAWNS_PER_TICK);
            for (int i = 0; i < n; i++) {
                double r = rng.nextDouble();
                VehicleType type = r < 0.70 ? VehicleType.CAR : r < 0.86 ? VehicleType.TAXI : VehicleType.DELIVERY;
                spawnRegular(type);
            }
        }
        ensureBuses();
    }

    public Vehicle spawnRegular(VehicleType type) {
        SplittableRandom rng = ctx.rng();
        RoadSegment origin = demand.sampleOrigin(rng);
        RoadSegment dest = demand.sampleDestination(rng);
        if (origin == null || dest == null || dest == origin || dest.length() < 20) {
            return null;
        }
        Vehicle v = placeNew(type, origin, rng);
        if (v == null) {
            return null;
        }
        double destS = dest.length() * rng.nextDouble(0.35, 0.65);
        if (type == VehicleType.DELIVERY) {
            RoadSegment second = demand.sampleDestination(rng);
            v.stops.add(new Stop(dest, destS, 9));
            if (second != null && second != dest && second.length() >= 20) {
                v.stops.add(new Stop(second, second.length() * rng.nextDouble(0.35, 0.65), 9));
            }
        }
        if (!planRoute(v, dest, destS)) {
            return null;
        }
        register(v);
        return v;
    }

    /** Finds an insertion gap on {@code seg} and builds an unregistered vehicle there. */
    private Vehicle placeNew(VehicleType type, RoadSegment seg, SplittableRandom rng) {
        if (seg.isClosed() || seg.length() < DemandModel.MIN_SPAWN_LENGTH) {
            return null;
        }
        int lane = rng.nextInt(seg.lanes());
        double lo = Math.max(seg.length() * 0.2, type.length() + 1);
        double hi = seg.length() - 22;
        if (hi <= lo) {
            return null;
        }
        double front = rng.nextDouble(lo, hi);
        double speed = seg.effectiveSpeedLimit() * 0.5;
        DriverProfile driver = sampleDriver(type, rng);
        if (!ctx.lanes().hasInsertionGap(seg, lane, front, type.length(), speed)) {
            return null;
        }
        Vehicle v = new Vehicle(nextId, type, driver, ctx.time());
        v.segment = seg;
        v.lane = lane;
        v.s = front;
        v.speed = speed;
        return v;
    }

    private void register(Vehicle v) {
        nextId++;
        vehicles.add(v);
        byId.put(v.id(), v);
        ctx.lanes().insert(v);
        dynamics.updatePose(v);
        ctx.metrics().recordSpawn();
    }

    private static DriverProfile sampleDriver(VehicleType type, SplittableRandom rng) {
        double speed = 1 + Math.max(-0.15, Math.min(0.15, rng.nextGaussian() * 0.06));
        double headway = rng.nextDouble(0.85, 1.2);
        double accel = rng.nextDouble(0.85, 1.15);
        double awarenessRoll = rng.nextDouble(0.3, 0.9);
        double awareness = switch (type) {
            case TAXI, EMERGENCY -> 1.0;
            case DELIVERY -> 0.8;
            case BUS -> 0.0;
            case CAR -> awarenessRoll;
        };
        return new DriverProfile(speed, headway, accel, awareness);
    }

    private void ensureBuses() {
        List<Integer> stops = ctx.city().busStopSegments();
        if (stops.size() < 3) {
            return;
        }
        int wanted = Math.max(2, Math.min(8, targetVehicles / 60));
        if (targetVehicles == 0) {
            wanted = 0;
        }
        int buses = 0;
        boolean[] lineInUse = new boolean[8];
        for (Vehicle v : vehicles) {
            if (v.persistent && v.state != VehicleState.DESPAWNING) {
                buses++;
                lineInUse[v.missionRoadId] = true;
            }
        }
        if (buses >= wanted) {
            return;
        }
        int line = 0;
        while (line < lineInUse.length && lineInUse[line]) {
            line++;
        }
        if (line >= wanted) {
            return;
        }
        RoadGraph g = ctx.graph();
        int n = stops.size();
        List<Stop> loop = new ArrayList<>();
        for (int j = 0; j < 4; j++) {
            RoadSegment seg = g.segment(stops.get((line + j * Math.max(1, n / 4) + j) % n));
            if (loop.stream().noneMatch(st -> st.segment() == seg)) {
                loop.add(new Stop(seg, seg.length() * 0.55, 7));
            }
        }
        if (loop.size() < 2) {
            return;
        }
        Vehicle bus = placeNew(VehicleType.BUS, loop.get(0).segment(), ctx.rng());
        if (bus == null || bus.s >= loop.get(0).s() - 2) {
            return;
        }
        bus.persistent = true;
        bus.missionRoadId = line; // bus line number
        bus.stops.addAll(loop);
        if (!planRoute(bus, loop.get(0).segment(), loop.get(0).s())) {
            return;
        }
        register(bus);
    }

    // ------------------------------------------------------------------ emergency missions

    /**
     * Dispatches an emergency vehicle from the station to {@code target}. Returns null if no road
     * near the station has room or the target is unreachable.
     */
    public Vehicle dispatchEmergency(RoadSegment target) {
        if (target == null || target.isClosed()) {
            return null;
        }
        RoadGraph g = ctx.graph();
        Vec2 station = g.node(ctx.city().emergencyStationNode()).position();
        List<RoadSegment> candidates = new ArrayList<>(g.segments());
        candidates.sort((a, b) -> {
            int c = Double.compare(a.start().distance(station), b.start().distance(station));
            return c != 0 ? c : Integer.compare(a.id(), b.id());
        });
        SplittableRandom rng = ctx.rng();
        int tried = 0;
        for (RoadSegment seg : candidates) {
            if (tried >= 14) {
                break;
            }
            if (seg == target || seg.isClosed() || seg.length() < DemandModel.MIN_SPAWN_LENGTH) {
                continue;
            }
            tried++;
            Vehicle v = placeNew(VehicleType.EMERGENCY, seg, rng);
            if (v == null) {
                continue;
            }
            v.sirenActive = true;
            v.missionRoadId = target.road().id();
            v.dispatchTime = ctx.time();
            if (!planRoute(v, target, Math.max(8, target.length() * 0.5))) {
                return null;
            }
            register(v);
            RouteResult r = v.lastRoute;
            ctx.emit(EventType.EMERGENCY_DISPATCHED, Severity.CRITICAL, "Emergency unit " + v.id() + " dispatched",
                    String.format("Route %.0f m, est. %.0f s via %s (%d nodes explored)",
                            r == null ? 0 : r.distance(), v.plannedCost, ctx.router().algorithm(),
                            r == null ? 0 : r.exploredNodes()),
                    v.id(), target.road().id(), -1, v.x, v.y);
            return v;
        }
        return null;
    }

    /** Asks signals ahead of active emergency vehicles to switch to their approach. */
    public void requestPreemptions() {
        double t = ctx.time();
        for (Vehicle v : vehicles) {
            if (!v.sirenActive || v.connector != null || v.segment == null || v.onFinalSegment()) {
                continue;
            }
            Intersection ix = ctx.intersectionAt(v.segment.to());
            if (!ix.isSignalized() || v.segment.length() - v.s > PREEMPT_RANGE) {
                continue;
            }
            if (ix.requestPreemption(ix.approachFor(v.segment), v.id(), t)) {
                v.setDecision(Decision.PREEMPTING, ix.node().id(), 0, t);
                ctx.emit(EventType.SIGNAL_PREEMPTED, Severity.INFO, "Signal priority at junction " + ix.node().id(),
                        "Emergency unit " + v.id() + " requested a green for its approach",
                        v.id(), -1, ix.node().id(), ix.node().position().x(), ix.node().position().y());
            }
        }
    }

    // ------------------------------------------------------------------ routing

    private RouteProfile profileFor(Vehicle v) {
        return v.type() == VehicleType.EMERGENCY ? RouteProfile.EMERGENCY : RouteProfile.STANDARD;
    }

    /** Sets a new destination and plans a route to it from the current segment. */
    boolean planRoute(Vehicle v, RoadSegment dest, double destS) {
        RoadSegment cur = v.segment;
        double t = ctx.time();
        if (cur == dest && v.s < destS - 5) {
            v.route = new ArrayList<>(List.of(cur));
            v.routeIndex = 0;
            v.destS = destS;
            v.lastRoute = null;
            v.plannedCost = 0;
            v.freeFlowEstimate = (destS - v.s) / cur.speedLimit();
            return true;
        }
        Router router = ctx.router();
        RouteProfile profile = profileFor(v);
        RouteResult r = router.route(cur.to(), dest.from(), profile, ctx.graph().reverseOf(cur));
        if (!r.found()) {
            r = router.route(cur.to(), dest.from(), profile, null);
        }
        if (!r.found()) {
            return false;
        }
        List<RoadSegment> route = new ArrayList<>(r.segments().size() + 2);
        route.add(cur);
        route.addAll(r.segments());
        route.add(dest);
        v.route = route;
        v.routeIndex = 0;
        v.destS = destS;
        v.lastRoute = r;
        v.plannedCost = r.cost();
        v.lastRouteCheck = t;
        double free = (cur.length() - v.s) / cur.speedLimit() + destS / dest.speedLimit();
        for (RoadSegment s : r.segments()) {
            free += s.freeFlowTime();
        }
        v.freeFlowEstimate = free;
        return true;
    }

    /**
     * Re-plans from the end of the current segment. Forced replans (closures, blocked routes) always
     * adopt a found route; periodic ones only switch when the saving clears the hysteresis threshold.
     */
    public boolean replan(Vehicle v, boolean force) {
        if (v.connector != null || v.movement != null || v.segment == null || v.state == VehicleState.DESPAWNING) {
            return false;
        }
        RoadSegment cur = v.segment;
        RoadSegment dest = v.destinationSegment();
        double t = ctx.time();
        if (v.onFinalSegment() && v.s < v.destS) {
            return false;
        }
        if (dest.isClosed()) {
            return retarget(v);
        }
        Router router = ctx.router();
        RouteProfile profile = profileFor(v);
        RouteResult r = router.route(cur.to(), dest.from(), profile, ctx.graph().reverseOf(cur));
        if (!r.found()) {
            r = router.route(cur.to(), dest.from(), profile, null);
        }
        if (!r.found()) {
            return force && retarget(v);
        }
        double oldCost = router.pathCost(v.route, v.routeIndex + 1, v.route.size() - 1, profile);
        double newCost = r.cost();
        if (sameTail(r.segments(), v.route, v.routeIndex + 1)) {
            v.plannedCost = newCost;
            return false;
        }
        if (!force && !(newCost < oldCost * REROUTE_RATIO - REROUTE_MARGIN)) {
            return false;
        }
        List<RoadSegment> route = new ArrayList<>(r.segments().size() + 2);
        route.add(cur);
        route.addAll(r.segments());
        route.add(dest);
        v.route = route;
        v.routeIndex = 0;
        v.lastRoute = r;
        v.plannedCost = newCost;
        v.routeChanges++;
        v.lastReplanTime = t;
        ctx.metrics().recordReroute();
        v.transitionTo(VehicleState.REPLANNING, t);
        double saved = Double.isFinite(oldCost) ? oldCost - newCost : 0;
        v.setDecision(Decision.REROUTED, -1, saved, t);
        return true;
    }

    private static boolean sameTail(List<RoadSegment> path, List<RoadSegment> route, int from) {
        int tailLen = route.size() - 1 - from;
        if (tailLen != path.size()) {
            return false;
        }
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i) != route.get(from + i)) {
                return false;
            }
        }
        return true;
    }

    /** Picks a new reachable destination after the old one became unreachable; strands the vehicle otherwise. */
    private boolean retarget(Vehicle v) {
        double t = ctx.time();
        if (v.persistent && !v.stops.isEmpty()) {
            // Buses skip a stop that has become unreachable and continue the line.
            for (int k = 1; k <= v.stops.size(); k++) {
                Stop next = v.stops.get((v.stopIndex + k) % v.stops.size());
                if (!next.segment().isClosed() && next.segment() != v.segment && planRoute(v, next.segment(), next.s())) {
                    v.stopIndex = (v.stopIndex + k) % v.stops.size();
                    v.routeChanges++;
                    ctx.metrics().recordReroute();
                    v.transitionTo(VehicleState.REPLANNING, t);
                    v.setDecision(Decision.REROUTED, -1, 0, t);
                    return true;
                }
            }
        }
        if (v.type() != VehicleType.EMERGENCY && !v.persistent) {
            for (int attempt = 0; attempt < 4; attempt++) {
                RoadSegment dest = demand.sampleDestination(ctx.rng());
                if (dest != null && dest != v.segment && dest.length() >= 20
                        && planRoute(v, dest, dest.length() * 0.5)) {
                    v.routeChanges++;
                    ctx.metrics().recordReroute();
                    v.transitionTo(VehicleState.REPLANNING, t);
                    v.setDecision(Decision.REROUTED, -1, 0, t);
                    return true;
                }
            }
        }
        ctx.emit(EventType.VEHICLE_STRANDED, Severity.WARNING, "Vehicle " + v.id() + " stranded",
                "No route to any destination; removed from the network", v.id(), -1, -1, v.x, v.y);
        ctx.metrics().recordAbandoned();
        despawn(v);
        return false;
    }

    /** Flags every vehicle whose remaining route uses a closed road; returns how many were affected. */
    public int flagBlockedRoutes() {
        int affected = 0;
        for (Vehicle v : vehicles) {
            if (v.state != VehicleState.DESPAWNING && routeBlocked(v)) {
                v.replanRequested = true;
                affected++;
            }
        }
        return affected;
    }

    private static boolean routeBlocked(Vehicle v) {
        for (int i = v.routeIndex + 1; i < v.route.size(); i++) {
            if (v.route.get(i).isClosed()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Per-tick bookkeeping after movement: timers, box release, arrival, state derivation, replanning. */
    public void updateLifecycles() {
        double t = ctx.time();
        double dt = ctx.dt();
        for (int i = 0; i < vehicles.size(); i++) {
            Vehicle v = vehicles.get(i);
            VehicleState st = v.state;
            if (st != VehicleState.DWELLING && st != VehicleState.DESPAWNING && st != VehicleState.SPAWNING) {
                if (v.speed < 0.5) {
                    v.waitTime += dt;
                    v.stoppedFor += dt;
                } else {
                    v.stoppedFor = 0;
                }
            }
            if (v.movement != null && v.connector == null && v.segment == v.movement.out()
                    && v.s - v.length() >= -0.3) {
                v.committedAt.release(v);
                v.movement = null;
                v.committedAt = null;
            }
            if (st == VehicleState.DESPAWNING) {
                continue;
            }
            if (st == VehicleState.DWELLING) {
                v.dwellRemaining -= dt;
                if (v.dwellRemaining <= 0) {
                    finishDwell(v);
                }
                continue;
            }
            if (v.connector == null && v.onFinalSegment() && v.s >= v.destS - 1.5
                    && (v.speed < 1.2 || v.s >= v.destS)) {
                arrive(v);
                continue;
            }
            deriveState(v, t);
            if (v.connector == null && v.movement == null) {
                maybeReplan(v, t);
            }
            if (v.stoppedFor > GRIDLOCK_TIMEOUT) {
                relieveGridlock(v, t);
            }
        }
    }

    private void deriveState(Vehicle v, double t) {
        if (v.state == VehicleState.SPAWNING && t - v.stateSince < 0.8) {
            return;
        }
        VehicleState target;
        if (v.connector != null) {
            target = VehicleState.CROSSING_INTERSECTION;
        } else if (v.onFinalSegment()) {
            target = v.destS - v.s < VehicleDynamics.DESTINATION_ZONE ? VehicleState.ARRIVING : VehicleState.CRUISING;
        } else if (v.segment.length() - v.s < 60) {
            target = v.speed < 0.5 ? VehicleState.WAITING_AT_SIGNAL : VehicleState.APPROACHING_INTERSECTION;
        } else {
            target = VehicleState.CRUISING;
        }
        v.transitionTo(target, t);
    }

    private void maybeReplan(Vehicle v, double t) {
        if (v.state == VehicleState.ARRIVING) {
            return;
        }
        if (v.replanRequested) {
            v.replanRequested = false;
            replan(v, true);
            return;
        }
        double interval = v.type() == VehicleType.EMERGENCY ? EMERGENCY_REPLAN_INTERVAL : REPLAN_INTERVAL;
        if (t - v.lastRouteCheck >= interval) {
            v.lastRouteCheck = t;
            if (routeBlocked(v)) {
                replan(v, true);
                return;
            }
            if (ctx.rng().nextDouble() < v.driver().rerouteAwareness()) {
                replan(v, false);
            }
            return;
        }
        if (v.stoppedFor > STUCK_REPLAN_AFTER && t - v.lastReplanTime > 40) {
            v.lastReplanTime = t;
            replan(v, false);
        }
    }

    private void arrive(Vehicle v) {
        double t = ctx.time();
        if (v.state != VehicleState.ARRIVING) {
            v.transitionTo(VehicleState.ARRIVING, t);
        }
        switch (v.type()) {
            case EMERGENCY -> {
                double response = t - v.dispatchTime;
                ctx.metrics().recordEmergencyResponse(response);
                v.sirenActive = false;
                ctx.emit(EventType.EMERGENCY_ARRIVED, Severity.NOTICE, "Emergency unit " + v.id() + " on scene",
                        String.format("Response time %.1f s (%d route changes)", response, v.routeChanges),
                        v.id(), v.missionRoadId, -1, v.x, v.y);
                dwell(v, 15);
            }
            case BUS -> {
                v.stopIndex++;
                dwell(v, 7);
            }
            case DELIVERY -> {
                if (v.stopIndex < v.stops.size() - 1) {
                    v.stopIndex++;
                    dwell(v, 9);
                } else {
                    completeTrip(v);
                    despawn(v);
                }
            }
            case TAXI -> {
                completeTrip(v);
                if (ctx.rng().nextDouble() < 0.6) {
                    dwell(v, 3.5);
                } else {
                    despawn(v);
                }
            }
            case CAR -> {
                completeTrip(v);
                despawn(v);
            }
        }
    }

    private void dwell(Vehicle v, double seconds) {
        v.dwellRemaining = seconds;
        v.transitionTo(VehicleState.DWELLING, ctx.time());
        v.setDecision(Decision.DWELLING, -1, seconds, ctx.time());
    }

    private void finishDwell(Vehicle v) {
        double t = ctx.time();
        RoadSegment dest = null;
        double destS = 0;
        switch (v.type()) {
            case BUS -> {
                Stop s = v.stops.get(v.stopIndex % v.stops.size());
                dest = s.segment();
                destS = s.s();
            }
            case DELIVERY -> {
                Stop s = v.stops.get(v.stopIndex);
                dest = s.segment();
                destS = s.s();
            }
            case TAXI -> {
                RoadSegment d = demand.sampleDestination(ctx.rng());
                if (d != null && d != v.segment && d.length() >= 20) {
                    dest = d;
                    destS = d.length() * 0.5;
                    v.tripStart = t;
                    v.tripWaitBase = v.waitTime;
                    v.tripDistanceBase = v.distance;
                }
            }
            default -> {
            }
        }
        if (dest == null || dest.isClosed()) {
            despawn(v);
            return;
        }
        v.transitionTo(VehicleState.REPLANNING, t);
        if (!planRoute(v, dest, destS)) {
            despawn(v);
        }
    }

    private void completeTrip(Vehicle v) {
        double t = ctx.time();
        ctx.metrics().recordTrip(v.type(), t, t - v.tripStart, v.waitTime - v.tripWaitBase,
                v.distance - v.tripDistanceBase, v.freeFlowEstimate);
    }

    private void relieveGridlock(Vehicle v, double t) {
        ctx.metrics().recordAbandoned();
        gridlockSinceEvent++;
        if (t - lastGridlockEvent > 20) {
            ctx.emit(EventType.GRIDLOCK_RELIEF, Severity.WARNING, "Gridlock relief",
                    gridlockSinceEvent + " vehicle(s) stationary for over " + (int) GRIDLOCK_TIMEOUT
                            + " s were removed to break a deadlock",
                    v.id(), -1, -1, v.x, v.y);
            lastGridlockEvent = t;
            gridlockSinceEvent = 0;
        }
        despawn(v);
    }

    public void despawn(Vehicle v) {
        if (v.state != VehicleState.DESPAWNING) {
            v.transitionTo(VehicleState.DESPAWNING, ctx.time());
            v.setDecision(Decision.DESPAWNING, -1, 0, ctx.time());
        }
    }

    /** Removes vehicles whose despawn fade has completed. */
    public void removeFinished() {
        double t = ctx.time();
        vehicles.removeIf(v -> {
            if (v.state != VehicleState.DESPAWNING || t - v.stateSince < 1.0) {
                return false;
            }
            detach(v);
            return true;
        });
    }

    private void detach(Vehicle v) {
        if (v.connector != null) {
            v.connector.vehicles().remove(v);
            v.connector = null;
        } else {
            ctx.lanes().remove(v);
        }
        if (v.committedAt != null) {
            v.committedAt.forget(v);
        }
        if (v.segment != null) {
            ctx.intersectionAt(v.segment.to()).removeClaim(v);
        }
        byId.remove(v.id());
    }

    /** Removes everyone immediately (used by tests and resets). */
    public void clear() {
        for (Vehicle v : vehicles) {
            detach(v);
        }
        vehicles.clear();
    }

    // ------------------------------------------------------------------ accessors

    public List<Vehicle> vehicles() {
        return Collections.unmodifiableList(vehicles);
    }

    public Vehicle vehicle(int id) {
        return byId.get(id);
    }

    public int targetVehicles() {
        return targetVehicles;
    }

    public void setTargetVehicles(int targetVehicles) {
        this.targetVehicles = targetVehicles;
    }

    public DemandModel demand() {
        return demand;
    }

    /** Picks the most loaded major segment, or a seeded random arterial if the network is empty. */
    public RoadSegment busiestMajorSegment() {
        RoadSegment best = null;
        for (RoadSegment s : ctx.graph().segments()) {
            if (s.roadClass() == RoadClass.LOCAL || s.isClosed() || s.length() < 30) {
                continue;
            }
            if (best == null || s.load() > best.load()) {
                best = s;
            }
        }
        if (best != null && best.load() > 0) {
            return best;
        }
        List<RoadSegment> majors = new ArrayList<>();
        for (RoadSegment s : ctx.graph().segments()) {
            if (s.roadClass() == RoadClass.ARTERIAL && !s.isClosed() && s.length() >= 30) {
                majors.add(s);
            }
        }
        return majors.isEmpty() ? best : majors.get(ctx.rng().nextInt(majors.size()));
    }
}
