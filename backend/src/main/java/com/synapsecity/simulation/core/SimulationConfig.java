package com.synapsecity.simulation.core;

import com.synapsecity.simulation.agents.DemandProfile;
import com.synapsecity.simulation.roads.CityLayout;
import com.synapsecity.simulation.routing.RoutingAlgorithm;
import com.synapsecity.simulation.signals.SignalMode;

/**
 * Everything that determines a run. Two engines built from equal configs and fed the same commands
 * at the same ticks produce bit-identical results.
 */
public record SimulationConfig(long seed, CityLayout layout, int targetVehicles, SignalMode signalMode,
                               RoutingAlgorithm routingAlgorithm, DemandProfile demandProfile) {

    /** Fixed simulation timestep in seconds. */
    public static final double DT = 0.1;
    public static final int MAX_VEHICLES = 1200;

    public SimulationConfig {
        if (layout == null || signalMode == null || routingAlgorithm == null || demandProfile == null) {
            throw new IllegalArgumentException("Configuration fields must not be null");
        }
        if (targetVehicles < 0 || targetVehicles > MAX_VEHICLES) {
            throw new IllegalArgumentException("targetVehicles must be within [0, " + MAX_VEHICLES + "]");
        }
    }

    public static SimulationConfig defaults() {
        return new SimulationConfig(20261030L, CityLayout.GRID, 300, SignalMode.ADAPTIVE,
                RoutingAlgorithm.A_STAR, DemandProfile.BALANCED);
    }

    public SimulationConfig withSeed(long s) {
        return new SimulationConfig(s, layout, targetVehicles, signalMode, routingAlgorithm, demandProfile);
    }

    public SimulationConfig withLayout(CityLayout l) {
        return new SimulationConfig(seed, l, targetVehicles, signalMode, routingAlgorithm, demandProfile);
    }

    public SimulationConfig withTargetVehicles(int n) {
        return new SimulationConfig(seed, layout, n, signalMode, routingAlgorithm, demandProfile);
    }

    public SimulationConfig withSignalMode(SignalMode m) {
        return new SimulationConfig(seed, layout, targetVehicles, m, routingAlgorithm, demandProfile);
    }

    public SimulationConfig withRoutingAlgorithm(RoutingAlgorithm a) {
        return new SimulationConfig(seed, layout, targetVehicles, signalMode, a, demandProfile);
    }

    public SimulationConfig withDemandProfile(DemandProfile p) {
        return new SimulationConfig(seed, layout, targetVehicles, signalMode, routingAlgorithm, p);
    }
}
