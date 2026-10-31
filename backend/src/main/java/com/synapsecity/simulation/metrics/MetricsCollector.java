package com.synapsecity.simulation.metrics;

import com.synapsecity.simulation.agents.Vehicle;
import com.synapsecity.simulation.agents.VehicleState;
import com.synapsecity.simulation.agents.VehicleType;
import com.synapsecity.simulation.roads.Road;
import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadSegment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** Aggregates trip outcomes, a sampled time series and engine performance. */
public final class MetricsCollector {

    public static final double SAMPLE_INTERVAL = 5.0;
    public static final int SERIES_CAPACITY = 720;
    private static final int RECENT_TRIPS = 300;
    private static final int STEP_WINDOW = 600;

    public record TripRecord(double completedAt, VehicleType type, double duration, double waitTime,
                             double distance, double freeFlow) {
    }

    private long completedTrips;
    private long abandonedTrips;
    private long spawned;
    private long reroutes;
    private double sumTripTime;
    private double sumTripWait;
    private final Deque<TripRecord> recent = new ArrayDeque<>();
    private final List<Double> emergencyResponses = new ArrayList<>();
    private final Deque<MetricsSample> series = new ArrayDeque<>();
    private double nextSample = SAMPLE_INTERVAL;

    private final long[] stepNanos = new long[STEP_WINDOW];
    private long steps;
    private long maxStepNanos;

    public void recordTrip(VehicleType type, double completedAt, double duration, double waitTime,
                           double distance, double freeFlow) {
        completedTrips++;
        sumTripTime += duration;
        sumTripWait += waitTime;
        recent.addLast(new TripRecord(completedAt, type, duration, waitTime, distance, freeFlow));
        while (recent.size() > RECENT_TRIPS) {
            recent.removeFirst();
        }
    }

    public void recordAbandoned() {
        abandonedTrips++;
    }

    public void recordSpawn() {
        spawned++;
    }

    public void recordReroute() {
        reroutes++;
    }

    public void recordEmergencyResponse(double seconds) {
        emergencyResponses.add(seconds);
    }

    public void recordStep(long nanos) {
        stepNanos[(int) (steps % STEP_WINDOW)] = nanos;
        steps++;
        maxStepNanos = Math.max(maxStepNanos, nanos);
    }

    /** Appends a time-series sample if the sampling interval has elapsed. */
    public void maybeSample(double time, LiveMetricsSupplier supplier) {
        if (time + 1e-9 < nextSample) {
            return;
        }
        nextSample += SAMPLE_INTERVAL;
        LiveMetrics m = supplier.get();
        series.addLast(new MetricsSample(time, m.active(), m.avgSpeed(), m.congestionIndex(), m.queuedAtSignals(),
                m.throughputPerMin(), m.avgTripTime(), m.avgTripWait(), m.completedTrips(), m.travelTimeIndex()));
        while (series.size() > SERIES_CAPACITY) {
            series.removeFirst();
        }
    }

    @FunctionalInterface
    public interface LiveMetricsSupplier {
        LiveMetrics get();
    }

