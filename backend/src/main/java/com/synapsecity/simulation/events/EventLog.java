package com.synapsecity.simulation.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Bounded, append-only event log with monotonically increasing ids. */
public final class EventLog {

    public static final int CAPACITY = 400;

    private final Deque<SimEvent> events = new ArrayDeque<>();
    private long nextId = 1;

    public SimEvent add(long tick, double time, EventType type, Severity severity, String title, String detail,
                        int vehicleId, int roadId, int nodeId, double x, double y) {
        SimEvent e = new SimEvent(nextId++, tick, time, type, severity, title, detail, vehicleId, roadId, nodeId, x, y);
        events.addLast(e);
        while (events.size() > CAPACITY) {
            events.removeFirst();
        }
        return e;
    }

    /** Events with id greater than {@code afterId}, oldest first. */
    public List<SimEvent> since(long afterId) {
        List<SimEvent> out = new ArrayList<>();
        for (SimEvent e : events) {
            if (e.id() > afterId) {
                out.add(e);
            }
        }
        return out;
    }

    public List<SimEvent> all() {
        return List.copyOf(events);
    }

    public long lastId() {
        return nextId - 1;
    }

    public int size() {
        return events.size();
    }
}
