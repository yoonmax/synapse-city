package com.synapsecity.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsecity.session.ClientView;
import com.synapsecity.session.RateMeter;
import com.synapsecity.session.SimulationSession;
import com.synapsecity.simulation.core.SimulationConfig;
import com.synapsecity.simulation.metrics.MetricsSample;
import com.synapsecity.simulation.scenarios.ComparisonRunner;
import com.synapsecity.simulation.scenarios.ScenarioPreset;
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
 * JVM host for a {@link SimulationSession}: one shared simulation served over WebSocket.
 *
 * <p>Threading model: exactly one thread ("sim-loop") ever touches the session. WebSocket and
 * REST threads enqueue commands or callables; the loop drains them at tick boundaries, advances
 * the fixed-step engine, then publishes snapshots. Because commands land between whole steps, the
 * engine stays deterministic regardless of network timing.</p>
 */
@Service
public class SimulationService implements SimulationSession.Host {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);
    public static final long LOOP_MILLIS = 50;
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
    private final SimulationSession session = new SimulationSession(this);

    // Owned by the sim-loop thread.
    private long lastWallNanos;
    private long lastStateNanos;
    private long lastDetailNanos;
    private String cityJson;
    private int lastFrameBytes;
    private final RateMeter frameRate = new RateMeter();
    private final RateMeter stepRate = new RateMeter();

    public SimulationService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void start() {
        loop.execute(session::start);
        lastWallNanos = System.nanoTime();
        loop.scheduleAtFixedRate(this::safeTick, LOOP_MILLIS, LOOP_MILLIS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        loop.shutdownNow();
        experiments.shutdownNow();
    }

    // ------------------------------------------------------------------ session registry

    public void register(ClientSession client) {
        sessions.add(client);
        inbox.add(() -> {
            client.send(json(new Messages.Hello("hello", Messages.PROTOCOL_VERSION, SimulationConfig.DT,
                    BinaryFrameEncoder.VEHICLE_STRIDE)));
            client.send(json(new Messages.ScenariosMessage("scenarios", ProtocolMapper.scenarios())));
            client.send(cityJson);
            session.onClientConnected(client.view);
            sendState(client, baseState());
            client.sendBinary(session.encodeFrame());
        });
    }

    public void unregister(ClientSession client) {
        sessions.remove(client);
    }

    public void submit(ClientSession client, ClientCommand command) {
        inbox.add(() -> {
            try {
                session.apply(client.view, command);
            } catch (CommandException e) {
                client.send(json(new Messages.ErrorMessage("error", e.getMessage())));
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
            session.apply(new ClientView(), new ClientCommand.Pause());
        }
    }

    private void tick() {
        Runnable r;
        while ((r = inbox.poll()) != null) {
            r.run();
        }
        long now = System.nanoTime();
        int steps = session.advance((now - lastWallNanos) / 1e9);
        lastWallNanos = now;
        stepRate.add(steps, now);
        if (steps > 0) {
            broadcastFrame(now);
        }
        if (now - lastStateNanos >= STATE_INTERVAL_NANOS) {
            lastStateNanos = now;
            Messages.StateMessage base = baseState();
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
        byte[] frame = session.encodeFrame();
        lastFrameBytes = frame.length;
        frameRate.add(1, now);
        for (ClientSession s : sessions) {
            s.sendBinary(frame);
        }
    }

    // ------------------------------------------------------------------ session callbacks

    @Override
    public void onCityChanged() {
        cityJson = json(session.city());
        for (ClientSession s : sessions) {
            s.view.reset();
            s.send(cityJson);
        }
    }

    @Override
    public void onFrameDirty() {
        broadcastFrame(System.nanoTime());
    }

    @Override
    public void onDetailsRequested(ClientView view) {
        for (ClientSession s : sessions) {
            if (s.view == view) {
                sendDetails(s);
            }
        }
    }

    @Override
    public void onComparisonRequested(SimulationConfig base, ScenarioPreset preset, double duration) {
        if (!comparisonRunning.compareAndSet(false, true)) {
            throw new CommandException("A comparison is already running");
        }
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

    private Messages.StateMessage baseState() {
        long now = System.nanoTime();
        double steps = stepRate.perSecond(now);
        return session.baseState(new Messages.ServerStats(sessions.size(), lastFrameBytes, frameRate.perSecond(now),
                steps, steps * SimulationConfig.DT), comparisonRunning.get());
    }

    private void sendState(ClientSession s, Messages.StateMessage base) {
        s.send(json(session.stateFor(s.view, base)));
    }

    private void sendDetails(ClientSession s) {
        for (Object message : session.detailsFor(s.view)) {
            s.send(json(message));
        }
    }

    private void broadcastJson(Object message) {
        String payload = json(message);
        for (ClientSession s : sessions) {
            s.send(payload);
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
        return onSimThread(this::baseState);
    }

    public List<MetricsSample> series() {
        return onSimThread(() -> session.engine().metrics().series());
    }
}
