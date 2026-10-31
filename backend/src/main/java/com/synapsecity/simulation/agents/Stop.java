package com.synapsecity.simulation.agents;

import com.synapsecity.simulation.roads.RoadSegment;

/** A waypoint where a bus or delivery vehicle dwells. */
public record Stop(RoadSegment segment, double s, double dwell) {
}
