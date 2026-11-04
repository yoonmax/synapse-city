package com.synapsecity.simulation.agents;

import com.synapsecity.simulation.core.Vec2;
import com.synapsecity.simulation.roads.RoadSegment;
import com.synapsecity.simulation.signals.EntryDecision;
import com.synapsecity.simulation.signals.Intersection;
import com.synapsecity.simulation.signals.Movement;
import com.synapsecity.simulation.signals.TurnType;

import java.util.List;

/**
 * Longitudinal vehicle control and lane/junction transfers.
 *
 * <h2>Car following</h2>
 * Each vehicle accelerates according to the Intelligent Driver Model (Treiber et al., 2000):
 * <pre>
 *   a = a_max * [ 1 - (v / v0)^4 - (s* / s)^2 ],   s* = s0 + v*T + v*dv / (2*sqrt(a_max * b))
 * </pre>
 * where {@code s} is the bumper-to-bumper gap to the relevant obstacle. The obstacle is the
 * leader in the same lane, a virtual stopped vehicle at the stop line (red light, conflict,
 * blocked exit), the destination point, or the last vehicle on the exit lane seen through the
 * junction once entry is permitted. The free-road term is floored so a vehicle entering a slower
 * road decelerates gently instead of braking hard.
 *
 * <h2>Simplifications</h2>
 * Lanes are assigned on entry to each segment based on the next turn; there is no mid-block
 * lane changing or overtaking. Vehicles move in one dimension along lanes and connectors.
 *
 * <p>Updates are synchronous: all accelerations are computed from the same snapshot before any
 * vehicle moves, so the result does not depend on iteration order (except for junction
 * commitments, which are granted in a fixed segment/lane order).</p>
 */
public final class VehicleDynamics {

    public static final double LOOKAHEAD = 95;
    public static final double TURN_SPEED = 6.5;
    public static final double UTURN_SPEED = 3.5;
    public static final double DESTINATION_ZONE = 70;
    private static final double INF = Double.POSITIVE_INFINITY;

    private final TrafficContext ctx;
    private long safetyClamps;

    public VehicleDynamics(TrafficContext ctx) {
        this.ctx = ctx;
    }

    // ------------------------------------------------------------------ decisions

    /** Computes accelerations (and junction commitments) for every vehicle. */
    public void decideAll() {
        TrafficLanes lanes = ctx.lanes();
        for (RoadSegment seg : ctx.graph().segments()) {
            for (int l = 0; l < seg.lanes(); l++) {
                List<Vehicle> list = lanes.lane(seg, l);
                for (int i = 0; i < list.size(); i++) {
                    decideOnSegment(list.get(i), i == 0 ? null : list.get(i - 1));
                }
            }
        }
        for (Intersection ix : ctx.intersections()) {
            for (Movement m : ix.activeMovements()) {
                List<Vehicle> vs = m.vehicles();
                for (int i = 0; i < vs.size(); i++) {
                    decideInConnector(vs.get(i), i == 0 ? null : vs.get(i - 1), m, ix);
                }
            }
        }
    }

