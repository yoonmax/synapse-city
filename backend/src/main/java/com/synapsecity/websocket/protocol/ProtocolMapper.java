package com.synapsecity.websocket.protocol;

import com.synapsecity.simulation.agents.Decision;
import com.synapsecity.simulation.agents.Vehicle;
import com.synapsecity.simulation.core.Incident;
import com.synapsecity.simulation.core.SimulationEngine;
import com.synapsecity.simulation.core.Vec2;
import com.synapsecity.simulation.events.SimEvent;
import com.synapsecity.simulation.roads.Building;
import com.synapsecity.simulation.roads.CityModel;
import com.synapsecity.simulation.roads.Park;
import com.synapsecity.simulation.roads.Road;
import com.synapsecity.simulation.roads.RoadNode;
import com.synapsecity.simulation.roads.RoadSegment;
import com.synapsecity.simulation.roads.StreetLight;
import com.synapsecity.simulation.routing.RouteResult;
import com.synapsecity.simulation.scenarios.ScenarioPreset;
import com.synapsecity.simulation.signals.AdaptiveController;
import com.synapsecity.simulation.signals.Approach;
import com.synapsecity.simulation.signals.Intersection;
import com.synapsecity.simulation.signals.SignalMode;

import java.util.ArrayList;
import java.util.List;

/** Builds wire DTOs from engine state. Must only be called on the simulation thread. */
public final class ProtocolMapper {

    private ProtocolMapper() {
    }

    static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static double r3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    public static Messages.CityMessage city(SimulationEngine engine) {
        CityModel city = engine.city();
        List<Messages.NodeDto> nodes = new ArrayList<>();
        for (RoadNode n : city.graph().nodes()) {
            nodes.add(new Messages.NodeDto(n.id(), r2(n.position().x()), r2(n.position().y()), r2(n.radius()),
                    engine.intersection(n.id()).isSignalized(), n.degree()));
        }
        List<Messages.RoadDto> roads = new ArrayList<>();
        for (Road r : city.graph().roads()) {
            roads.add(new Messages.RoadDto(r.id(), r.a().id(), r.b().id(), r.roadClass().name(), r.lanesPerDirection(),
                    r2(r.halfWidth()), r2(r.roadClass().medianWidth()), r.forward().id(),
                    r.backward() == null ? -1 : r.backward().id(), r.isClosed()));
        }
        List<Messages.SegmentDto> segments = new ArrayList<>();
        for (RoadSegment s : city.graph().segments()) {
            segments.add(new Messages.SegmentDto(s.id(), s.road().id(), s.from().id(), s.to().id(), s.lanes(),
                    r2(s.start().x()), r2(s.start().y()), r2(s.end().x()), r2(s.end().y()), r2(s.length()),
                    r2(s.speedLimit()), r1(s.capacity())));
        }
        List<Messages.ApproachDto> approaches = new ArrayList<>();
        int index = 0;
        for (Intersection ix : engine.intersections()) {
            for (Approach a : ix.approaches()) {
                RoadSegment s = a.segment();
                Vec2 head = s.end().add(s.right().scale(s.road().halfWidth() + 0.6));
                approaches.add(new Messages.ApproachDto(index++, ix.node().id(), s.id(), a.phase(),
                        r2(head.x()), r2(head.y()), r3(s.direction().angle())));
            }
        }
        double[] buildings = new double[city.buildings().size() * 9];
        int k = 0;
        for (Building b : city.buildings()) {
            buildings[k++] = r2(b.x());
            buildings[k++] = r2(b.y());
            buildings[k++] = r2(b.width());
            buildings[k++] = r2(b.depth());
            buildings[k++] = r2(b.height());
            buildings[k++] = r3(b.rotation());
            buildings[k++] = b.style().ordinal();
            buildings[k++] = b.district().ordinal();
            buildings[k++] = b.variant() & 0xffff;
        }
        List<Messages.ParkDto> parks = new ArrayList<>();
        for (Park p : city.parks()) {
            parks.add(new Messages.ParkDto(p.id(), p.name(), r2(p.x()), r2(p.y()), r2(p.radius())));
        }
        double[] trees = new double[city.trees().size() * 2];
        k = 0;
        for (Vec2 t : city.trees()) {
            trees[k++] = r2(t.x());
            trees[k++] = r2(t.y());
        }
        double[] lights = new double[city.streetLights().size() * 3];
        k = 0;
        for (StreetLight l : city.streetLights()) {
            lights[k++] = r2(l.x());
            lights[k++] = r2(l.y());
            lights[k++] = r3(l.rotation());
        }
        List<Messages.BusStopDto> stops = new ArrayList<>();
        for (int segId : city.busStopSegments()) {
            RoadSegment s = city.graph().segment(segId);
            Vec2 p = s.lanePoint(0, s.length() * 0.55).add(s.right().scale(Road.LANE_WIDTH));
            stops.add(new Messages.BusStopDto(segId, r2(p.x()), r2(p.y()), r3(s.direction().angle())));
        }
        return new Messages.CityMessage("city", city.name(), city.seed(), city.layout().name(), r1(city.radius()),
                city.emergencyStationNode(), city.emergencyStationBuilding(), nodes, roads, segments, approaches,
                buildings, Messages.buildingStyleNames(), parks, trees, lights, stops);
    }