    public LiveMetrics computeLive(List<Vehicle> vehicles, RoadGraph graph, double time, int queuedAtSignals,
                                   int activeIncidents) {
        int active = 0;
        int moving = 0;
        int stopped = 0;
        double sumSpeed = 0;
        double sumLimit = 0;
        double sumWait = 0;
        for (Vehicle v : vehicles) {
            VehicleState st = v.state();
            if (st == VehicleState.DESPAWNING) {
                continue;
            }
            active++;
            if (st == VehicleState.SPAWNING || st == VehicleState.DWELLING) {
                continue;
            }
            RoadSegment seg = v.isInConnector() ? v.connector().out() : v.segment();
            sumSpeed += v.speed();
            sumLimit += seg.speedLimit();
            sumWait += v.stoppedFor();
            if (v.speed() < 0.5) {
                stopped++;
            } else {
                moving++;
            }
        }
        int onRoad = moving + stopped;
        double avgSpeed = onRoad == 0 ? 0 : sumSpeed / onRoad;
        double congestion = sumLimit == 0 ? 0 : Math.max(0, Math.min(100, 100 * (1 - sumSpeed / sumLimit)));

        double recentTime = 0;
        double recentWait = 0;
        double tti = 0;
        int ttiCount = 0;
        int lastMinute = 0;
        double[] durations = new double[recent.size()];
        int i = 0;
        for (TripRecord r : recent) {
            recentTime += r.duration();
            recentWait += r.waitTime();
            durations[i++] = r.duration();
            if (r.freeFlow() > 1) {
                tti += r.duration() / r.freeFlow();
                ttiCount++;
            }
            if (time - r.completedAt() <= 60) {
                lastMinute++;
            }
        }
        double avgTrip = recent.isEmpty() ? 0 : recentTime / recent.size();
        double avgTripWait = recent.isEmpty() ? 0 : recentWait / recent.size();
        double p90 = 0;
        if (durations.length > 0) {
            Arrays.sort(durations);
            p90 = durations[Math.min(durations.length - 1, (int) Math.floor(durations.length * 0.9))];
        }
        double throughput = time < 60 ? (time > 0 ? lastMinute * 60.0 / time : 0) : lastMinute;

        double util = 0;
        int occupied = 0;
        for (RoadSegment s : graph.segments()) {
            if (s.load() > 0) {
                util += s.loadRatio();
                occupied++;
            }
        }
        int closed = 0;
        for (Road r : graph.roads()) {
            if (r.isClosed()) {
                closed++;
            }
        }
        double emergencyAvg = emergencyResponses.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new LiveMetrics(active, moving, stopped, avgSpeed, congestion, conditionFor(congestion),
                onRoad == 0 ? 0 : sumWait / onRoad, queuedAtSignals, completedTrips, abandonedTrips, spawned,
                avgTrip, avgTripWait, p90, ttiCount == 0 ? 1 : tti / ttiCount, throughput, emergencyAvg,
                emergencyResponses.size(), reroutes, occupied == 0 ? 0 : util / occupied, closed, activeIncidents);
    }

    /**
     * Condition bands are calibrated against measured baselines: an almost empty signalised network
     * already scores ~35-40 because the index counts red-light stops and turn slowdowns.
     */
    public static String conditionFor(double congestion) {
        if (congestion < 45) {
            return "FREE FLOW";
        }
        if (congestion < 60) {
            return "MODERATE";
        }
        if (congestion < 75) {
            return "HEAVY";
        }
        return "SEVERE";
    }

    public PerformanceStats performance(long routingCalls, double avgRoutingMicros, double avgExplored,
                                        long routingFailures, long safetyClamps) {
        int n = (int) Math.min(steps, STEP_WINDOW);
        double avg = 0;
        double p95 = 0;
        if (n > 0) {
            long[] window = Arrays.copyOf(stepNanos, n);
            Arrays.sort(window);
            long sum = 0;
            for (long w : window) {
                sum += w;
            }
            avg = sum / 1000.0 / n;
            p95 = window[Math.min(n - 1, (int) Math.floor(n * 0.95))] / 1000.0;
        }
        return new PerformanceStats(steps, avg, p95, maxStepNanos / 1000.0, routingCalls, avgRoutingMicros,
                avgExplored, routingFailures, safetyClamps);
    }

    public List<MetricsSample> series() {
        return List.copyOf(series);
    }

    public List<TripRecord> recentTrips() {
        return List.copyOf(recent);
    }

    public List<Double> emergencyResponses() {
        return List.copyOf(emergencyResponses);
    }

    public long completedTrips() {
        return completedTrips;
    }

    public long abandonedTrips() {
        return abandonedTrips;
    }

    public long spawned() {
        return spawned;
    }

    public long reroutes() {
        return reroutes;
    }

    public double meanTripTime() {
        return completedTrips == 0 ? 0 : sumTripTime / completedTrips;
    }

    public double meanTripWait() {
        return completedTrips == 0 ? 0 : sumTripWait / completedTrips;
    }
}
