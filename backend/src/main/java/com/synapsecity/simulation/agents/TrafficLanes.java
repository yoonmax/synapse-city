package com.synapsecity.simulation.agents;

import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * Lane occupancy index: for every segment lane, the vehicles on it ordered front to back
 * (descending {@code s}). This is the structure car-following and junction detection read.
 */
public final class TrafficLanes {

    private final List<List<List<Vehicle>>> lanes = new ArrayList<>();

    public TrafficLanes(RoadGraph graph) {
        for (RoadSegment s : graph.segments()) {
            List<List<Vehicle>> perLane = new ArrayList<>(s.lanes());
            for (int i = 0; i < s.lanes(); i++) {
                perLane.add(new ArrayList<>());
            }
            lanes.add(perLane);
        }
    }

    public List<Vehicle> lane(RoadSegment s, int lane) {
        return lanes.get(s.id()).get(lane);
    }

    /** Inserts at the position given by the vehicle's current segment, lane and {@code s}. */
    public void insert(Vehicle v) {
        List<Vehicle> list = lane(v.segment, v.lane);
        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).s >= v.s) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        list.add(lo, v);
    }

    public void remove(Vehicle v) {
        if (v.segment != null) {
            lane(v.segment, v.lane).remove(v);
        }
    }

    public Vehicle tail(RoadSegment s, int lane) {
        List<Vehicle> list = lane(s, lane);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    /** Free road space from the lane start to the rear of the last vehicle. */
    public double freeSpaceAtStart(RoadSegment s, int lane) {
        Vehicle t = tail(s, lane);
        return t == null ? s.length() : t.s - t.length();
    }

    public int count(RoadSegment s) {
        int n = 0;
        for (List<Vehicle> l : lanes.get(s.id())) {
            n += l.size();
        }
        return n;
    }

    /**
     * Whether a vehicle of {@code length} could be placed with its front at {@code front} without
     * forcing anyone to brake hard: room ahead for a standstill gap plus headway, room behind for
     * the follower's safe gap.
     */
    public boolean hasInsertionGap(RoadSegment s, int lane, double front, double length, double speed) {
        if (front - length < 0.5 || front > s.length() - 2) {
            return false;
        }
        for (Vehicle o : lane(s, lane)) {
            if (o.s >= front) {
                double gapAhead = (o.s - o.length()) - front;
                if (gapAhead < 4 + speed * 1.2) {
                    return false;
                }
            } else {
                double gapBehind = (front - length) - o.s;
                if (gapBehind < 4 + o.speed * 1.4) {
                    return false;
                }
            }
        }
        return true;
    }

    public void clear() {
        for (List<List<Vehicle>> perLane : lanes) {
            for (List<Vehicle> l : perLane) {
                l.clear();
            }
        }
    }
}
