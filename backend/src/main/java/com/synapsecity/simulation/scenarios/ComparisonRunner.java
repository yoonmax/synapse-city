package com.synapsecity.simulation.scenarios;

import com.synapsecity.simulation.core.SimulationConfig;
import com.synapsecity.simulation.core.SimulationEngine;
import com.synapsecity.simulation.metrics.MetricsSample;
import com.synapsecity.simulation.roads.CityLayout;
import com.synapsecity.simulation.signals.SignalMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Runs the same seed, configuration and script twice, once per signal strategy, in independent
 * engines and reports measured outcomes side by side. Each run is single-threaded and
 * deterministic, so a comparison is exactly repeatable.
 */
public final class ComparisonRunner {

    private ComparisonRunner() {
    }

    public record RunSummary(SignalMode mode, long completedTrips, double avgTripTime, double avgTripWait,
                             double avgSpeed, double meanCongestion, double p90TripTime, double throughputPerMin,
                             int peakQueue, double emergencyAvgResponse, long abandoned, long stateHash,
                             double wallMillis, List<double[]> series) {
    }

    public record ComparisonResult(long seed, CityLayout layout, int targetVehicles, String scenario,
                                   double durationSeconds, RunSummary fixed, RunSummary adaptive) {

        /** Relative change of adaptive vs fixed, in percent (negative = adaptive lower). */
        public double tripTimeChangePct() {
            return fixed.avgTripTime() == 0 ? 0 : 100 * (adaptive.avgTripTime() - fixed.avgTripTime()) / fixed.avgTripTime();
        }
    }

    public static ComparisonResult compare(SimulationConfig base, ScenarioPreset preset, double durationSeconds,
                                           DoubleConsumer progress) {
        RunSummary fixed = run(base, preset, SignalMode.FIXED, durationSeconds, p -> progress.accept(p * 0.5));
        RunSummary adaptive = run(base, preset, SignalMode.ADAPTIVE, durationSeconds, p -> progress.accept(0.5 + p * 0.5));
        return new ComparisonResult(base.seed(), base.layout(), base.targetVehicles(),
                preset == null ? "Custom" : preset.title(), durationSeconds, fixed, adaptive);
    }

    public static RunSummary run(SimulationConfig base, ScenarioPreset preset, SignalMode mode, double durationSeconds,
                                 DoubleConsumer progress) {
        SimulationEngine engine = new SimulationEngine(base.withSignalMode(mode));
        if (preset != null) {
            engine.loadScript(preset.title(), preset.script());
        }
        long t0 = System.nanoTime();
        long steps = Math.round(durationSeconds / SimulationConfig.DT);
        for (long i = 0; i < steps; i++) {
            engine.step();
            if (i % 500 == 0) {
                progress.accept((double) i / steps);
            }
        }
        progress.accept(1.0);
        double wallMillis = (System.nanoTime() - t0) / 1e6;
        List<MetricsSample> samples = engine.metrics().series();
        double speed = 0;
        double congestion = 0;
        int peakQueue = 0;
        List<double[]> series = new ArrayList<>();
        for (MetricsSample s : samples) {
            speed += s.avgSpeed();
            congestion += s.congestionIndex();
            peakQueue = Math.max(peakQueue, s.queued());
            series.add(new double[]{s.time(), s.congestionIndex(), s.avgSpeed(), s.active(), s.throughputPerMin(), s.queued()});
        }
        int n = Math.max(1, samples.size());
        var live = engine.liveMetrics();
        return new RunSummary(mode, engine.metrics().completedTrips(), engine.metrics().meanTripTime(),
                engine.metrics().meanTripWait(), speed / n, congestion / n, live.p90TripTime(),
                engine.metrics().completedTrips() / (durationSeconds / 60.0), peakQueue, live.emergencyAvgResponse(),
                engine.metrics().abandonedTrips(), engine.stateHash(), wallMillis, series);
    }
}