    public static Messages.EventDto event(SimEvent e) {
        return new Messages.EventDto(e.id(), e.tick(), r1(e.time()), e.type().name(), e.severity().name(), e.title(),
                e.detail(), e.vehicleId(), e.roadId(), e.nodeId(),
                Double.isNaN(e.x()) ? null : r1(e.x()), Double.isNaN(e.y()) ? null : r1(e.y()));
    }

    public static List<Messages.IncidentDto> incidents(SimulationEngine engine) {
        List<Messages.IncidentDto> out = new ArrayList<>();
        for (Incident i : engine.incidents()) {
            out.add(new Messages.IncidentDto(i.id(), i.roadId(), i.segmentId(), r1(i.startTime()), r1(i.endTime()),
                    r2(i.x()), r2(i.y())));
        }
        return out;
    }

    public static int[] closedRoads(SimulationEngine engine) {
        return engine.graph().roads().stream().filter(Road::isClosed).mapToInt(Road::id).toArray();
    }

    public static String describe(Decision d, int ref, double value) {
        return switch (d) {
            case SPAWNING -> "Merging into traffic";
            case FREE_FLOW -> "Free flow toward junction " + ref;
            case FOLLOWING -> "Car-following vehicle #" + ref + " (IDM gap control)";
            case STOP_RED -> "Red signal at junction " + ref + ": stopping at the line";
            case STOP_YELLOW -> "Amber at junction " + ref + ": stopping, braking distance allows it";
            case YIELD_CONFLICT -> "Yielding: a crossing movement holds junction " + ref;
            case YIELD_PRIORITY -> "Yielding: another driver reached junction " + ref + " first";
            case EXIT_BLOCKED -> "Exit lane full: waiting so junction " + ref + " stays clear";
            case COMMITTED -> "Junction " + ref + " reserved: entering box";
            case TURNING -> "Crossing junction " + ref;
            case ARRIVING -> "Decelerating to destination";
            case DWELLING -> String.format("Dwelling at stop (%.0f s)", value);
            case REROUTED -> value > 0.5 ? String.format("Rerouted: new path saves %.0f s", value)
                    : "Rerouted around a blocked road";
            case ROUTE_BLOCKED -> "Next road " + ref + " closed: replanning";
            case PREEMPTING -> "Requesting signal priority at junction " + ref;
            case DESPAWNING -> "Trip complete: leaving the network";
        };
    }

