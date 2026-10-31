package com.synapsecity.simulation.scenarios;

import com.synapsecity.simulation.core.SimulationConfig;
import com.synapsecity.simulation.core.SimulationEngine;
import com.synapsecity.simulation.metrics.LiveMetrics;
import com.synapsecity.simulation.metrics.PerformanceStats;
import com.synapsecity.simulation.roads.CityLayout;

/**
 * Command-line entry point that runs the simulation without Spring or a browser.
 *
 * <pre>
 *   java -cp target/classes com.synapsecity.simulation.scenarios.HeadlessRunner [layout] [vehicles] [seconds] [seed]
 *   java -cp target/classes com.synapsecity.simulation.scenarios.HeadlessRunner compare [preset] [seconds]
 * </pre>
 */
public final class HeadlessRunner {

    private HeadlessRunner() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("compare")) {
            ScenarioPreset preset = args.length > 1 ? ScenarioPreset.valueOf(args[1].toUpperCase()) : ScenarioPreset.SMART_SIGNALS;
            double seconds = args.length > 2 ? Double.parseDouble(args[2]) : 600;
            var r = ComparisonRunner.compare(preset.config(), preset, seconds, p -> { });
            System.out.printf("Scenario %s, seed %d, %s, %d vehicles, %.0f s simulated%n",
                    r.scenario(), r.seed(), r.layout(), r.targetVehicles(), r.durationSeconds());
            for (var s : new ComparisonRunner.RunSummary[]{r.fixed(), r.adaptive()}) {
                System.out.printf("  %-8s trips=%5d  avgTrip=%6.1fs  avgWait=%5.1fs  p90=%6.1fs  speed=%5.2fm/s  "
                                + "congestion=%5.1f  peakQueue=%4d  abandoned=%3d  wall=%6.0fms%n",
                        s.mode(), s.completedTrips(), s.avgTripTime(), s.avgTripWait(), s.p90TripTime(), s.avgSpeed(),
                        s.meanCongestion(), s.peakQueue(), s.abandoned(), s.wallMillis());
            }
            System.out.printf("  Adaptive vs fixed average trip time: %+.1f%%%n", r.tripTimeChangePct());
            return;
        }
        CityLayout layout = args.length > 0 ? CityLayout.valueOf(args[0].toUpperCase()) : CityLayout.GRID;
        int vehicles = args.length > 1 ? Integer.parseInt(args[1]) : 400;
        double seconds = args.length > 2 ? Double.parseDouble(args[2]) : 300;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : SimulationConfig.defaults().seed();
        long t0 = System.nanoTime();
        SimulationEngine e = new SimulationEngine(SimulationConfig.defaults().withLayout(layout).withSeed(seed)
                .withTargetVehicles(vehicles));
        double genMs = (System.nanoTime() - t0) / 1e6;
        System.out.printf("%s (%s, seed %d): %d nodes, %d segments, %d buildings, generated in %.0f ms%n",
                e.city().name(), layout, seed, e.graph().nodeCount(), e.graph().segments().size(),
                e.city().buildings().size(), genMs);
        long t1 = System.nanoTime();
        e.runFor(seconds);
        double runMs = (System.nanoTime() - t1) / 1e6;
        LiveMetrics m = e.liveMetrics();
        PerformanceStats p = e.performance();
        System.out.printf("Simulated %.0f s in %.0f ms (%.0fx real time)%n", seconds, runMs, seconds * 1000 / runMs);
        System.out.printf("active=%d completed=%d abandoned=%d avgTrip=%.1fs avgWait=%.1fs speed=%.2fm/s congestion=%.1f (%s) reroutes=%d%n",
                m.active(), m.completedTrips(), m.abandonedTrips(), m.avgTripTime(), m.avgTripWait(), m.avgSpeed(),
                m.congestionIndex(), m.condition(), m.reroutes());
        System.out.printf("step avg=%.1fus p95=%.1fus max=%.0fus | routing calls=%d avg=%.1fus explored=%.1f failures=%d | safety clamps=%d%n",
                p.avgStepMicros(), p.p95StepMicros(), p.maxStepMicros(), p.routingCalls(), p.avgRoutingMicros(),
                p.avgExploredNodes(), p.routingFailures(), p.safetyClamps());
    }
}