    private void decideOnSegment(Vehicle v, Vehicle leader) {
        double t = ctx.time();
        if (v.state == VehicleState.DWELLING) {
            v.accel = v.speed > 0.05 ? -Math.min(4.0, v.speed / ctx.dt()) : 0;
            return;
        }
        if (v.state == VehicleState.DESPAWNING) {
            v.accel = v.speed > 0 ? -Math.max(2.5, v.speed / 0.8) : 0;
            return;
        }
        RoadSegment seg = v.segment;
        double v0 = desiredSpeed(v, seg.effectiveSpeedLimit());
        double gap = INF;
        double leadSpeed = 0;
        Decision d = Decision.FREE_FLOW;
        int ref = seg.to().id();
        if (leader != null) {
            gap = leader.s - leader.length() - v.s;
            leadSpeed = leader.speed;
            d = Decision.FOLLOWING;
            ref = leader.id();
        }
        double distToEnd = seg.length() - v.s;

        if (v.onFinalSegment()) {
            double toDest = v.destS - v.s;
            if (toDest < DESTINATION_ZONE) {
                double g = Math.max(0.05, toDest + v.type().minGap() - 0.5);
                if (g < gap) {
                    gap = g;
                    leadSpeed = 0;
                    d = Decision.ARRIVING;
                    ref = -1;
                }
            }
        } else {
            RoadSegment next = v.nextSegment();
            TurnType turn = TurnType.classify(seg.direction(), next.direction());
            if (turn != TurnType.STRAIGHT) {
                double turnSpeed = turn == TurnType.UTURN ? UTURN_SPEED : TURN_SPEED;
                v0 = Math.min(v0, Math.sqrt(turnSpeed * turnSpeed + 2 * 1.4 * Math.max(0, distToEnd - 1)));
            }
            if (leader == null && distToEnd < LOOKAHEAD) {
                Intersection ix = ctx.intersectionAt(seg.to());
                Movement m = null;
                boolean go = false;
                if (v.movement != null && v.committedAt == ix) {
                    m = v.movement;
                    go = true;
                    d = Decision.COMMITTED;
                    ref = ix.node().id();
                } else if (next.isClosed()) {
                    v.replanRequested = true;
                    d = Decision.ROUTE_BLOCKED;
                    ref = next.road().id();
                } else {
                    m = ix.movement(seg, v.lane, next, chooseExitLane(v, next));
                    EntryDecision e = ix.evaluateEntry(v, m, distToEnd, ctx.lanes());
                    if (e == EntryDecision.GO) {
                        go = true;
                        if (distToEnd <= commitDistance(v)) {
                            ix.commit(v, m);
                            d = Decision.COMMITTED;
                            ref = ix.node().id();
                        }
                    } else {
                        d = switch (e) {
                            case STOP_RED -> Decision.STOP_RED;
                            case STOP_YELLOW -> Decision.STOP_YELLOW;
                            case YIELD_CONFLICT -> Decision.YIELD_CONFLICT;
                            case YIELD_PRIORITY -> Decision.YIELD_PRIORITY;
                            default -> Decision.EXIT_BLOCKED;
                        };
                        ref = ix.node().id();
                        if (!ix.isSignalized() && v.speed < 0.5 && distToEnd < 5) {
                            ix.claim(v, m, t);
                        }
                    }
                }
                if (go) {
                    double[] through = throughObstacle(v, m, ix, distToEnd);
                    if (through != null && through[0] < gap) {
                        gap = through[0];
                        leadSpeed = through[1];
                    }
                } else {
                    double g = Math.max(0.05, distToEnd + v.type().minGap() - 0.6);
                    if (g < gap) {
                        gap = g;
                        leadSpeed = 0;
                    }
                }
            }
        }
        v.accel = idm(v, v0, gap, leadSpeed);
        v.setDecision(d, ref, 0, t);
    }

    private void decideInConnector(Vehicle v, Vehicle leader, Movement m, Intersection ix) {
        if (v.state == VehicleState.DESPAWNING) {
            v.accel = v.speed > 0 ? -Math.max(2.5, v.speed / 0.8) : 0;
            return;
        }
        double v0 = switch (m.turn()) {
            case STRAIGHT -> desiredSpeed(v, m.out().effectiveSpeedLimit());
            case UTURN -> UTURN_SPEED;
            default -> TURN_SPEED;
        };
        double gap = INF;
        double leadSpeed = 0;
        if (leader != null) {
            gap = leader.cs - leader.length() - v.cs;
            leadSpeed = leader.speed;
        } else {
            Vehicle tail = ctx.lanes().tail(m.out(), m.outLane());
            if (tail != null) {
                gap = m.length() - v.cs + tail.s - tail.length();
                leadSpeed = tail.speed;
            }
        }
        // Paths sharing an entry lane overlap for the first few metres of the box.
        if (v.cs < 8) {
            for (Movement other : ix.activeMovements()) {
                if (other == m || other.in() != m.in() || other.inLane() != m.inLane()) {
                    continue;
                }
                for (Vehicle o : other.vehicles()) {
                    if (o.cs > v.cs) {
                        double g = o.cs - o.length() - v.cs;
                        if (g < gap) {
                            gap = g;
                            leadSpeed = o.speed;
                        }
                    }
                }
            }
        }
        v.accel = idm(v, v0, gap, leadSpeed);
        v.setDecision(Decision.TURNING, ix.node().id(), 0, ctx.time());
    }

