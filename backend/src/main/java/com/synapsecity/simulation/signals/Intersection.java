package com.synapsecity.simulation.signals;

import com.synapsecity.simulation.agents.TrafficLanes;
import com.synapsecity.simulation.agents.Vehicle;
import com.synapsecity.simulation.roads.RoadClass;
import com.synapsecity.simulation.roads.RoadNode;
import com.synapsecity.simulation.roads.RoadSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Junction management for one node: signal phasing, the junction-box reservation system and
 * per-approach detection.
 *
 * <h2>Phasing</h2>
 * Approaches whose travel directions are roughly opposite (more than 130 degrees apart) are paired
 * into one phase; the remaining approaches get a phase each. Only one phase is ever non-red, and
 * every phase change passes through amber then all-red: this is the signal safety invariant.
 *
 * <h2>Box reservation</h2>
 * A vehicle must <em>commit</em> before entering the box. A commitment is granted only if the signal
 * permits it, no conflicting {@link Movement} is committed or inside the box, and the exit lane has
 * room for the whole vehicle (so committed vehicles can never be trapped inside the box).
 * Signals decide who may go, and the reservation guarantees that conflicting paths are never
 * occupied at the same time.
 */
public final class Intersection {

    public static final double YELLOW_TIME = 3.0;
    public static final double ALL_RED_TIME = 1.5;
    /** Minimum green for the outgoing phase before an emergency preemption may cut it short. */
    public static final double PREEMPT_MIN_GREEN = 2.0;
    public static final double DETECTION_RANGE = 120.0;

    private final RoadNode node;
    private final boolean signalized;
    private final List<Approach> approaches = new ArrayList<>();
    private final Map<Integer, Approach> approachBySegment = new HashMap<>();
    private final List<List<Approach>> phases = new ArrayList<>();
    private final Map<Long, Movement> movementCache = new LinkedHashMap<>();
    private final List<Movement> activeMovements = new ArrayList<>();
    private final List<Reservation> reservations = new ArrayList<>();
    private final List<Claim> claims = new ArrayList<>();

    private SignalController controller;
    private int currentPhase;
    private int nextPhase;
    private SignalStage stage = SignalStage.GREEN;
    private double stageElapsed;
    private double[] lastServed;

    private int preemptPhase = -1;
    private int preemptVehicle = -1;
    private double preemptRefreshed;

    private long entries;
    private long phaseChanges;
    private long preemptions;

    private record Claim(Vehicle vehicle, Movement movement, double since) {
    }

    /** A vehicle's right to one path through this box, held from commitment until its rear clears. */
    public record Reservation(Vehicle vehicle, Movement movement) {
    }

    public Intersection(RoadNode node) {
        this.node = node;
        List<RoadSegment> incoming = new ArrayList<>(node.incoming());
        incoming.sort((a, b) -> Integer.compare(a.id(), b.id()));
        for (RoadSegment s : incoming) {
            Approach a = new Approach(approaches.size(), s);
            approaches.add(a);
            approachBySegment.put(s.id(), a);
        }
        boolean major = false;
        for (Approach a : approaches) {
            major |= a.segment().roadClass() != RoadClass.LOCAL;
        }
        this.signalized = approaches.size() >= 4 || (approaches.size() == 3 && major);
        buildPhases();
        this.lastServed = new double[phases.size()];
        updateColors();
    }

