package com.synapsecity.websocket.protocol;

import com.synapsecity.simulation.metrics.LiveMetrics;
import com.synapsecity.simulation.metrics.MetricsSample;
import com.synapsecity.simulation.metrics.PerformanceStats;
import com.synapsecity.simulation.scenarios.ComparisonRunner;

import java.util.List;

/**
 * Serialises {@link Messages} to JSON without reflection or libraries, for the browser build, where
 * Jackson is unavailable. {@code WireJsonTest} holds this output equal to Jackson's for real messages,
 * so both hosts speak the same protocol.
 */
public final class WireJson {

    private final StringBuilder b = new StringBuilder();
    private boolean first = true;

    private WireJson() {
    }

    public static String write(Object message) {
        WireJson w = new WireJson();
        w.message(message);
        return w.b.toString();
    }

    private void message(Object m) {
        if (m instanceof Messages.Hello x) {
            hello(x);
        } else if (m instanceof Messages.CityMessage x) {
            city(x);
        } else if (m instanceof Messages.StateMessage x) {
            state(x);
        } else if (m instanceof Messages.VehicleDetail x) {
            vehicle(x);
        } else if (m instanceof Messages.IntersectionDetail x) {
            intersection(x);
        } else if (m instanceof Messages.ComparisonMessage x) {
            comparison(x);
        } else if (m instanceof Messages.ErrorMessage x) {
            open();
            str("type", x.type());
            str("message", x.message());
            close();
        } else if (m instanceof Messages.AckMessage x) {
            open();
            str("type", x.type());
            str("command", x.command());
            str("message", x.message());
            close();
        } else if (m instanceof Messages.ScenariosMessage x) {
            open();
            str("type", x.type());
            key("scenarios");
            beginArray();
            for (Messages.ScenarioDto s : x.scenarios()) {
                item();
                scenario(s);
            }
            endArray();
            close();
        } else {
            throw new IllegalArgumentException("Unsupported message");
        }
    }

    private void hello(Messages.Hello x) {
        open();
        str("type", x.type());
        num("protocolVersion", x.protocolVersion());
        num("dt", x.dt());
        num("binaryVehicleStride", x.binaryVehicleStride());
        close();
    }

    private void city(Messages.CityMessage x) {
        open();
        str("type", x.type());
        str("name", x.name());
        num("seed", x.seed());
        str("layout", x.layout());
        num("radius", x.radius());
        num("stationNode", x.stationNode());
        num("stationBuilding", x.stationBuilding());
        key("nodes");
        beginArray();
        for (Messages.NodeDto n : x.nodes()) {
            item();
            open();
            num("id", n.id());
            num("x", n.x());
            num("y", n.y());
            num("r", n.r());
            bool("signalized", n.signalized());
            num("degree", n.degree());
            close();
        }
        endArray();
        key("roads");
        beginArray();
        for (Messages.RoadDto r : x.roads()) {
            item();
            open();
            num("id", r.id());
            num("a", r.a());
            num("b", r.b());
            str("cls", r.cls());
            num("lanes", r.lanes());
            num("halfWidth", r.halfWidth());
            num("median", r.median());
            num("forward", r.forward());
            num("backward", r.backward());
            bool("closed", r.closed());
            close();
        }
        endArray();
        key("segments");
        beginArray();
        for (Messages.SegmentDto s : x.segments()) {
            item();
            open();
            num("id", s.id());
            num("road", s.road());
            num("from", s.from());
            num("to", s.to());
            num("lanes", s.lanes());
            num("sx", s.sx());
            num("sy", s.sy());
            num("ex", s.ex());
            num("ey", s.ey());
            num("length", s.length());
            num("speedLimit", s.speedLimit());
            num("capacity", s.capacity());
            close();
        }
        endArray();
        key("approaches");
        beginArray();
        for (Messages.ApproachDto a : x.approaches()) {
            item();
            open();
            num("index", a.index());
            num("node", a.node());
            num("segment", a.segment());
            num("phase", a.phase());
            num("x", a.x());
            num("y", a.y());
            num("angle", a.angle());
            close();
        }
        endArray();
        doubles("buildings", x.buildings());
        key("buildingStyles");
        beginArray();
        for (String s : x.buildingStyles()) {
            item();
            quote(s);
            first = false;
        }
        endArray();
        key("parks");
        beginArray();
        for (Messages.ParkDto p : x.parks()) {
            item();
            open();
            num("id", p.id());
            str("name", p.name());
            num("x", p.x());
            num("y", p.y());
            num("r", p.r());
            close();
        }
        endArray();
        doubles("trees", x.trees());
        doubles("lights", x.lights());
        key("busStops");
        beginArray();
        for (Messages.BusStopDto s : x.busStops()) {
            item();
            open();
            num("segment", s.segment());
            num("x", s.x());
            num("y", s.y());
            num("angle", s.angle());
            close();
        }
        endArray();
        close();
    }

