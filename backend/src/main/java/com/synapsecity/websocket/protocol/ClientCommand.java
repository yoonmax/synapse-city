package com.synapsecity.websocket.protocol;

import com.synapsecity.simulation.agents.DemandProfile;
import com.synapsecity.simulation.agents.VehicleType;
import com.synapsecity.simulation.roads.CityLayout;
import com.synapsecity.simulation.routing.RoutingAlgorithm;
import com.synapsecity.simulation.scenarios.ScenarioPreset;
import com.synapsecity.simulation.signals.SignalMode;

/**
 * Typed client-to-server commands. Instances are only ever created by {@link CommandParser},
 * which guarantees every field is present and within range. Entity ids (roads, vehicles,
 * nodes) are re-checked against the live world when the command is applied.
 */
public sealed interface ClientCommand {

    record Pause() implements ClientCommand { }

    record Resume() implements ClientCommand { }

    record Step(int ticks) implements ClientCommand { }

    record SetSpeed(double value) implements ClientCommand { }

    record AddVehicles(int count, VehicleType vehicleType) implements ClientCommand { }

    record SetDensity(int value) implements ClientCommand { }

    record SetDemand(DemandProfile profile) implements ClientCommand { }

    record CloseRoad(int roadId) implements ClientCommand { }

    record OpenRoad(int roadId) implements ClientCommand { }

    record CreateIncident(Integer roadId) implements ClientCommand { }

    record DispatchEmergency(Integer roadId) implements ClientCommand { }

    record SetSignalMode(SignalMode mode) implements ClientCommand { }

    record SetRouting(RoutingAlgorithm algorithm) implements ClientCommand { }

    record SelectVehicle(Integer vehicleId) implements ClientCommand { }

    record InspectIntersection(Integer nodeId) implements ClientCommand { }

    record Reset() implements ClientCommand { }

    record Regenerate(long seed, CityLayout layout) implements ClientCommand { }

    record LoadScenario(ScenarioPreset scenario) implements ClientCommand { }

    record RunComparison(double durationSeconds) implements ClientCommand { }
}
