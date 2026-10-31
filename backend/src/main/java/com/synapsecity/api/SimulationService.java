package com.synapsecity.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsecity.simulation.core.SimulationConfig;
import com.synapsecity.simulation.core.SimulationEngine;
import com.synapsecity.simulation.events.SimEvent;
import com.synapsecity.simulation.metrics.MetricsSample;
import com.synapsecity.simulation.scenarios.ComparisonRunner;
import com.synapsecity.simulation.scenarios.ScenarioPreset;
import com.synapsecity.simulation.signals.Intersection;
import com.synapsecity.websocket.protocol.BinaryFrameEncoder;
import com.synapsecity.websocket.protocol.ClientCommand;
import com.synapsecity.websocket.protocol.CommandException;
import com.synapsecity.websocket.protocol.Messages;
import com.synapsecity.websocket.protocol.ProtocolMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the live simulation and its driver loop.
 *
 * <p>Threading model: exactly one thread ("sim-loop") ever touches the engine. WebSocket and
 * REST threads enqueue commands or callables; the loop drains them at tick boundaries, then
 * advances the fixed-step engine by however many steps the wall clock and speed multiplier
 * require, then publishes snapshots. Because commands land between whole steps, the engine stays
 * deterministic regardless of network timing.</p>
 */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);
    public static final long LOOP_MILLIS = 50;
    private static final int MAX_STEPS_PER_LOOP = 40;
    private static final long STATE_INTERVAL_NANOS = 500_000_000L;
    private static final long DETAIL_INTERVAL_NANOS = 250_000_000L;

    private final ObjectMapper mapper;
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sim-loop");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService experiments = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "experiments");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentLinkedQueue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private final List<ClientSession> sessions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean comparisonRunning = new AtomicBoolean();

    // Owned by the sim-loop thread.
    private SimulationEngine engine;
    private ScenarioPreset scenario;
    private boolean running = true;
    private double speed = 1.0;
    private double stepDebt;
    private long lastWallNanos;
    private long lastStateNanos;
    private long lastDetailNanos;
    private String cityJson;
    private int approachCount;
    private int lastFrameBytes;
    private final RateMeter frameRate = new RateMeter();
    private final RateMeter stepRate = new RateMeter();

    public SimulationService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void start() {
        loop.execute(() -> install(new SimulationEngine(SimulationConfig.defaults()), null));
        lastWallNanos = System.nanoTime();
        loop.scheduleAtFixedRate(this::safeTick, LOOP_MILLIS, LOOP_MILLIS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        loop.shutdownNow();
        experiments.shutdownNow();
    }

    // ------------------------------------------------------------------ session registry

    public void register(ClientSession session) {
        sessions.add(session);
        inbox.add(() -> {
            session.send(json(new Messages.Hello("hello", Messages.PROTOCOL_VERSION, SimulationConfig.DT,
                    BinaryFrameEncoder.VEHICLE_STRIDE)));
            session.sendRaw(cityJson);
            session.lastEventId = Math.max(0, engine.events().lastId() - 40);
            session.lastSampleTime = -1;
            sendState(session, buildBaseState());
            session.sendBinary(BinaryFrameEncoder.encode(engine, approachCount));
        });
    }

    public void unregister(ClientSession session) {
        sessions.remove(session);
    }

    public void submit(ClientSession session, ClientCommand command) {
        inbox.add(() -> {
            try {
                apply(session, command);
            } catch (CommandException e) {
                session.send(json(new Messages.ErrorMessage("error", e.getMessage())));
            }
        });
    }

    /** Runs {@code task} on the simulation thread and waits for its result (used by REST endpoints). */
    public <T> T onSimThread(Callable<T> task) {
        FutureTask<T> future = new FutureTask<>(task);
        inbox.add(future);
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Simulation thread did not respond", e);
        }
    }

    // ------------------------------------------------------------------ loop

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            log.error("Simulation loop error; pausing simulation", t);
            running = false;
        }
    }

    private void tick() {
        Runnable r;
        while ((r = inbox.poll()) != null) {
            r.run();
        }
        long now = System.nanoTime();
        double wallDt = Math.min(0.25, (now - lastWallNanos) / 1e9);
        lastWallNanos = now;
        int steps = 0;
        if (running) {
            stepDebt += wallDt * speed;
            while (stepDebt >= SimulationConfig.DT && steps < MAX_STEPS_PER_LOOP) {
                engine.step();
                stepDebt -= SimulationConfig.DT;
                steps++;
            }
            if (steps == MAX_STEPS_PER_LOOP) {
                stepDebt = 0;
            }
        }
        stepRate.add(steps, now);
        if (steps > 0) {
            broadcastFrame(now);
        }
        if (now - lastStateNanos >= STATE_INTERVAL_NANOS) {
            lastStateNanos = now;
            Messages.StateMessage base = buildBaseState();
            for (ClientSession s : sessions) {
                sendState(s, base);
            }
        }
        if (now - lastDetailNanos >= DETAIL_INTERVAL_NANOS) {
            lastDetailNanos = now;
            for (ClientSession s : sessions) {
                sendDetails(s);
            }
        }
    }

    private void broadcastFrame(long now) {
        if (sessions.isEmpty()) {
            return;
        }
        byte[] frame = BinaryFrameEncoder.encode(engine, approachCount);
        lastFrameBytes = frame.length;
        frameRate.add(1, now);
        for (ClientSession s : sessions) {
            s.sendBinary(frame);
        }
    }

    private void install(SimulationEngine next, ScenarioPreset preset) {
        engine = next;
        scenario = preset;
        stepDebt = 0;
        approachCount = 0;
        for (Intersection ix : engine.intersections()) {
            approachCount += ix.approaches().size();
        }
        cityJson = json(ProtocolMapper.city(engine));
        for (ClientSession s : sessions) {
            s.selectedVehicle = null;
            s.inspectedNode = null;
            s.lastEventId = 0;
            s.lastSampleTime = -1;
            s.sendRaw(cityJson);
        }
    }

    // ------------------------------------------------------------------ commands

    private void apply(ClientSession session, ClientCommand command) {
        switch (command) {
            case ClientCommand.Pause p -> running = false;
            case ClientCommand.Resume r -> {
                running = true;
                lastWallNanos = System.nanoTime();
            }
            case ClientCommand.Step s -> {
                for (int i = 0; i < s.ticks(); i++) {
                    engine.step();
                }
                broadcastFrame(System.nanoTime());
            }
            case ClientCommand.SetSpeed s -> speed = s.value();
            case ClientCommand.AddVehicles a -> engine.addVehicles(a.count(), a.vehicleType());
            case ClientCommand.SetDensity d -> engine.setTargetVehicles(d.value());
            case ClientCommand.SetDemand d -> engine.setDemandProfile(d.profile());
            case ClientCommand.CloseRoad c -> engine.closeRoad(checkRoad(c.roadId()));
            case ClientCommand.OpenRoad o -> engine.openRoad(checkRoad(o.roadId()));
            case ClientCommand.CreateIncident i -> {
                if (engine.createIncident(i.roadId() == null ? null : checkRoad(i.roadId())) == null) {
                    throw new CommandException("Road is closed or already has an incident");
                }
            }
            case ClientCommand.DispatchEmergency d -> engine.dispatchEmergency(d.roadId() == null ? null : checkRoad(d.roadId()));
            case ClientCommand.SetSignalMode m -> engine.setSignalMode(m.mode());
            case ClientCommand.SetRouting r -> engine.setRoutingAlgorithm(r.algorithm());
            case ClientCommand.SelectVehicle v -> {
                if (v.vehicleId() != null && engine.vehicle(v.vehicleId()) == null) {
                    throw new CommandException("Vehicle " + v.vehicleId() + " is no longer in the simulation");
                }
                session.selectedVehicle = v.vehicleId();
                sendDetails(session);
            }
            case ClientCommand.InspectIntersection n -> {
                if (n.nodeId() != null && (n.nodeId() < 0 || n.nodeId() >= engine.graph().nodeCount())) {
                    throw new CommandException("Unknown junction " + n.nodeId());
                }
                session.inspectedNode = n.nodeId();
                sendDetails(session);
            }
            case ClientCommand.Reset r -> {
                SimulationConfig c = engine.config();
                install(scenario != null ? scenario.createEngine() : new SimulationEngine(c), scenario);
            }
            case ClientCommand.Regenerate g -> install(new SimulationEngine(engine.config().withSeed(g.seed())
                    .withLayout(g.layout()).withTargetVehicles(engine.targetVehicles())
                    .withSignalMode(engine.signalMode()).withRoutingAlgorithm(engine.router().algorithm())
                    .withDemandProfile(engine.demandProfile())), null);
            case ClientCommand.LoadScenario l -> install(l.scenario().createEngine(), l.scenario());
            case ClientCommand.RunComparison c -> startComparison(c.durationSeconds());
        }
    }

    private int checkRoad(int roadId) {
        if (roadId < 0 || roadId >= engine.graph().roads().size()) {
            throw new CommandException("Unknown road " + roadId);
        }
        return roadId;
    }

    private void startComparison(double duration) {
        if (!comparisonRunning.compareAndSet(false, true)) {
            throw new CommandException("A comparison is already running");
        }
        SimulationConfig base = engine.config().withTargetVehicles(engine.targetVehicles())
                .withDemandProfile(engine.demandProfile()).withRoutingAlgorithm(engine.router().algorithm());
        ScenarioPreset preset = scenario;
        broadcastJson(new Messages.ComparisonMessage("comparison", "running", 0,
                "Running fixed-time and adaptive control on seed " + base.seed(), null));
        experiments.execute(() -> {
            try {
                double[] lastSent = {0};
                var result = ComparisonRunner.compare(base, preset, duration, p -> {
                    if (p - lastSent[0] >= 0.05) {
                        lastSent[0] = p;
                        broadcastJson(new Messages.ComparisonMessage("comparison", "running", p, null, null));
                    }
                });
                broadcastJson(new Messages.ComparisonMessage("comparison", "done", 1, null, result));
            } catch (Exception e) {
                log.error("Comparison failed", e);
                broadcastJson(new Messages.ComparisonMessage("comparison", "error", 0, e.getMessage(), null));
            } finally {
                comparisonRunning.set(false);
            }
        });
    }

    /** Synchronous comparison for the REST API (runs on the experiments thread). */
    public ComparisonRunner.ComparisonResult compareBlocking(ScenarioPreset preset, double duration) throws Exception {
        return experiments.submit(() -> ComparisonRunner.compare(preset.config(), preset, duration, p -> { })).get();
    }

    // ------------------------------------------------------------------ outbound messages

    private Messages.StateMessage buildBaseState() {
        long now = System.nanoTime();
        Messages.ServerStats server = new Messages.ServerStats(sessions.size(), lastFrameBytes,
                frameRate.perSecond(now), stepRate.perSecond(now), stepRate.perSecond(now) * SimulationConfig.DT);
        return new Messages.StateMessage("state", engine.tick(), engine.time(), running, speed,
                engine.signalMode().name(), engine.router().algorithm().name(), engine.demandProfile().name(),
                engine.targetVehicles(), scenario == null ? null : scenario.name(), engine.config().seed(),
                engine.config().layout().name(), engine.liveMetrics(), engine.performance(), server,
                ProtocolMapper.incidents(engine), ProtocolMapper.closedRoads(engine), List.of(), List.of(),
                comparisonRunning.get());
    }

    private void sendState(ClientSession s, Messages.StateMessage base) {
        List<Messages.EventDto> events = new ArrayList<>();
        for (SimEvent e : engine.events().since(s.lastEventId)) {
            events.add(ProtocolMapper.event(e));
            s.lastEventId = e.id();
        }
        List<MetricsSample> series = new ArrayList<>();
        for (MetricsSample m : engine.metrics().series()) {
            if (m.time() > s.lastSampleTime) {
                series.add(m);
                s.lastSampleTime = m.time();
            }
        }
        s.send(json(new Messages.StateMessage(base.type(), base.tick(), base.time(), base.running(), base.speed(),
                base.signalMode(), base.routing(), base.demandProfile(), base.targetVehicles(), base.scenario(),
                base.seed(), base.layout(), base.metrics(), base.perf(), base.server(), base.incidents(),
                base.closedRoads(), events, series, base.comparisonRunning())));
    }

    private void sendDetails(ClientSession s) {
        if (s.selectedVehicle != null) {
            var v = engine.vehicle(s.selectedVehicle);
            if (v == null) {
                s.send(json(new Messages.AckMessage("vehicleGone", "SELECT_VEHICLE",
                        "Vehicle " + s.selectedVehicle + " left the network")));
                s.selectedVehicle = null;
            } else {
                s.send(json(ProtocolMapper.vehicle(engine, v)));
            }
        }
        if (s.inspectedNode != null) {
            s.send(json(ProtocolMapper.intersection(engine, engine.intersection(s.inspectedNode))));
        }
    }

    private void broadcastJson(Object message) {
        String payload = json(message);
        for (ClientSession s : sessions) {
            s.sendRaw(payload);
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ REST views (sim thread)

    public String cityJson() {
        return onSimThread(() -> cityJson);
    }

    public Messages.StateMessage stateSnapshot() {
        return onSimThread(this::buildBaseState);
    }

    public List<MetricsSample> series() {
        return onSimThread(() -> engine.metrics().series());
    }

    /** Events-per-second style counter over a sliding two-second window. */
    private static final class RateMeter {
        private final long[] times = new long[256];
        private final int[] counts = new int[256];
        private int cursor;

        void add(int n, long now) {
            times[cursor] = now;
            counts[cursor] = n;
            cursor = (cursor + 1) % times.length;
        }

        double perSecond(long now) {
            long window = 2_000_000_000L;
            int total = 0;
            for (int i = 0; i < times.length; i++) {
                if (times[i] != 0 && now - times[i] <= window) {
                    total += counts[i];
                }
            }
            return Math.round(total / 2.0 * 10) / 10.0;
        }
    }
}
