package com.synapsecity.websocket.protocol;

import com.synapsecity.simulation.agents.DriverProfile;
import com.synapsecity.simulation.metrics.LiveMetrics;
import com.synapsecity.simulation.metrics.MetricsSample;
import com.synapsecity.simulation.metrics.PerformanceStats;
import com.synapsecity.simulation.scenarios.ComparisonRunner;

import java.util.List;

/**
 * Server-to-client JSON message contracts. Every message carries a {@code type} discriminator;
 * the TypeScript mirror lives in {@code frontend/src/network/protocol.ts}.
 *
 * <p>High-frequency vehicle positions are not here: they travel as compact binary frames
 * (see {@link BinaryFrameEncoder}).</p>
 */
public final class Messages {

    private Messages() {
    }

    public static final int PROTOCOL_VERSION = 1;

    public record Hello(String type, int protocolVersion, double dt, int binaryVehicleStride) {
    }

    public record NodeDto(int id, double x, double y, double r, boolean signalized, int degree) {
    }

    public record RoadDto(int id, int a, int b, String cls, int lanes, double halfWidth, double median,
                          int forward, int backward, boolean closed) {
    }

    public record SegmentDto(int id, int road, int from, int to, int lanes, double sx, double sy, double ex,
                             double ey, double length, double speedLimit, double capacity) {
    }

    /** Signal head for one approach; {@code index} is its position in the binary colour array. */
    public record ApproachDto(int index, int node, int segment, int phase, double x, double y, double angle) {
    }

    public record ParkDto(int id, String name, double x, double y, double r) {
    }

    public record BusStopDto(int segment, double x, double y, double angle) {
    }

    /**
     * Static city geometry. {@code buildings} is a flat array with stride 9:
     * x, y, width, depth, height, rotation, styleOrdinal, districtOrdinal, variant.
     * {@code trees} has stride 2 (x, y); {@code lights} stride 3 (x, y, rotation).
     */
    public record CityMessage(String type, String name, long seed, String layout, double radius,
                              int stationNode, int stationBuilding, List<NodeDto> nodes, List<RoadDto> roads,
                              List<SegmentDto> segments, List<ApproachDto> approaches, double[] buildings,
                              List<String> buildingStyles, List<ParkDto> parks, double[] trees, double[] lights,
                              List<BusStopDto> busStops) {
    }

    public record EventDto(long id, long tick, double time, String type, String severity, String title,
                           String detail, int vehicleId, int roadId, int nodeId, Double x, Double y) {
    }

    public record IncidentDto(int id, int roadId, int segmentId, double startTime, double endTime, double x, double y) {
    }

    public record ServerStats(int clients, int lastFrameBytes, double framesPerSecond, double stepsPerSecond,
                              double simSecondsPerWallSecond) {
    }

    public record StateMessage(String type, long tick, double time, boolean running, double speed, String signalMode,
                               String routing, String demandProfile, int targetVehicles, String scenario,
                               long seed, String layout, LiveMetrics metrics, PerformanceStats perf,
                               ServerStats server, List<IncidentDto> incidents, int[] closedRoads,
                               List<EventDto> events, List<MetricsSample> series, boolean comparisonRunning) {
    }

    public record DecisionDto(double time, String decision, String text) {
    }

    public record RouteAnalysisDto(String algorithm, int exploredNodes, double computeMicros, double distance,
                                   double plannedCost, double remainingDistance, double estimatedRemaining,
                                   int remainingSegments, int routeChanges, boolean hasRoute) {
    }

    public record TripDto(double elapsed, double waitTime, double distance, double freeFlowEstimate, double stoppedFor) {
    }

    public record VehicleDetail(String type, int id, String vehicleType, String state, double stateFor,
                                String decision, String decisionText, List<DecisionDto> history, double speed,
                                double accel, double speedLimit, int segment, int road, int lane, double x,
                                double y, double heading, double[] routePoints, int[] routeSegments,
                                double destX, double destY, RouteAnalysisDto analysis, TripDto trip,
                                DriverProfile driver, boolean siren, int stopIndex, int stopCount) {
    }

    public record ApproachDetail(int segment, int road, int phase, String color, int queue, int demand,
                                 double totalWait, double maxWait, double heading) {
    }

    public record PhaseDetail(int index, int[] segments, double pressure, int demand, double sinceServed) {
    }

    public record IntersectionDetail(String type, int node, double x, double y, boolean signalized, String mode,
                                     int phase, int nextPhase, int phaseCount, String stage, double stageElapsed,
                                     double stageRemaining, boolean preempted, int preemptVehicle, long entries,
                                     long phaseChanges, long preemptions, int occupants,
                                     List<ApproachDetail> approaches, List<PhaseDetail> phases) {
    }

    public record ComparisonMessage(String type, String status, double progress, String message,
                                    ComparisonRunner.ComparisonResult result) {
    }

    public record ErrorMessage(String type, String message) {
    }

    public record AckMessage(String type, String command, String message) {
    }

    public record ScenarioDto(String id, String title, String description, long seed, String layout,
                              int targetVehicles, String signalMode, String demandProfile, int scriptedSteps) {
    }

    public record LiveSummary(LiveMetrics metrics, PerformanceStats perf) {
    }

    public static List<String> buildingStyleNames() {
        return List.of("TOWER", "SETBACK_TOWER", "OFFICE", "APARTMENT", "HOUSE", "CIVIC");
    }
}
