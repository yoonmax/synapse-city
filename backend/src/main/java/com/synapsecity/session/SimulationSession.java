package com.synapsecity.session;

import com.synapsecity.simulation.agents.Vehicle;
import com.synapsecity.simulation.core.SimulationConfig;
import com.synapsecity.simulation.core.SimulationEngine;
import com.synapsecity.simulation.events.SimEvent;
import com.synapsecity.simulation.metrics.MetricsSample;
import com.synapsecity.simulation.scenarios.ScenarioPreset;
import com.synapsecity.simulation.signals.Intersection;
import com.synapsecity.websocket.protocol.BinaryFrameEncoder;
import com.synapsecity.websocket.protocol.ClientCommand;
import com.synapsecity.websocket.protocol.CommandException;
import com.synapsecity.websocket.protocol.Messages;
import com.synapsecity.websocket.protocol.ProtocolMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * One running simulation and everything a host does with it: pacing the fixed-step engine against
 * the wall clock, applying validated commands, and building outbound messages.
 *
 * <p>This class has no threads, networking or server dependencies, so the same code drives both
 * hosts: {@code SimulationService} on the JVM (WebSocket) and {@code BrowserMain} in a browser Web
 * Worker (TeaVM). Callers must only touch a session from one thread.</p>
 */
public final class SimulationSession {

    /** What a host must do in response to session events. */
    public interface Host {

        /** The engine was replaced; clients need the new city. */
        void onCityChanged();

        /** The world moved outside normal pacing (single-stepping); publish a frame now. */
        void onFrameDirty();

        /** A client's selection changed; send it fresh details now. */
        void onDetailsRequested(ClientView view);

        void onComparisonRequested(SimulationConfig base, ScenarioPreset preset, double durationSeconds);
    }

    private static final int MAX_STEPS_PER_ADVANCE = 40;
    private static final double MAX_WALL_DT = 0.25;

    private final Host host;
    private SimulationEngine engine;
    private ScenarioPreset scenario;
    private boolean running = true;
    private double speed = 1.0;
    private double stepDebt;
    private int approachCount;
    private Messages.CityMessage city;

    public SimulationSession(Host host) {
        this.host = host;
    }

    /** Builds the default world. Separate from the constructor so hosts can choose their thread. */
    public void start() {
        install(new SimulationEngine(SimulationConfig.defaults()), null);
    }

    private void install(SimulationEngine next, ScenarioPreset preset) {
        engine = next;
        scenario = preset;
        stepDebt = 0;
        approachCount = 0;
        for (Intersection ix : engine.intersections()) {
            approachCount += ix.approaches().size();
        }
        city = ProtocolMapper.city(engine);
        host.onCityChanged();
    }

    /** Advances by however many whole fixed steps {@code wallDt} seconds call for; returns the step count. */
    public int advance(double wallDt) {
        if (!running) {
            return 0;
        }
        int steps = 0;
        stepDebt += Math.min(MAX_WALL_DT, wallDt) * speed;
        while (stepDebt >= SimulationConfig.DT && steps < MAX_STEPS_PER_ADVANCE) {
            engine.step();
            stepDebt -= SimulationConfig.DT;
            steps++;
        }
        if (steps == MAX_STEPS_PER_ADVANCE) {
            stepDebt = 0;
        }
        return steps;
    }