    private void state(Messages.StateMessage x) {
        open();
        str("type", x.type());
        num("tick", x.tick());
        num("time", x.time());
        bool("running", x.running());
        num("speed", x.speed());
        str("signalMode", x.signalMode());
        str("routing", x.routing());
        str("demandProfile", x.demandProfile());
        num("targetVehicles", x.targetVehicles());
        str("scenario", x.scenario());
        num("seed", x.seed());
        str("layout", x.layout());
        key("metrics");
        metrics(x.metrics());
        key("perf");
        perf(x.perf());
        key("server");
        open();
        Messages.ServerStats s = x.server();
        num("clients", s.clients());
        num("lastFrameBytes", s.lastFrameBytes());
        num("framesPerSecond", s.framesPerSecond());
        num("stepsPerSecond", s.stepsPerSecond());
        num("simSecondsPerWallSecond", s.simSecondsPerWallSecond());
        close();
        key("incidents");
        beginArray();
        for (Messages.IncidentDto i : x.incidents()) {
            item();
            open();
            num("id", i.id());
            num("roadId", i.roadId());
            num("segmentId", i.segmentId());
            num("startTime", i.startTime());
            num("endTime", i.endTime());
            num("x", i.x());
            num("y", i.y());
            close();
        }
        endArray();
        ints("closedRoads", x.closedRoads());
        key("events");
        beginArray();
        for (Messages.EventDto e : x.events()) {
            item();
            open();
            num("id", e.id());
            num("tick", e.tick());
            num("time", e.time());
            str("type", e.type());
            str("severity", e.severity());
            str("title", e.title());
            str("detail", e.detail());
            num("vehicleId", e.vehicleId());
            num("roadId", e.roadId());
            num("nodeId", e.nodeId());
            optional("x", e.x());
            optional("y", e.y());
            close();
        }
        endArray();
        key("series");
        beginArray();
        for (MetricsSample m : x.series()) {
            item();
            open();
            num("time", m.time());
            num("active", m.active());
            num("avgSpeed", m.avgSpeed());
            num("congestionIndex", m.congestionIndex());
            num("queued", m.queued());
            num("throughputPerMin", m.throughputPerMin());
            num("avgTripTime", m.avgTripTime());
            num("avgTripWait", m.avgTripWait());
            num("completed", m.completed());
            num("travelTimeIndex", m.travelTimeIndex());
            close();
        }
        endArray();
        bool("comparisonRunning", x.comparisonRunning());
        close();
    }

    private void metrics(LiveMetrics m) {
        open();
        num("active", m.active());
        num("moving", m.moving());
        num("stopped", m.stopped());
        num("avgSpeed", m.avgSpeed());
        num("congestionIndex", m.congestionIndex());
        str("condition", m.condition());
        num("avgCurrentWait", m.avgCurrentWait());
        num("queuedAtSignals", m.queuedAtSignals());
        num("completedTrips", m.completedTrips());
        num("abandonedTrips", m.abandonedTrips());
        num("spawned", m.spawned());
        num("avgTripTime", m.avgTripTime());
        num("avgTripWait", m.avgTripWait());
        num("p90TripTime", m.p90TripTime());
        num("travelTimeIndex", m.travelTimeIndex());
        num("throughputPerMin", m.throughputPerMin());
        num("emergencyAvgResponse", m.emergencyAvgResponse());
        num("emergencyResponses", m.emergencyResponses());
        num("reroutes", m.reroutes());
        num("utilization", m.utilization());
        num("closedRoads", m.closedRoads());
        num("activeIncidents", m.activeIncidents());
        close();
    }