    /** Nearest obstacle beyond the stop line for a vehicle allowed to enter {@code m}: {gap, speed}. */
    private double[] throughObstacle(Vehicle v, Movement m, Intersection ix, double distToEnd) {
        double best = INF;
        double speed = 0;
        List<Vehicle> inBox = m.vehicles();
        if (!inBox.isEmpty()) {
            Vehicle last = inBox.get(inBox.size() - 1);
            best = distToEnd + last.cs - last.length();
            speed = last.speed;
        } else {
            Vehicle tail = ctx.lanes().tail(m.out(), m.outLane());
            if (tail != null) {
                best = distToEnd + m.length() + tail.s - tail.length();
                speed = tail.speed;
            }
        }
        for (Movement other : ix.activeMovements()) {
            if (other == m || other.in() != m.in() || other.inLane() != m.inLane() || other.vehicles().isEmpty()) {
                continue;
            }
            Vehicle last = other.vehicles().get(other.vehicles().size() - 1);
            double g = distToEnd + last.cs - last.length();
            if (g < best) {
                best = g;
                speed = last.speed;
            }
        }
        return best == INF ? null : new double[]{best, speed};
    }

    /** Lane on the next segment, chosen for the turn that follows it. */
    int chooseExitLane(Vehicle v, RoadSegment next) {
        if (next.lanes() == 1) {
            return 0;
        }
        int nextIndex = v.routeIndex + 1;
        if (nextIndex >= v.route.size() - 1) {
            return 0; // destination segment: kerb lane
        }
        RoadSegment after = v.route.get(nextIndex + 1);
        TurnType t = TurnType.classify(next.direction(), after.direction());
        return switch (t) {
            case RIGHT -> 0;
            case LEFT, UTURN -> next.lanes() - 1;
            case STRAIGHT -> {
                int best = 0;
                double bestFree = -1;
                for (int l = 0; l < next.lanes(); l++) {
                    double free = ctx.lanes().freeSpaceAtStart(next, l);
                    if (free > bestFree + 0.5) {
                        bestFree = free;
                        best = l;
                    }
                }
                yield best;
            }
        };
    }

    private static double commitDistance(Vehicle v) {
        return v.speed * v.speed / (2 * v.type().comfortDecel()) + 10;
    }

    private static double desiredSpeed(Vehicle v, double limit) {
        return Math.max(0.5, limit * v.type().speedFactor() * v.driver().speedFactor());
    }

    /** Intelligent Driver Model acceleration. */
    static double idm(Vehicle v, double v0, double gap, double leaderSpeed) {
        VehicleType t = v.type();
        double a = t.maxAccel() * v.driver().accelFactor();
        double b = t.comfortDecel();
        double headway = t.timeHeadway() * v.driver().headwayFactor();
        double ratio = v.speed / Math.max(v0, 0.1);
        double free = Math.max(-1.0, 1 - ratio * ratio * ratio * ratio);
        double acc;
        if (gap == INF) {
            acc = a * free;
        } else {
            double dv = v.speed - leaderSpeed;
            double sStar = t.minGap() + Math.max(0, v.speed * headway + v.speed * dv / (2 * Math.sqrt(a * b)));
            double g = Math.max(gap, 0.01);
            acc = a * (free - (sStar / g) * (sStar / g));
        }
        return Math.max(-9.0, Math.min(a, acc));
    }

    // ------------------------------------------------------------------ integration and transfers

    /** Ballistic update; speed is clamped at zero without reversing. */
    public void integrate(Vehicle v, double dt) {
        double nv = v.speed + v.accel * dt;
        double ds;
        if (nv < 0) {
            ds = v.accel < 0 ? -v.speed * v.speed / (2 * v.accel) : 0;
            nv = 0;
        } else {
            ds = v.speed * dt + 0.5 * v.accel * dt * dt;
        }
        v.speed = nv;
        v.distance += ds;
        if (v.connector != null) {
            v.cs += ds;
        } else {
            v.s += ds;
        }
    }

