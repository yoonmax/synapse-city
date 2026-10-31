package com.synapsecity.simulation.scenarios;

import com.synapsecity.simulation.agents.DemandProfile;
import com.synapsecity.simulation.core.ScheduledAction;
import com.synapsecity.simulation.core.SimulationConfig;
import com.synapsecity.simulation.core.SimulationEngine;
import com.synapsecity.simulation.core.Vec2;
import com.synapsecity.simulation.roads.CityLayout;
import com.synapsecity.simulation.roads.Road;
import com.synapsecity.simulation.roads.RoadClass;
import com.synapsecity.simulation.routing.RoutingAlgorithm;
import com.synapsecity.simulation.signals.SignalMode;

import java.util.List;

/**
 * Reproducible experiment presets: a stored seed + configuration + a timed script. Replaying a
 * preset always yields the same city, the same demand and the same scripted disruptions.
 */
public enum ScenarioPreset {

    RUSH_HOUR("Rush Hour", "Commuters stream from residential rings into downtown; demand surges at t=90 s.",
            new SimulationConfig(2041, CityLayout.GRID, 560, SignalMode.FIXED, RoutingAlgorithm.A_STAR, DemandProfile.COMMUTE)),
    GRIDLOCK("Gridlock", "Dense demand focused on the core, two crashes and a closure on central arterials.",
            new SimulationConfig(77, CityLayout.GRID, 760, SignalMode.FIXED, RoutingAlgorithm.A_STAR, DemandProfile.CENTRAL)),
    EMERGENCY("Emergency", "Incidents across a radial city; emergency units must cut through congested rings.",
            new SimulationConfig(1309, CityLayout.RADIAL, 480, SignalMode.ADAPTIVE, RoutingAlgorithm.A_STAR, DemandProfile.COMMUTE)),
    ROAD_FAILURE("Road Failure", "The busiest arterial fails at t=60 s; watch the network reroute around it.",
            new SimulationConfig(4242, CityLayout.ORGANIC, 440, SignalMode.ADAPTIVE, RoutingAlgorithm.A_STAR, DemandProfile.BALANCED)),
    SMART_SIGNALS("Smart Signals", "Commuter load for a controlled fixed-time vs adaptive comparison on one seed.",
            new SimulationConfig(9001, CityLayout.GRID, 520, SignalMode.ADAPTIVE, RoutingAlgorithm.A_STAR, DemandProfile.COMMUTE));

    private final String title;
    private final String description;
    private final SimulationConfig config;

    ScenarioPreset(String title, String description, SimulationConfig config) {
        this.title = title;
        this.description = description;
        this.config = config;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public SimulationConfig config() {
        return config;
    }

    public List<ScheduledAction> script() {
        return switch (this) {
            case RUSH_HOUR -> List.of(
                    new ScheduledAction(90, "Peak demand surge", e -> e.setTargetVehicles(680)));
            case GRIDLOCK -> List.of(
                    new ScheduledAction(30, "Crash on a central arterial", e -> e.createIncident(null)),
                    new ScheduledAction(45, "Second crash downtown", e -> e.createIncident(null)),
                    new ScheduledAction(60, "Central arterial closed", e -> e.closeRoad(centralArterial(e))));
            case EMERGENCY -> List.of(
                    new ScheduledAction(25, "Collision reported", e -> e.createIncident(null)),
                    new ScheduledAction(30, "Unit dispatched to collision", e -> e.dispatchEmergency(null)),
                    new ScheduledAction(100, "Second collision", e -> e.createIncident(null)),
                    new ScheduledAction(105, "Unit dispatched", e -> e.dispatchEmergency(null)),
                    new ScheduledAction(170, "Medical call across town", e -> e.dispatchEmergency(null)));
            case ROAD_FAILURE -> List.of(
                    new ScheduledAction(60, "Structural failure: busiest arterial closed", e -> e.closeRoad(busiestArterial(e))));
            case SMART_SIGNALS -> List.of();
        };
    }

    /** The arterial road whose midpoint is closest to the city centre. */
    static int centralArterial(SimulationEngine e) {
        Road best = null;
        double bestDist = Double.MAX_VALUE;
        for (Road r : e.graph().roads()) {
            if (r.roadClass() != RoadClass.ARTERIAL || r.isClosed()) {
                continue;
            }
            double d = r.a().position().lerp(r.b().position(), 0.5).length();
            if (d < bestDist) {
                bestDist = d;
                best = r;
            }
        }
        return best == null ? 0 : best.id();
    }

    /** The open arterial road carrying the most vehicles right now (ties: lowest id). */
    static int busiestArterial(SimulationEngine e) {
        Road best = null;
        int bestLoad = -1;
        for (Road r : e.graph().roads()) {
            if (r.roadClass() != RoadClass.ARTERIAL || r.isClosed()) {
                continue;
            }
            int load = r.forward().load() + (r.backward() == null ? 0 : r.backward().load());
            Vec2 mid = r.a().position().lerp(r.b().position(), 0.5);
            if (load > bestLoad || (load == bestLoad && mid.length() < 1e-9)) {
                bestLoad = load;
                best = r;
            }
        }
        return best == null ? centralArterial(e) : best.id();
    }

    public SimulationEngine createEngine() {
        SimulationEngine engine = new SimulationEngine(config);
        engine.loadScript(title, script());
        return engine;
    }

    public SimulationEngine createEngine(SignalMode override) {
        SimulationEngine engine = new SimulationEngine(config.withSignalMode(override));
        engine.loadScript(title, script());
        return engine;
    }
}
