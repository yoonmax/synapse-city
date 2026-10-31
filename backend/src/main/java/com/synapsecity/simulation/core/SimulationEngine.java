package com.synapsecity.simulation.core;

import com.synapsecity.simulation.agents.DemandModel;
import com.synapsecity.simulation.agents.DemandProfile;
import com.synapsecity.simulation.agents.FleetManager;
import com.synapsecity.simulation.agents.TrafficContext;
import com.synapsecity.simulation.agents.TrafficLanes;
import com.synapsecity.simulation.agents.Vehicle;
import com.synapsecity.simulation.agents.VehicleDynamics;
import com.synapsecity.simulation.agents.VehicleType;
import com.synapsecity.simulation.events.EventLog;
import com.synapsecity.simulation.events.EventType;
import com.synapsecity.simulation.events.Severity;
import com.synapsecity.simulation.metrics.LiveMetrics;
import com.synapsecity.simulation.metrics.MetricsCollector;
import com.synapsecity.simulation.metrics.PerformanceStats;
import com.synapsecity.simulation.roads.CityGenerator;
import com.synapsecity.simulation.roads.CityModel;
import com.synapsecity.simulation.roads.Road;
import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadNode;
import com.synapsecity.simulation.roads.RoadSegment;
import com.synapsecity.simulation.routing.Router;
import com.synapsecity.simulation.routing.RoutingAlgorithm;
import com.synapsecity.simulation.signals.AdaptiveController;
import com.synapsecity.simulation.signals.FixedTimeController;
import com.synapsecity.simulation.signals.Intersection;
import com.synapsecity.simulation.signals.SignalController;
import com.synapsecity.simulation.signals.SignalMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The authoritative traffic simulation. Single-threaded and fixed-timestep: {@link #step()}
 * advances exactly {@link SimulationConfig#DT} simulated seconds. The engine has no notion of
 * wall-clock time, pausing or speed multipliers; those belong to whoever drives it.
 *
 * <p>Tick order: scripted scenario actions, incident expiry, spawning, emergency preemption
 * requests, signal detection and control, vehicle decisions (IDM + junction commitment),
 * integration, lane/connector transfers, spacing enforcement, lifecycle (arrival, state machine,
 * replanning), removal, segment statistics, render poses, metrics.</p>
 */
public final class SimulationEngine implements TrafficContext {

    public static final double INCIDENT_DURATION = 150;
    public static final double INCIDENT_SPEED_FACTOR = 0.22;

    private static final SignalController FIXED = new FixedTimeController();
    private static final SignalController ADAPTIVE = new AdaptiveController();

    private final SimulationConfig config;
    private final CityModel city;
    private final RoadGraph graph;
    private final SimulationClock clock = new SimulationClock(SimulationConfig.DT);
    private final SplittableRandom rng;
    private final TrafficLanes lanes;
    private final List<Intersection> intersections = new ArrayList<>();
    private final Intersection[] byNode;
    private final Router router;
    private final VehicleDynamics dynamics;
    private final FleetManager fleet;
    private final MetricsCollector metrics = new MetricsCollector();
    private final EventLog events = new EventLog();
    private final List<ScheduledAction> script = new ArrayList<>();
    private int scriptCursor;
    private final List<Incident> incidents = new ArrayList<>();
    private int nextIncidentId = 1;
    private SignalMode signalMode;
    private String scenarioName;

    public SimulationEngine(SimulationConfig config) {
        this(config, CityGenerator.generate(config.seed(), config.layout()));
    }

    /** Builds an engine over an existing city. The city's road graph becomes owned by this engine. */
    public SimulationEngine(SimulationConfig config, CityModel city) {
        this.config = config;
        this.city = city;
        this.graph = city.graph();
        this.rng = new SplittableRandom(config.seed() ^ 0x5DEECE66DL);
        this.lanes = new TrafficLanes(graph);
        this.byNode = new Intersection[graph.nodeCount()];
        boolean[] signalized = new boolean[graph.nodeCount()];
        for (RoadNode n : graph.nodes()) {
            Intersection ix = new Intersection(n);
            intersections.add(ix);
            byNode[n.id()] = ix;
            signalized[n.id()] = ix.isSignalized();
        }
        this.router = new Router(graph, signalized, config.routingAlgorithm());
        this.dynamics = new VehicleDynamics(this);
        this.fleet = new FleetManager(this, dynamics, new DemandModel(graph, city.radius(), config.demandProfile()),
                config.targetVehicles());
        installSignals(config.signalMode(), true);
        emit(EventType.CITY_GENERATED, Severity.INFO, city.name() + " online",
                String.format("%s layout, seed %d: %d junctions, %d road segments, %d buildings",
                        city.layout(), city.seed(), graph.nodeCount(), graph.segments().size(), city.buildings().size()),
                -1, -1, -1, Double.NaN, Double.NaN);
    }

    private void installSignals(SignalMode mode, boolean initial) {
        this.signalMode = mode;
        double minX = Double.MAX_VALUE;
        for (RoadNode n : graph.nodes()) {
            minX = Math.min(minX, n.position().x());
        }
        for (Intersection ix : intersections) {
            // Fixed plans are offset by x-position (12 m/s progression) so eastbound platoons meet greens.
            double offset = initial && mode == SignalMode.FIXED ? (ix.node().position().x() - minX) / 12.0 : -1;
            ix.setController(mode == SignalMode.FIXED ? FIXED : ADAPTIVE, time(), offset);
        }
    }

    // ------------------------------------------------------------------ stepping

    public void step() {
        long t0 = System.nanoTime();
        clock.advance();
        double t = time();
        runScript(t);
        expireIncidents(t);
        fleet.spawnTraffic();
        fleet.requestPreemptions();
        for (Intersection ix : intersections) {
            ix.measure(lanes);
            ix.updateSignal(SimulationConfig.DT, t);
        }
        dynamics.decideAll();
        for (Vehicle v : fleet.vehicles()) {
            dynamics.integrate(v, SimulationConfig.DT);
        }
        dynamics.resolveTransfers();
        dynamics.enforceSpacing();
        fleet.updateLifecycles();
        fleet.removeFinished();
        updateSegmentStats();
        for (Vehicle v : fleet.vehicles()) {
            dynamics.updatePose(v);
        }
        metrics.recordStep(System.nanoTime() - t0);
        metrics.maybeSample(t, this::liveMetrics);
    }

    /** Runs whole steps until {@code seconds} of simulated time have elapsed. */
    public void runFor(double seconds) {
        long steps = Math.round(seconds / SimulationConfig.DT);
        for (long i = 0; i < steps; i++) {
            step();
        }
    }

    private void runScript(double t) {
        while (scriptCursor < script.size() && script.get(scriptCursor).time() <= t + 1e-9) {
            ScheduledAction a = script.get(scriptCursor++);
            a.action().accept(this);
            emit(EventType.SCENARIO_STEP, Severity.NOTICE, a.label(), "Scripted at t=" + (int) a.time() + " s",
                    -1, -1, -1, Double.NaN, Double.NaN);
        }
    }

    private void expireIncidents(double t) {
        incidents.removeIf(inc -> {
            if (t < inc.endTime()) {
                return false;
            }
            graph.segment(inc.segmentId()).clearIncident();
            emit(EventType.INCIDENT_CLEARED, Severity.INFO, "Incident " + inc.id() + " cleared",
                    "Lane capacity restored on road " + inc.roadId(), -1, inc.roadId(), -1, inc.x(), inc.y());
            return true;
        });
    }

    private void updateSegmentStats() {
        for (RoadSegment seg : graph.segments()) {
            int count = 0;
            double sum = 0;
            for (int l = 0; l < seg.lanes(); l++) {
                for (Vehicle v : lanes.lane(seg, l)) {
                    count++;
                    sum += v.speed();
                }
            }
            seg.setLoad(count);
            double target = count > 0 ? sum / count : seg.effectiveSpeedLimit();
            double obs = seg.observedSpeed();
            seg.setObservedSpeed(obs + (target - obs) * (count > 0 ? 0.008 : 0.01));
            double deficit = 1 - Math.min(1, seg.observedSpeed() / seg.speedLimit());
            double raw = count == 0 ? 0 : Math.max(0, Math.min(1, 0.65 * deficit + 0.8 * seg.loadRatio()));
            seg.setCongestion(seg.congestion() + (raw - seg.congestion()) * 0.05);
        }
    }

    // ------------------------------------------------------------------ commands

    public void setTargetVehicles(int target) {
        int clamped = Math.max(0, Math.min(SimulationConfig.MAX_VEHICLES, target));
        fleet.setTargetVehicles(clamped);
        emit(EventType.DEMAND_CHANGED, Severity.INFO, "Traffic demand set to " + clamped + " vehicles",
                "Spawner converges on the new target", -1, -1, -1, Double.NaN, Double.NaN);
    }

    public void setDemandProfile(DemandProfile profile) {
        fleet.demand().setProfile(profile);
        emit(EventType.DEMAND_CHANGED, Severity.INFO, "Demand profile: " + profile,
                "Origin/destination weighting updated", -1, -1, -1, Double.NaN, Double.NaN);
    }

    /** Spawns up to {@code count} extra vehicles immediately; emergency units are dispatched instead. */
    public int addVehicles(int count, VehicleType type) {
        int added = 0;
        if (type == VehicleType.EMERGENCY) {
            for (int i = 0; i < count; i++) {
                if (dispatchEmergency(null) != null) {
                    added++;
                }
            }
            return added;
        }
        for (int attempt = 0; attempt < count * 6 && added < count; attempt++) {
            if (fleet.spawnRegular(type == VehicleType.BUS ? VehicleType.CAR : type) != null) {
                added++;
            }
        }
        emit(EventType.VEHICLES_ADDED, Severity.INFO, added + " " + type.name().toLowerCase() + " vehicle(s) added",
                added < count ? "Network too dense to place all " + count : "Inserted into free gaps",
                -1, -1, -1, Double.NaN, Double.NaN);
        return added;
    }

    public boolean closeRoad(int roadId) {
        Road road = graph.road(roadId);
        if (road.isClosed()) {
            return false;
        }
        road.setClosed(true);
        int affected = fleet.flagBlockedRoutes();
        Vec2 mid = road.a().position().lerp(road.b().position(), 0.5);
        emit(EventType.ROAD_CLOSED, Severity.WARNING, "Road " + roadId + " closed",
                affected + " vehicle(s) must replan around the closure", -1, roadId, -1, mid.x(), mid.y());
        if (affected > 0) {
            emit(EventType.MASS_REROUTE, Severity.NOTICE, affected + " vehicles rerouting",
                    "Routes recomputed with " + router.algorithm(), -1, roadId, -1, mid.x(), mid.y());
        }
        return true;
    }

    public boolean openRoad(int roadId) {
        Road road = graph.road(roadId);
        if (!road.isClosed()) {
            return false;
        }
        road.setClosed(false);
        Vec2 mid = road.a().position().lerp(road.b().position(), 0.5);
        emit(EventType.ROAD_REOPENED, Severity.INFO, "Road " + roadId + " reopened",
                "Vehicles will pick it up at their next route check", -1, roadId, -1, mid.x(), mid.y());
        return true;
    }

    /**
     * Creates a crash on {@code roadId} (or, if null, on the busiest major segment), cutting its
     * speed to {@value #INCIDENT_SPEED_FACTOR} of the limit for {@value #INCIDENT_DURATION} s.
     */
    public Incident createIncident(Integer roadId) {
        RoadSegment seg;
        if (roadId != null) {
            Road road = graph.road(roadId);
            seg = road.backward() != null && road.backward().load() > road.forward().load() ? road.backward() : road.forward();
        } else {
            seg = fleet.busiestMajorSegment();
        }
        if (seg == null || seg.isClosed() || seg.hasIncident()) {
            return null;
        }
        double t = time();
        seg.setIncident(INCIDENT_SPEED_FACTOR, t + INCIDENT_DURATION);
        Vec2 p = seg.lanePoint(0, seg.length() * 0.55);
        Incident inc = new Incident(nextIncidentId++, seg.road().id(), seg.id(), t, t + INCIDENT_DURATION, p.x(), p.y());
        incidents.add(inc);
        emit(EventType.INCIDENT_CREATED, Severity.WARNING, "Incident on road " + seg.road().id(),
                String.format("Speed cut to %.0f%% for %.0f s", INCIDENT_SPEED_FACTOR * 100, INCIDENT_DURATION),
                -1, seg.road().id(), -1, p.x(), p.y());
        return inc;
    }

    /** Dispatches an emergency unit to {@code roadId}, the latest incident, or a far arterial. */
    public Vehicle dispatchEmergency(Integer roadId) {
        RoadSegment target;
        if (roadId != null) {
            target = graph.road(roadId).forward();
        } else if (!incidents.isEmpty()) {
            target = graph.segment(incidents.get(incidents.size() - 1).segmentId());
        } else {
            Vec2 station = graph.node(city.emergencyStationNode()).position();
            List<RoadSegment> far = new ArrayList<>();
            for (RoadSegment s : graph.segments()) {
                if (!s.isClosed() && s.length() > 40) {
                    far.add(s);
                }
            }
            far.sort((a, b) -> {
                int c = Double.compare(b.start().distance(station), a.start().distance(station));
                return c != 0 ? c : Integer.compare(a.id(), b.id());
            });
            target = far.isEmpty() ? null : far.get(rng.nextInt(Math.min(10, far.size())));
        }
        Vehicle v = fleet.dispatchEmergency(target);
        if (v == null) {
            emit(EventType.EMERGENCY_DISPATCHED, Severity.WARNING, "Dispatch failed",
                    "No free road space at the station or target unreachable", -1, -1, -1, Double.NaN, Double.NaN);
        }
        return v;
    }

    public void setSignalMode(SignalMode mode) {
        if (mode == signalMode) {
            return;
        }
        installSignals(mode, false);
        emit(EventType.SIGNAL_MODE_CHANGED, Severity.NOTICE, "Signals switched to " + mode,
                mode == SignalMode.ADAPTIVE ? "Queue/wait pressure control with min/max green and anti-starvation"
                        : "Pre-timed cyclic plan", -1, -1, -1, Double.NaN, Double.NaN);
    }

    public void setRoutingAlgorithm(RoutingAlgorithm algorithm) {
        router.setAlgorithm(algorithm);
        emit(EventType.ROUTING_CHANGED, Severity.INFO, "Routing algorithm: " + algorithm,
                "Applies to all future route computations", -1, -1, -1, Double.NaN, Double.NaN);
    }

    /** Installs a scenario script; actions run at their scheduled simulated times. */
    public void loadScript(String name, List<ScheduledAction> actions) {
        this.scenarioName = name;
        script.clear();
        script.addAll(actions);
        script.sort((a, b) -> Double.compare(a.time(), b.time()));
        scriptCursor = 0;
        emit(EventType.SCENARIO_LOADED, Severity.NOTICE, "Scenario: " + name,
                actions.size() + " scripted step(s), seed " + config.seed(), -1, -1, -1, Double.NaN, Double.NaN);
    }

    // ------------------------------------------------------------------ observation

    public LiveMetrics liveMetrics() {
        int queued = 0;
        for (Intersection ix : intersections) {
            if (ix.isSignalized()) {
                queued += ix.totalQueue();
            }
        }
        return metrics.computeLive(fleet.vehicles(), graph, time(), queued, incidents.size());
    }

    public PerformanceStats performance() {
        return metrics.performance(router.calls(), router.averageMicros(), router.averageExplored(),
                router.failures(), dynamics.safetyClamps());
    }

    /**
     * FNV-style hash over the complete dynamic state (vehicle kinematics and states, signal timing).
     * Two runs are reproducible exactly when their hashes match tick for tick.
     */
    public long stateHash() {
        long h = 0xcbf29ce484222325L;
        h = mix(h, clock.tick());
        for (Vehicle v : fleet.vehicles()) {
            h = mix(h, v.id());
            h = mix(h, v.segment() == null ? -1 : v.segment().id());
            h = mix(h, v.lane());
            h = mix(h, Math.round(v.s() * 1000));
            h = mix(h, Math.round(v.cs() * 1000));
            h = mix(h, Math.round(v.speed() * 1000));
            h = mix(h, v.state().ordinal());
            h = mix(h, v.routeIndex());
        }
        for (Intersection ix : intersections) {
            h = mix(h, ix.currentPhase());
            h = mix(h, ix.stage().ordinal());
            h = mix(h, Math.round(ix.stageElapsed() * 1000));
        }
        return h;
    }

    private static long mix(long h, long x) {
        h ^= x;
        return h * 0x100000001b3L;
    }

    // ------------------------------------------------------------------ TrafficContext

    @Override
    public double time() {
        return clock.time();
    }

    @Override
    public double dt() {
        return SimulationConfig.DT;
    }

    @Override
    public CityModel city() {
        return city;
    }

    @Override
    public RoadGraph graph() {
        return graph;
    }

    @Override
    public TrafficLanes lanes() {
        return lanes;
    }

    @Override
    public Intersection intersectionAt(RoadNode node) {
        return byNode[node.id()];
    }

    @Override
    public List<Intersection> intersections() {
        return Collections.unmodifiableList(intersections);
    }

    @Override
    public Router router() {
        return router;
    }

    @Override
    public MetricsCollector metrics() {
        return metrics;
    }

    @Override
    public SplittableRandom rng() {
        return rng;
    }

    @Override
    public void emit(EventType type, Severity severity, String title, String detail,
                     int vehicleId, int roadId, int nodeId, double x, double y) {
        events.add(clock.tick(), time(), type, severity, title, detail, vehicleId, roadId, nodeId, x, y);
    }

    // ------------------------------------------------------------------ accessors

    public long tick() {
        return clock.tick();
    }

    public SimulationConfig config() {
        return config;
    }

    public List<Vehicle> vehicles() {
        return fleet.vehicles();
    }

    public Vehicle vehicle(int id) {
        return fleet.vehicle(id);
    }

    public FleetManager fleet() {
        return fleet;
    }

    public VehicleDynamics dynamics() {
        return dynamics;
    }

    public Intersection intersection(int nodeId) {
        return byNode[nodeId];
    }

    public EventLog events() {
        return events;
    }

    public List<Incident> incidents() {
        return Collections.unmodifiableList(incidents);
    }

    public SignalMode signalMode() {
        return signalMode;
    }

    public int targetVehicles() {
        return fleet.targetVehicles();
    }

    public DemandProfile demandProfile() {
        return fleet.demand().profile();
    }

    public String scenarioName() {
        return scenarioName;
    }
}
