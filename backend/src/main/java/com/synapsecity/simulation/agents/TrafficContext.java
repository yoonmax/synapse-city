package com.synapsecity.simulation.agents;

import com.synapsecity.simulation.events.EventType;
import com.synapsecity.simulation.events.Severity;
import com.synapsecity.simulation.metrics.MetricsCollector;
import com.synapsecity.simulation.roads.CityModel;
import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadNode;
import com.synapsecity.simulation.routing.Router;
import com.synapsecity.simulation.signals.Intersection;

import java.util.List;
import java.util.SplittableRandom;

/** The view of the world that vehicle dynamics and fleet management need; implemented by the engine. */
public interface TrafficContext {

    double time();

    double dt();

    CityModel city();

    RoadGraph graph();

    TrafficLanes lanes();

    Intersection intersectionAt(RoadNode node);

    List<Intersection> intersections();

    Router router();

    MetricsCollector metrics();

    /** The engine's single seeded random stream; consuming it in a fixed order keeps runs reproducible. */
    SplittableRandom rng();

    void emit(EventType type, Severity severity, String title, String detail,
              int vehicleId, int roadId, int nodeId, double x, double y);
}