    private void perf(PerformanceStats p) {
        open();
        num("steps", p.steps());
        num("avgStepMicros", p.avgStepMicros());
        num("p95StepMicros", p.p95StepMicros());
        num("maxStepMicros", p.maxStepMicros());
        num("routingCalls", p.routingCalls());
        num("avgRoutingMicros", p.avgRoutingMicros());
        num("avgExploredNodes", p.avgExploredNodes());
        num("routingFailures", p.routingFailures());
        num("safetyClamps", p.safetyClamps());
        close();
    }

    private void vehicle(Messages.VehicleDetail v) {
        open();
        str("type", v.type());
        num("id", v.id());
        str("vehicleType", v.vehicleType());
        str("state", v.state());
        num("stateFor", v.stateFor());
        str("decision", v.decision());
        str("decisionText", v.decisionText());
        key("history");
        beginArray();
        for (Messages.DecisionDto d : v.history()) {
            item();
            open();
            num("time", d.time());
            str("decision", d.decision());
            str("text", d.text());
            close();
        }
        endArray();
        num("speed", v.speed());
        num("accel", v.accel());
        num("speedLimit", v.speedLimit());
        num("segment", v.segment());
        num("road", v.road());
        num("lane", v.lane());
        num("x", v.x());
        num("y", v.y());
        num("heading", v.heading());
        doubles("routePoints", v.routePoints());
        ints("routeSegments", v.routeSegments());
        num("destX", v.destX());
        num("destY", v.destY());
        key("analysis");
        open();
        Messages.RouteAnalysisDto a = v.analysis();
        str("algorithm", a.algorithm());
        num("exploredNodes", a.exploredNodes());
        num("computeMicros", a.computeMicros());
        num("distance", a.distance());
        num("plannedCost", a.plannedCost());
        num("remainingDistance", a.remainingDistance());
        num("estimatedRemaining", a.estimatedRemaining());
        num("remainingSegments", a.remainingSegments());
        num("routeChanges", a.routeChanges());
        bool("hasRoute", a.hasRoute());
        close();
        key("trip");
        open();
        Messages.TripDto t = v.trip();
        num("elapsed", t.elapsed());
        num("waitTime", t.waitTime());
        num("distance", t.distance());
        num("freeFlowEstimate", t.freeFlowEstimate());
        num("stoppedFor", t.stoppedFor());
        close();
        key("driver");
        open();
        num("speedFactor", v.driver().speedFactor());
        num("headwayFactor", v.driver().headwayFactor());
        num("accelFactor", v.driver().accelFactor());
        num("rerouteAwareness", v.driver().rerouteAwareness());
        close();
        bool("siren", v.siren());
        num("stopIndex", v.stopIndex());
        num("stopCount", v.stopCount());
        close();
    }

    private void intersection(Messages.IntersectionDetail x) {
        open();
        str("type", x.type());
        num("node", x.node());
        num("x", x.x());
        num("y", x.y());
        bool("signalized", x.signalized());
        str("mode", x.mode());
        num("phase", x.phase());
        num("nextPhase", x.nextPhase());
        num("phaseCount", x.phaseCount());
        str("stage", x.stage());
        num("stageElapsed", x.stageElapsed());
        num("stageRemaining", x.stageRemaining());
        bool("preempted", x.preempted());
        num("preemptVehicle", x.preemptVehicle());
        num("entries", x.entries());
        num("phaseChanges", x.phaseChanges());
        num("preemptions", x.preemptions());
        num("occupants", x.occupants());
        key("approaches");
        beginArray();
        for (Messages.ApproachDetail a : x.approaches()) {
            item();
            open();
            num("segment", a.segment());
            num("road", a.road());
            num("phase", a.phase());
            str("color", a.color());
            num("queue", a.queue());
            num("demand", a.demand());
            num("totalWait", a.totalWait());
            num("maxWait", a.maxWait());
            num("heading", a.heading());
            close();
        }
        endArray();
        key("phases");
        beginArray();
        for (Messages.PhaseDetail p : x.phases()) {
            item();
            open();
            num("index", p.index());
            ints("segments", p.segments());
            num("pressure", p.pressure());
            num("demand", p.demand());
            num("sinceServed", p.sinceServed());
            close();
        }
        endArray();
        close();
    }