    private void buildPhases() {
        int n = approaches.size();
        boolean[] used = new boolean[n];
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dot = approaches.get(i).segment().direction().dot(approaches.get(j).segment().direction());
                if (dot < Math.cos(Math.toRadians(130))) {
                    pairs.add(new int[]{i, j, (int) Math.round(dot * 1e6)});
                }
            }
        }
        // Most opposed pairs first; ties resolved by index for determinism.
        pairs.sort((p, q) -> p[2] != q[2] ? Integer.compare(p[2], q[2])
                : p[0] != q[0] ? Integer.compare(p[0], q[0]) : Integer.compare(p[1], q[1]));
        for (int[] p : pairs) {
            if (!used[p[0]] && !used[p[1]]) {
                used[p[0]] = used[p[1]] = true;
                phases.add(new ArrayList<>(List.of(approaches.get(p[0]), approaches.get(p[1]))));
            }
        }
        for (int i = 0; i < n; i++) {
            if (!used[i]) {
                phases.add(new ArrayList<>(List.of(approaches.get(i))));
            }
        }
        // Serve phases carrying the busiest road class first.
        phases.sort((p, q) -> Integer.compare(rank(p), rank(q)));
        for (int p = 0; p < phases.size(); p++) {
            for (Approach a : phases.get(p)) {
                a.setPhase(p);
            }
        }
    }

    private static int rank(List<Approach> phase) {
        int best = Integer.MAX_VALUE;
        for (Approach a : phase) {
            best = Math.min(best, a.segment().roadClass().ordinal());
        }
        return best;
    }

    // ------------------------------------------------------------------ signal control

    /**
     * Installs a controller. For fixed-time control an offset (seconds into the cycle) places the
     * intersection mid-cycle so neighbouring signals are staggered.
     */
    public void setController(SignalController controller, double time, double offset) {
        this.controller = controller;
        if (!signalized || offset < 0) {
            return;
        }
        double cycle = FixedTimeController.cycleLength(this);
        double pos = offset % cycle;
        for (int p = 0; p < phases.size(); p++) {
            double green = FixedTimeController.greenTime(this, p);
            if (pos < green) {
                setStage(p, SignalStage.GREEN, pos);
                break;
            }
            pos -= green;
            if (pos < YELLOW_TIME) {
                setStage(p, SignalStage.YELLOW, pos);
                break;
            }
            pos -= YELLOW_TIME;
            if (pos < ALL_RED_TIME || p == phases.size() - 1) {
                setStage(p, SignalStage.ALL_RED, Math.min(pos, ALL_RED_TIME - 0.01));
                break;
            }
            pos -= ALL_RED_TIME;
        }
        updateColors();
    }

    private void setStage(int phase, SignalStage s, double elapsed) {
        currentPhase = phase;
        nextPhase = (phase + 1) % phases.size();
        stage = s;
        stageElapsed = elapsed;
    }

    public void updateSignal(double dt, double time) {
        if (!signalized || controller == null) {
            return;
        }
        stageElapsed += dt;
        if (preemptPhase >= 0 && time - preemptRefreshed > 1.0) {
            preemptPhase = -1;
            preemptVehicle = -1;
        }
        switch (stage) {
            case GREEN -> {
                if (preemptPhase >= 0) {
                    if (preemptPhase != currentPhase && stageElapsed >= PREEMPT_MIN_GREEN) {
                        beginClearance(preemptPhase);
                    }
                } else if (controller.shouldTerminateGreen(this, time)) {
                    int next = controller.selectNextPhase(this, time);
                    if (next != currentPhase) {
                        beginClearance(next);
                    }
                }
            }
            case YELLOW -> {
                if (stageElapsed >= YELLOW_TIME) {
                    stage = SignalStage.ALL_RED;
                    stageElapsed = 0;
                }
            }
            case ALL_RED -> {
                if (stageElapsed >= ALL_RED_TIME) {
                    lastServed[currentPhase] = time;
                    if (preemptPhase >= 0) {
                        nextPhase = preemptPhase;
                    }
                    currentPhase = nextPhase;
                    stage = SignalStage.GREEN;
                    stageElapsed = 0;
                    phaseChanges++;
                }
            }
        }
        updateColors();
    }

    private void beginClearance(int next) {
        nextPhase = next;
        stage = SignalStage.YELLOW;
        stageElapsed = 0;
    }

    private void updateColors() {
        for (Approach a : approaches) {
            LightColor c;
            if (!signalized) {
                c = LightColor.GREEN;
            } else if (a.phase() != currentPhase) {
                c = LightColor.RED;
            } else {
                c = switch (stage) {
                    case GREEN -> LightColor.GREEN;
                    case YELLOW -> LightColor.YELLOW;
                    case ALL_RED -> LightColor.RED;
                };
            }
            a.setColor(c);
        }
    }

    /**
     * Emergency vehicle priority request for the phase serving {@code approach}. The request must
     * be refreshed every tick; the first active requester holds it until released or expired.
     *
     * @return true if this call newly activated a preemption
     */
    public boolean requestPreemption(Approach approach, int vehicleId, double time) {
        if (!signalized) {
            return false;
        }
        if (preemptPhase >= 0 && preemptVehicle != vehicleId) {
            return false;
        }
        boolean fresh = preemptPhase < 0;
        preemptPhase = approach.phase();
        preemptVehicle = vehicleId;
        preemptRefreshed = time;
        if (fresh) {
            preemptions++;
        }
        return fresh;
    }

    // ------------------------------------------------------------------ detection

    /** Refreshes queue, demand and waiting-time measurements for every approach. */
    public void measure(TrafficLanes lanes) {
        for (Approach a : approaches) {
            RoadSegment s = a.segment();
            int queue = 0;
            int demand = 0;
            double total = 0;
            double max = 0;
            for (int lane = 0; lane < s.lanes(); lane++) {
                for (Vehicle v : lanes.lane(s, lane)) {
                    double dist = s.length() - v.s();
                    if (dist > DETECTION_RANGE) {
                        break;
                    }
                    demand++;
                    if (v.speed() < 1.0) {
                        queue++;
                        total += v.stoppedFor();
                        max = Math.max(max, v.stoppedFor());
                    }
                }
            }
            a.queue = queue;
            a.demand = demand;
            a.totalWait = total;
            a.maxWait = max;
        }
    }

    // ------------------------------------------------------------------ box reservation

    public Movement movement(RoadSegment in, int inLane, RoadSegment out, int outLane) {
        long key = ((long) in.id() * 8 + inLane) * 1_000_000L + (long) out.id() * 8 + outLane;
        return movementCache.computeIfAbsent(key, k -> new Movement(in, inLane, out, outLane));
    }

    public EntryDecision evaluateEntry(Vehicle v, Movement m, double distToLine, TrafficLanes lanes) {
        Approach a = approachBySegment.get(m.in().id());
        if (signalized) {
            LightColor c = a.color();
            if (c == LightColor.RED) {
                return EntryDecision.STOP_RED;
            }
            if (c == LightColor.YELLOW) {
                double stopping = v.speed() * v.speed() / (2 * v.type().comfortDecel() * 1.3);
                if (stopping < distToLine - 0.5) {
                    return EntryDecision.STOP_YELLOW;
                }
            }
        }
        for (Reservation r : reservations) {
            if (r.vehicle() != v && m.conflictsWith(r.movement())) {
                return EntryDecision.YIELD_CONFLICT;
            }
        }
        if (!signalized) {
            double mine = Double.POSITIVE_INFINITY;
            for (Claim c : claims) {
                if (c.vehicle() == v) {
                    mine = c.since();
                }
            }
            for (Claim c : claims) {
                if (c.vehicle() != v && c.since() < mine && m.conflictsWith(c.movement())) {
                    return EntryDecision.YIELD_PRIORITY;
                }
            }
        }
        double free = lanes.freeSpaceAtStart(m.out(), m.outLane()) - reservedLength(m.out(), m.outLane());
        if (free < v.length() + v.type().minGap() + 1.0) {
            return EntryDecision.EXIT_BLOCKED;
        }
        return EntryDecision.GO;
    }

    private double reservedLength(RoadSegment out, int lane) {
        double total = 0;
        for (Reservation r : reservations) {
            Movement m = r.movement();
            Vehicle o = r.vehicle();
            if (m.out() == out && m.outLane() == lane && o.segment() != out) {
                total += o.length() + o.type().minGap();
            }
        }
        return total;
    }

    public void commit(Vehicle v, Movement m) {
        reservations.add(new Reservation(v, m));
        v.assignMovement(this, m);
        removeClaim(v);
        entries++;
    }

    /** Called once the vehicle's rear has fully left the box. */
    public void release(Vehicle v) {
        reservations.removeIf(r -> r.vehicle() == v);
    }

    /** Unsignalised junctions: register that {@code v} is stopped at the line waiting for {@code m}. */
    public void claim(Vehicle v, Movement m, double time) {
        for (int i = 0; i < claims.size(); i++) {
            Claim c = claims.get(i);
            if (c.vehicle() == v) {
                if (c.movement() != m) {
                    claims.set(i, new Claim(v, m, c.since()));
                }
                return;
            }
        }
        claims.add(new Claim(v, m, time));
    }

    public void removeClaim(Vehicle v) {
        claims.removeIf(c -> c.vehicle() == v);
    }

    /** Called by the engine when a vehicle is removed from the simulation. */
    public void forget(Vehicle v) {
        reservations.removeIf(r -> r.vehicle() == v);
        removeClaim(v);
    }

    void activate(Movement m) {
        if (!activeMovements.contains(m)) {
            activeMovements.add(m);
        }
    }

    /** Adds a vehicle to a connector; keeps the active-connector list in sync. */
    public void enterConnector(Vehicle v, Movement m) {
        m.vehicles().add(v);
        activate(m);
    }

    public void pruneInactiveMovements() {
        activeMovements.removeIf(m -> m.vehicles().isEmpty());
    }

    // ------------------------------------------------------------------ accessors

    public RoadNode node() {
        return node;
    }

    public boolean isSignalized() {
        return signalized;
    }

    public List<Approach> approaches() {
        return Collections.unmodifiableList(approaches);
    }

    public Approach approachFor(RoadSegment in) {
        return approachBySegment.get(in.id());
    }

    public int phaseCount() {
        return phases.size();
    }

    public List<Approach> phaseApproaches(int phase) {
        return Collections.unmodifiableList(phases.get(phase));
    }

    public boolean phaseHasMajorRoad(int phase) {
        for (Approach a : phases.get(phase)) {
            if (a.segment().roadClass() != RoadClass.LOCAL) {
                return true;
            }
        }
        return false;
    }

    public int phaseDemand(int phase) {
        int d = 0;
        for (Approach a : phases.get(phase)) {
            d += a.demand();
        }
        return d;
    }

    public double timeSinceServed(int phase, double time) {
        if (phase == currentPhase && stage == SignalStage.GREEN) {
            return 0;
        }
        return time - lastServed[phase];
    }

    public int currentPhase() {
        return currentPhase;
    }

    public int nextPhase() {
        return nextPhase;
    }

    public SignalStage stage() {
        return stage;
    }

    public double stageElapsed() {
        return stageElapsed;
    }

    public SignalController controller() {
        return controller;
    }

    /** Seconds until the current stage ends, or -1 when it depends on demand. */
    public double stageRemaining() {
        if (!signalized || controller == null) {
            return -1;
        }
        return switch (stage) {
            case GREEN -> preemptPhase >= 0 ? -1 : controller.plannedGreenRemaining(this);
            case YELLOW -> Math.max(0, YELLOW_TIME - stageElapsed);
            case ALL_RED -> Math.max(0, ALL_RED_TIME - stageElapsed);
        };
    }

    public boolean isPreempted() {
        return preemptPhase >= 0;
    }

    public int preemptVehicle() {
        return preemptVehicle;
    }

    public List<Reservation> reservations() {
        return Collections.unmodifiableList(reservations);
    }

    public List<Movement> activeMovements() {
        return activeMovements;
    }

    public long entries() {
        return entries;
    }

    public long phaseChanges() {
        return phaseChanges;
    }

    public long preemptions() {
        return preemptions;
    }

    /** Total stopped vehicles across all approaches. */
    public int totalQueue() {
        int q = 0;
        for (Approach a : approaches) {
            q += a.queue();
        }
        return q;
    }

    /** Safety invariant: at most one phase shows a non-red indication. */
    public boolean satisfiesSignalInvariant() {
        int nonRedPhase = -1;
        for (Approach a : approaches) {
            if (a.color() != LightColor.RED && signalized) {
                if (nonRedPhase >= 0 && nonRedPhase != a.phase()) {
                    return false;
                }
                nonRedPhase = a.phase();
            }
        }
        return true;
    }
}
