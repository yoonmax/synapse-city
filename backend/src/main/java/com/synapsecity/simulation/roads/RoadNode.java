package com.synapsecity.simulation.roads;

import com.synapsecity.simulation.core.Vec2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A vertex of the road graph: an intersection, a bend or a dead end. */
public final class RoadNode {

    private final int id;
    private final Vec2 position;
    private final List<RoadSegment> outgoing = new ArrayList<>();
    private final List<RoadSegment> incoming = new ArrayList<>();
    private final List<Road> roads = new ArrayList<>();
    /** Clearance radius: lane geometry stops this far from the node centre, leaving room for the junction box. */
    private double radius;

    RoadNode(int id, Vec2 position) {
        this.id = id;
        this.position = position;
    }

    public int id() {
        return id;
    }

    public Vec2 position() {
        return position;
    }

    public List<RoadSegment> outgoing() {
        return Collections.unmodifiableList(outgoing);
    }

    public List<RoadSegment> incoming() {
        return Collections.unmodifiableList(incoming);
    }

    public List<Road> roads() {
        return Collections.unmodifiableList(roads);
    }

    /** Number of distinct roads meeting at this node. */
    public int degree() {
        return roads.size();
    }

    public double radius() {
        return radius;
    }

    void setRadius(double radius) {
        this.radius = radius;
    }

    void addOutgoing(RoadSegment s) {
        outgoing.add(s);
    }

    void addIncoming(RoadSegment s) {
        incoming.add(s);
    }

    void addRoad(Road r) {
        roads.add(r);
    }

    @Override
    public String toString() {
        return "Node#" + id;
    }
}