    public static Messages.VehicleDetail vehicle(SimulationEngine engine, Vehicle v) {
        double t = engine.time();
        List<Messages.DecisionDto> history = new ArrayList<>();
        for (Vehicle.DecisionRecord r : v.history()) {
            history.add(new Messages.DecisionDto(r1(r.time()), r.decision().name(), describe(r.decision(), r.ref(), r.value())));
        }
        List<RoadSegment> route = v.route();
        int idx = Math.min(v.routeIndex(), Math.max(0, route.size() - 1));
        List<Double> pts = new ArrayList<>();
        double remaining = 0;
        if (!route.isEmpty()) {
            if (v.isInConnector()) {
                Vec2 p = new Vec2(v.x(), v.y());
                pts.add(p.x());
                pts.add(p.y());
            }
            for (int i = idx; i < route.size(); i++) {
                RoadSegment s = route.get(i);
                int lane = i == idx && !v.isInConnector() ? v.lane() : 0;
                double from = i == idx && !v.isInConnector() ? v.s() : 0;
                double to = i == route.size() - 1 ? v.destS() : s.length();
                if (i == idx && v.isInConnector()) {
                    continue; // still inside the junction leaving route[idx]
                }
                if (to <= from) {
                    continue;
                }
                Vec2 a = s.lanePoint(lane, from);
                Vec2 b = s.lanePoint(lane, to);
                pts.add(r2(a.x()));
                pts.add(r2(a.y()));
                pts.add(r2(b.x()));
                pts.add(r2(b.y()));
                remaining += to - from;
            }
        }
        double[] routePoints = pts.stream().mapToDouble(Double::doubleValue).toArray();
        int[] routeSegments = route.subList(idx, route.size()).stream().mapToInt(RoadSegment::id).toArray();
        RoadSegment dest = v.destinationSegment();
        Vec2 destP = dest.lanePoint(0, Math.min(v.destS(), dest.length()));
        RouteResult r = v.lastRoute();
        // Expected time uses the standard travel-time model for every vehicle; the emergency profile's
        // inflated congestion weight is a routing preference, not a time estimate.
        double eta = engine.router().pathCost(route, idx + 1, route.size(),
                com.synapsecity.simulation.routing.RouteProfile.STANDARD);
        if (!v.isInConnector() && v.segment() != null) {
            eta += (v.segment().length() - v.s()) / Math.max(1.0, v.segment().effectiveSpeedLimit());
        }
        Messages.RouteAnalysisDto analysis = new Messages.RouteAnalysisDto(
                r == null ? engine.router().algorithm().name() : r.algorithm().name(),
                r == null ? 0 : r.exploredNodes(), r == null ? 0 : r1(r.computeNanos() / 1000.0),
                r == null ? 0 : r1(r.distance()), r1(v.plannedCost()), r1(remaining),
                Double.isFinite(eta) ? r1(eta) : -1, routeSegments.length, v.routeChanges(), r != null);
        Messages.TripDto trip = new Messages.TripDto(r1(t - v.tripStart()), r1(v.tripWaitTime()), r1(v.tripDistance()),
                r1(v.freeFlowEstimate()), r1(v.stoppedFor()));
        RoadSegment seg = v.isInConnector() ? v.connector().in() : v.segment();
        return new Messages.VehicleDetail("vehicle", v.id(), v.type().name(), v.state().name(), r1(t - v.stateSince()),
                v.decision().name(), describe(v.decision(), v.decisionRef(), v.decisionValue()), history,
                r2(v.speed()), r2(v.accel()), seg.speedLimit(), seg.id(), seg.road().id(), v.lane(),
                r2(v.x()), r2(v.y()), r3(v.heading()), routePoints, routeSegments, r2(destP.x()), r2(destP.y()),
                analysis, trip, v.driver(), v.sirenActive(), v.stopIndex(), v.stops().size());
    }

    public static Messages.IntersectionDetail intersection(SimulationEngine engine, Intersection ix) {
        double t = engine.time();
        List<Messages.ApproachDetail> approaches = new ArrayList<>();
        for (Approach a : ix.approaches()) {
            approaches.add(new Messages.ApproachDetail(a.segment().id(), a.segment().road().id(), a.phase(),
                    a.color().name(), a.queue(), a.demand(), r1(a.totalWait()), r1(a.maxWait()), r3(a.headingAngle())));
        }
        List<Messages.PhaseDetail> phases = new ArrayList<>();
        for (int p = 0; p < ix.phaseCount(); p++) {
            int[] segs = ix.phaseApproaches(p).stream().mapToInt(a -> a.segment().id()).toArray();
            phases.add(new Messages.PhaseDetail(p, segs, r2(AdaptiveController.pressure(ix, p)), ix.phaseDemand(p),
                    r1(ix.timeSinceServed(p, t))));
        }
        SignalMode mode = ix.controller() == null ? engine.signalMode() : ix.controller().mode();
        return new Messages.IntersectionDetail("intersection", ix.node().id(), r2(ix.node().position().x()),
                r2(ix.node().position().y()), ix.isSignalized(), ix.isSignalized() ? mode.name() : "UNSIGNALIZED",
                ix.currentPhase(), ix.nextPhase(), ix.phaseCount(), ix.stage().name(), r1(ix.stageElapsed()),
                r1(ix.stageRemaining()), ix.isPreempted(), ix.preemptVehicle(), ix.entries(), ix.phaseChanges(),
                ix.preemptions(), ix.occupants().size(), approaches, phases);
    }

    public static List<Messages.ScenarioDto> scenarios() {
        List<Messages.ScenarioDto> out = new ArrayList<>();
        for (ScenarioPreset p : ScenarioPreset.values()) {
            var c = p.config();
            out.add(new Messages.ScenarioDto(p.name(), p.title(), p.description(), c.seed(), c.layout().name(),
                    c.targetVehicles(), c.signalMode().name(), c.demandProfile().name(), p.script().size()));
        }
        return out;
    }
}