    private void comparison(Messages.ComparisonMessage x) {
        open();
        str("type", x.type());
        str("status", x.status());
        num("progress", x.progress());
        str("message", x.message());
        key("result");
        ComparisonRunner.ComparisonResult r = x.result();
        if (r == null) {
            b.append("null");
        } else {
            open();
            num("seed", r.seed());
            str("layout", r.layout().name());
            num("targetVehicles", r.targetVehicles());
            str("scenario", r.scenario());
            num("durationSeconds", r.durationSeconds());
            key("fixed");
            run(r.fixed());
            key("adaptive");
            run(r.adaptive());
            close();
        }
        close();
    }

    private void run(ComparisonRunner.RunSummary s) {
        open();
        str("mode", s.mode().name());
        num("completedTrips", s.completedTrips());
        num("avgTripTime", s.avgTripTime());
        num("avgTripWait", s.avgTripWait());
        num("avgSpeed", s.avgSpeed());
        num("meanCongestion", s.meanCongestion());
        num("p90TripTime", s.p90TripTime());
        num("throughputPerMin", s.throughputPerMin());
        num("peakQueue", s.peakQueue());
        num("emergencyAvgResponse", s.emergencyAvgResponse());
        num("abandoned", s.abandoned());
        num("stateHash", s.stateHash());
        num("wallMillis", s.wallMillis());
        key("series");
        beginArray();
        List<double[]> rows = s.series();
        for (double[] row : rows) {
            item();
            doubleArray(row);
        }
        endArray();
        close();
    }

    private void scenario(Messages.ScenarioDto s) {
        open();
        str("id", s.id());
        str("title", s.title());
        str("description", s.description());
        num("seed", s.seed());
        str("layout", s.layout());
        num("targetVehicles", s.targetVehicles());
        str("signalMode", s.signalMode());
        str("demandProfile", s.demandProfile());
        num("scriptedSteps", s.scriptedSteps());
        close();
    }

    // ------------------------------------------------------------------ primitives

    private void open() {
        b.append('{');
        first = true;
    }

    private void close() {
        b.append('}');
        first = false;
    }

    private void beginArray() {
        b.append('[');
        first = true;
    }

    private void endArray() {
        b.append(']');
        first = false;
    }

    private void item() {
        if (!first) {
            b.append(',');
        }
        first = true;
    }

    private void key(String name) {
        if (!first) {
            b.append(',');
        }
        quote(name);
        b.append(':');
        first = true;
    }

    private void str(String name, String value) {
        key(name);
        if (value == null) {
            b.append("null");
        } else {
            quote(value);
        }
        first = false;
    }

    private void bool(String name, boolean value) {
        key(name);
        b.append(value);
        first = false;
    }

    private void num(String name, long value) {
        key(name);
        b.append(value);
        first = false;
    }

    private void num(String name, double value) {
        key(name);
        number(value);
        first = false;
    }

    private void optional(String name, Double value) {
        key(name);
        if (value == null) {
            b.append("null");
        } else {
            number(value);
        }
        first = false;
    }

    private void doubles(String name, double[] values) {
        key(name);
        doubleArray(values);
    }

    private void doubleArray(double[] values) {
        b.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            number(values[i]);
        }
        b.append(']');
        first = false;
    }

    private void ints(String name, int[] values) {
        key(name);
        b.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(values[i]);
        }
        b.append(']');
        first = false;
    }

    private void number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            b.append("null");
        } else {
            b.append(v);
        }
    }

    private void quote(String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        b.append("\\u").append("0000".substring(hex.length())).append(hex);
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