    public void apply(ClientView view, ClientCommand command) {
        switch (command) {
            case ClientCommand.Pause p -> running = false;
            case ClientCommand.Resume r -> running = true;
            case ClientCommand.Step s -> {
                for (int i = 0; i < s.ticks(); i++) {
                    engine.step();
                }
                host.onFrameDirty();
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
            case ClientCommand.DispatchEmergency d ->
                    engine.dispatchEmergency(d.roadId() == null ? null : checkRoad(d.roadId()));
            case ClientCommand.SetSignalMode m -> engine.setSignalMode(m.mode());
            case ClientCommand.SetRouting r -> engine.setRoutingAlgorithm(r.algorithm());
            case ClientCommand.SelectVehicle v -> {
                if (v.vehicleId() != null && engine.vehicle(v.vehicleId()) == null) {
                    throw new CommandException("Vehicle " + v.vehicleId() + " is no longer in the simulation");
                }
                view.selectedVehicle = v.vehicleId();
                host.onDetailsRequested(view);
            }
            case ClientCommand.InspectIntersection n -> {
                if (n.nodeId() != null && (n.nodeId() < 0 || n.nodeId() >= engine.graph().nodeCount())) {
                    throw new CommandException("Unknown junction " + n.nodeId());
                }
                view.inspectedNode = n.nodeId();
                host.onDetailsRequested(view);
            }
            case ClientCommand.Reset r ->
                    install(scenario != null ? scenario.createEngine() : new SimulationEngine(engine.config()), scenario);
            case ClientCommand.Regenerate g -> install(new SimulationEngine(engine.config().withSeed(g.seed())
                    .withLayout(g.layout()).withTargetVehicles(engine.targetVehicles())
                    .withSignalMode(engine.signalMode()).withRoutingAlgorithm(engine.router().algorithm())
                    .withDemandProfile(engine.demandProfile())), null);
            case ClientCommand.LoadScenario l -> install(l.scenario().createEngine(), l.scenario());
            case ClientCommand.RunComparison c -> host.onComparisonRequested(
                    engine.config().withTargetVehicles(engine.targetVehicles())
                            .withDemandProfile(engine.demandProfile())
                            .withRoutingAlgorithm(engine.router().algorithm()),
                    scenario, c.durationSeconds());
        }
    }

    private int checkRoad(int roadId) {
        if (roadId < 0 || roadId >= engine.graph().roads().size()) {
            throw new CommandException("Unknown road " + roadId);
        }
        return roadId;
    }

    /** Positions a new client's cursors so it receives recent history but not the whole log. */
    public void onClientConnected(ClientView view) {
        view.lastEventId = Math.max(0, engine.events().lastId() - 40);
        view.lastSampleTime = -1;
    }

    public byte[] encodeFrame() {
        return BinaryFrameEncoder.encode(engine, approachCount);
    }

    public Messages.StateMessage baseState(Messages.ServerStats server, boolean comparisonRunning) {
        return new Messages.StateMessage("state", engine.tick(), engine.time(), running, speed,
                engine.signalMode().name(), engine.router().algorithm().name(), engine.demandProfile().name(),
                engine.targetVehicles(), scenario == null ? null : scenario.name(), engine.config().seed(),
                engine.config().layout().name(), engine.liveMetrics(), engine.performance(), server,
                ProtocolMapper.incidents(engine), ProtocolMapper.closedRoads(engine), List.of(), List.of(),
                comparisonRunning);
    }

    /** The base state plus the events and samples this client has not seen, advancing its cursors. */
    public Messages.StateMessage stateFor(ClientView view, Messages.StateMessage base) {
        List<Messages.EventDto> events = new ArrayList<>();
        for (SimEvent e : engine.events().since(view.lastEventId)) {
            events.add(ProtocolMapper.event(e));
            view.lastEventId = e.id();
        }
        List<MetricsSample> series = new ArrayList<>();
        for (MetricsSample m : engine.metrics().series()) {
            if (m.time() > view.lastSampleTime) {
                series.add(m);
                view.lastSampleTime = m.time();
            }
        }
        return new Messages.StateMessage(base.type(), base.tick(), base.time(), base.running(), base.speed(),
                base.signalMode(), base.routing(), base.demandProfile(), base.targetVehicles(), base.scenario(),
                base.seed(), base.layout(), base.metrics(), base.perf(), base.server(), base.incidents(),
                base.closedRoads(), events, series, base.comparisonRunning());
    }

    /** Inspector messages this client should receive right now. */
    public List<Object> detailsFor(ClientView view) {
        List<Object> out = new ArrayList<>();
        if (view.selectedVehicle != null) {
            Vehicle v = engine.vehicle(view.selectedVehicle);
            if (v == null) {
                out.add(new Messages.AckMessage("vehicleGone", "SELECT_VEHICLE",
                        "Vehicle " + view.selectedVehicle + " left the network"));
                view.selectedVehicle = null;
            } else {
                out.add(ProtocolMapper.vehicle(engine, v));
            }
        }
        if (view.inspectedNode != null) {
            out.add(ProtocolMapper.intersection(engine, engine.intersection(view.inspectedNode)));
        }
        return out;
    }

    public SimulationEngine engine() {
        return engine;
    }

    public Messages.CityMessage city() {
        return city;
    }
}