    /** Moves vehicles across stop lines into connectors and out of connectors onto exit lanes. */
    public void resolveTransfers() {
        TrafficLanes lanes = ctx.lanes();
        for (RoadSegment seg : ctx.graph().segments()) {
            for (int l = 0; l < seg.lanes(); l++) {
                List<Vehicle> list = lanes.lane(seg, l);
                while (!list.isEmpty()) {
                    Vehicle front = list.get(0);
                    if (front.s < seg.length()) {
                        break;
                    }
                    Movement m = front.movement;
                    if (m != null && front.connector == null && m.in() == seg && m.inLane() == l && !front.onFinalSegment()) {
                        list.remove(0);
                        front.connector = m;
                        front.cs = front.s - seg.length();
                        front.committedAt.enterConnector(front, m);
                    } else {
                        // Not permitted to enter: hold at the stop line.
                        front.s = seg.length() - 0.01;
                        front.speed = 0;
                        break;
                    }
                }
            }
        }
        for (Intersection ix : ctx.intersections()) {
            for (Movement m : ix.activeMovements()) {
                List<Vehicle> vs = m.vehicles();
                while (!vs.isEmpty() && vs.get(0).cs >= m.length()) {
                    Vehicle v = vs.remove(0);
                    v.connector = null;
                    v.routeIndex++;
                    v.segment = m.out();
                    v.lane = m.outLane();
                    v.s = v.cs - m.length();
                    lanes.insert(v);
                }
            }
            ix.pruneInactiveMovements();
        }
    }

    /**
     * Last line of defence against overlap: pushes any follower back behind its leader. With IDM and
     * the box reservation this should rarely trigger; the count is reported as a safety metric.
     */
    public void enforceSpacing() {
        TrafficLanes lanes = ctx.lanes();
        for (RoadSegment seg : ctx.graph().segments()) {
            for (int l = 0; l < seg.lanes(); l++) {
                List<Vehicle> list = lanes.lane(seg, l);
                for (int i = 1; i < list.size(); i++) {
                    Vehicle lead = list.get(i - 1);
                    Vehicle v = list.get(i);
                    double max = lead.s - lead.length() - 0.2;
                    if (v.s > max) {
                        v.s = max;
                        v.speed = Math.min(v.speed, lead.speed);
                        safetyClamps++;
                    }
                }
            }
        }
        for (Intersection ix : ctx.intersections()) {
            for (Movement m : ix.activeMovements()) {
                List<Vehicle> vs = m.vehicles();
                for (int i = 1; i < vs.size(); i++) {
                    Vehicle lead = vs.get(i - 1);
                    Vehicle v = vs.get(i);
                    double max = lead.cs - lead.length() - 0.2;
                    if (v.cs > max) {
                        v.cs = max;
                        v.speed = Math.min(v.speed, lead.speed);
                        safetyClamps++;
                    }
                }
            }
        }
    }

    public long safetyClamps() {
        return safetyClamps;
    }

    // ------------------------------------------------------------------ rendering pose

    /** World-space centre point and heading, continuous across segment/connector boundaries. */
    public void updatePose(Vehicle v) {
        double half = v.length() / 2;
        Vec2 p;
        double h;
        if (v.connector != null) {
            Movement m = v.connector;
            double c = v.cs - half;
            if (c >= 0) {
                p = m.pointAt(c);
                h = m.headingAt(c);
            } else {
                RoadSegment in = m.in();
                p = in.lanePoint(m.inLane(), in.length() + c);
                h = in.direction().angle();
            }
        } else {
            RoadSegment seg = v.segment;
            double c = v.s - half;
            Movement m = v.clearing != null ? v.clearing : v.movement;
            if (c < 0 && m != null && m.out() == seg) {
                double cc = m.length() + c;
                if (cc >= 0) {
                    p = m.pointAt(cc);
                    h = m.headingAt(cc);
                } else {
                    p = m.in().lanePoint(m.inLane(), m.in().length() + cc);
                    h = m.in().direction().angle();
                }
            } else {
                p = seg.lanePoint(v.lane, Math.max(0, c));
                h = seg.direction().angle();
            }
        }
        v.x = p.x();
        v.y = p.y();
        v.heading = h;
        double age = ctx.time() - v.stateSince;
        v.fade = switch (v.state) {
            case SPAWNING -> Math.min(1, age / 0.8);
            case DESPAWNING -> Math.max(0, 1 - age / 1.0);
            default -> 1;
        };
    }
}
