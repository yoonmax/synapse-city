package com.synapsecity.simulation.signals;

import com.synapsecity.simulation.agents.Vehicle;
import com.synapsecity.simulation.core.Vec2;
import com.synapsecity.simulation.roads.RoadSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * A lane-to-lane path through a junction box (a "connector"), modelled as a quadratic Bezier
 * from the end of the approach lane to the start of the exit lane. Vehicles inside the box are
 * held in {@link #vehicles()} in front-to-back order.
 */
public final class Movement {

    private static final int SAMPLES = 16;

    private final RoadSegment in;
    private final int inLane;
    private final RoadSegment out;
    private final int outLane;
    private final TurnType turn;
    private final Vec2 p0;
    private final Vec2 control;
    private final Vec2 p2;
    private final double[] arc = new double[SAMPLES + 1];
    private final double length;
    private final List<Vehicle> vehicles = new ArrayList<>();

    Movement(RoadSegment in, int inLane, RoadSegment out, int outLane) {
        this.in = in;
        this.inLane = inLane;
        this.out = out;
        this.outLane = outLane;
        this.turn = TurnType.classify(in.direction(), out.direction());
        this.p0 = in.lanePoint(inLane, in.length());
        this.p2 = out.lanePoint(outLane, 0);
        this.control = computeControl();
        double total = 0;
        Vec2 prev = p0;
        for (int i = 1; i <= SAMPLES; i++) {
            Vec2 p = bezier((double) i / SAMPLES);
            total += p.distance(prev);
            arc[i] = total;
            prev = p;
        }
        this.length = Math.max(0.5, total);
    }

    private Vec2 computeControl() {
        Vec2 d1 = in.direction();
        Vec2 d2 = out.direction();
        Vec2 chord = p2.sub(p0);
        if (turn == TurnType.UTURN) {
            return p0.lerp(p2, 0.5).add(d1.scale(chord.length() * 0.9 + 3));
        }
        double denom = d1.cross(d2);
        if (Math.abs(denom) > 0.05) {
            double a = chord.cross(d2) / denom;
            if (a > 0 && a < chord.length() * 2.5) {
                return p0.add(d1.scale(a));
            }
        }
        return p0.lerp(p2, 0.5);
    }

    private Vec2 bezier(double t) {
        double u = 1 - t;
        return p0.scale(u * u).add(control.scale(2 * u * t)).add(p2.scale(t * t));
    }

    private Vec2 derivative(double t) {
        return control.sub(p0).scale(2 * (1 - t)).add(p2.sub(control).scale(2 * t));
    }

    private double paramAt(double s) {
        if (s <= 0) {
            return 0;
        }
        if (s >= length) {
            return 1;
        }
        for (int i = 1; i <= SAMPLES; i++) {
            if (arc[i] >= s) {
                double f = (s - arc[i - 1]) / Math.max(1e-9, arc[i] - arc[i - 1]);
                return (i - 1 + f) / SAMPLES;
            }
        }
        return 1;
    }

    /** Point {@code s} metres along the connector. */
    public Vec2 pointAt(double s) {
        return bezier(paramAt(s));
    }

    /** Travel heading (radians) {@code s} metres along the connector. */
    public double headingAt(double s) {
        Vec2 d = derivative(paramAt(s));
        return d.length() < 1e-9 ? in.direction().angle() : d.angle();
    }

    /**
     * Whether two movements through the same junction could collide if both were in the box.
     * <ul>
     *   <li>Same lane-to-lane path: no conflict (ordinary car-following applies).</li>
     *   <li>Same exit lane: merge conflict.</li>
     *   <li>Same approach, different lanes: conflict only if the paths cross laterally.</li>
     *   <li>Opposing approaches: conflict if exactly one of them turns across oncoming traffic.</li>
     *   <li>Crossing approaches: conflict unless both are right turns.</li>
     * </ul>
     */
    public boolean conflictsWith(Movement o) {
        if (this == o) {
            return false;
        }
        if (out == o.out && outLane == o.outLane) {
            return true;
        }
        if (in == o.in) {
            if (inLane == o.inLane) {
                return false;
            }
            long dIn = inLane - o.inLane;
            long dOut = exitOrder() - o.exitOrder();
            return dIn * dOut < 0;
        }
        double alignment = in.direction().dot(o.in.direction());
        if (alignment < Math.cos(Math.toRadians(130))) {
            boolean a = turn.crossesOncoming();
            boolean b = o.turn.crossesOncoming();
            return a != b;
        }
        return !(turn == TurnType.RIGHT && o.turn == TurnType.RIGHT);
    }

    private int exitOrder() {
        return turn.rank() * 10 + outLane;
    }

    public RoadSegment in() {
        return in;
    }

    public int inLane() {
        return inLane;
    }

    public RoadSegment out() {
        return out;
    }

    public int outLane() {
        return outLane;
    }

    public TurnType turn() {
        return turn;
    }

    public double length() {
        return length;
    }

    /** Vehicles currently inside this connector, front first. */
    public List<Vehicle> vehicles() {
        return vehicles;
    }
}
