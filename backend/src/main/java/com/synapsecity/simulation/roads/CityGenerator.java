package com.synapsecity.simulation.roads;

import com.synapsecity.simulation.core.Vec2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.synapsecity.simulation.core.SeededRandom;

/**
 * Seeded procedural city generator. The same (seed, layout) pair always yields an identical
 * city: every random draw comes from one {@link SeededRandom} consumed in a fixed order,
 * and all collections are iterated in insertion order.
 *
 * <p>Pipeline: street blueprint for the layout, then carve and prune (no dead ends, single
 * strongly connected component), then freeze the {@link RoadGraph}, then parks, building
 * frontages, street trees, street lights, bus stops and the emergency station.</p>
 */
public final class CityGenerator {

    private static final String[] NAME_A = {
        "Meridian", "Halcyon", "Vesper", "Corvin", "Aster", "Lumen", "Novara", "Kestrel",
        "Solenne", "Orrin", "Talvik", "Marrow", "Celest", "Iridia", "Varenne", "Obsidian"
    };
    private static final String[] NAME_B = {
        "Reach", "Harbor", "Heights", "Point", "Basin", "Crossing", "Vale", "Quarter", "Gate", "Bay"
    };
    private static final String[] PARK_NAMES = {
        "Commons", "Green", "Gardens", "Arboretum", "Meadow", "Fields"
    };

    private final long seed;
    private final CityLayout layout;
    private final SeededRandom rng;
    private final Blueprint bp = new Blueprint();

    private CityGenerator(long seed, CityLayout layout) {
        this.seed = seed;
        this.layout = layout;
        this.rng = new SeededRandom(seed * 0x9E3779B97F4A7C15L + layout.ordinal());
    }

    public static CityModel generate(long seed, CityLayout layout) {
        return new CityGenerator(seed, layout).run();
    }

    private CityModel run() {
        switch (layout) {
            case GRID -> buildGrid();
            case RADIAL -> buildRadial();
            case ORGANIC -> buildOrganic();
        }
        bp.pruneDeadEnds();
        bp.keepLargestComponent();
        RoadGraph graph = bp.build();
        graph.finalizeGeometry();

        double radius = 0;
        for (RoadNode n : graph.nodes()) {
            radius = Math.max(radius, n.position().length());
        }
        RoadIndex roadIndex = new RoadIndex(graph);
        List<Park> parks = placeParks(graph, roadIndex, radius);
        List<Building> buildings = new ArrayList<>();
        SpatialHash buildingHash = new SpatialHash(36);
        List<Rect> rects = new ArrayList<>();
        placeBuildings(graph, roadIndex, parks, radius, buildings, rects, buildingHash);
        List<Vec2> trees = placeTrees(graph, parks, radius, rects, buildingHash);
        List<StreetLight> lights = placeStreetLights(graph);
        List<Integer> busStops = chooseBusStops(graph);
        int station = chooseStationNode(graph, radius);
        int stationBuilding = markStationBuilding(graph.node(station).position(), buildings);
        String name = NAME_A[(int) Math.floorMod(seed * 31 + layout.ordinal(), NAME_A.length)] + " "
                + NAME_B[(int) Math.floorMod(seed / 7 + layout.ordinal() * 3L, NAME_B.length)];
        return new CityModel(name, seed, layout, graph, List.copyOf(buildings), List.copyOf(parks),
                List.copyOf(trees), List.copyOf(lights), List.copyOf(busStops), station, stationBuilding, radius);
    }

    // ------------------------------------------------------------------ street networks

    private void buildGrid() {
        int n = 9;
        double spacing = 96;
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            boolean edge = i == 0 || i == n - 1;
            xs[i] = (i - (n - 1) / 2.0) * spacing + (edge ? 0 : rng.nextDouble(-9, 9));
            ys[i] = (i - (n - 1) / 2.0) * spacing + (edge ? 0 : rng.nextDouble(-9, 9));
        }
        int[][] id = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                id[i][j] = bp.addPoint(new Vec2(xs[i], ys[j]));
            }
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i + 1 < n; i++) {
                bp.addEdge(id[i][j], id[i + 1][j], gridClass(j));
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j + 1 < n; j++) {
                bp.addEdge(id[i][j], id[i][j + 1], gridClass(i));
            }
        }
        bp.carveLocalEdges(rng, 0.16, 3);
    }

    private static RoadClass gridClass(int index) {
        if (index % 4 == 0) {
            return RoadClass.ARTERIAL;
        }
        return index % 2 == 0 ? RoadClass.COLLECTOR : RoadClass.LOCAL;
    }

    private void buildRadial() {
        int spokes = 10;
        double[] radii = {80, 178, 288, 398, 505};
        double rotation = rng.nextDouble(0, Math.PI * 2 / spokes);
        List<int[]> rings = new ArrayList<>();
        for (int k = 0; k < radii.length; k++) {
            int count = k >= 2 ? spokes * 2 : spokes;
            int[] ring = new int[count];
            for (int m = 0; m < count; m++) {
                double a = rotation + 2 * Math.PI * m / count;
                if (k >= 2 && m % 2 == 1) {
                    a += rng.nextDouble(-0.035, 0.035);
                }
                double r = radii[k] * (k == 0 ? 1 : 1 + rng.nextDouble(-0.025, 0.025));
                ring[m] = bp.addPoint(Vec2.fromAngle(a).scale(r));
            }
            rings.add(ring);
        }
        for (int k = 0; k < radii.length; k++) {
            int[] ring = rings.get(k);
            RoadClass c = k == 2 ? RoadClass.ARTERIAL : (k == 0 || k == radii.length - 1) ? RoadClass.COLLECTOR : RoadClass.LOCAL;
            for (int m = 0; m < ring.length; m++) {
                bp.addEdge(ring[m], ring[(m + 1) % ring.length], c);
            }
        }
        for (int s = 0; s < spokes; s++) {
            RoadClass c = s % 2 == 0 ? RoadClass.ARTERIAL : RoadClass.COLLECTOR;
            for (int k = 0; k + 1 < radii.length; k++) {
                int[] inner = rings.get(k);
                int[] outer = rings.get(k + 1);
                bp.addEdge(inner[s * (inner.length / spokes)], outer[s * (outer.length / spokes)], c);
            }
        }
        for (int k = 2; k + 1 < radii.length; k++) {
            int[] inner = rings.get(k);
            int[] outer = rings.get(k + 1);
            for (int m = 1; m < inner.length; m += 2) {
                bp.addEdge(inner[m], outer[m], RoadClass.LOCAL);
            }
        }
        bp.carveLocalEdges(rng, 0.10, 3);
    }

    private void buildOrganic() {
        int n = 11;
        double spacing = 80;
        double jitter = 20;
        double c = (n - 1) / 2.0;
        double radius = spacing * c;
        double p1 = rng.nextDouble(0, Math.PI * 2);
        double p2 = rng.nextDouble(0, Math.PI * 2);
        int[][] id = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                Vec2 p = new Vec2((i - c) * spacing + rng.nextDouble(-jitter, jitter),
                        (j - c) * spacing + rng.nextDouble(-jitter, jitter));
                double theta = p.angle();
                double limit = radius * (0.84 + 0.11 * Math.sin(3 * theta + p1) + 0.07 * Math.sin(5 * theta + p2));
                id[i][j] = p.length() < limit ? bp.addPoint(p) : -1;
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (id[i][j] < 0) {
                    continue;
                }
                if (i + 1 < n && id[i + 1][j] >= 0 && (j == 5 || rng.nextDouble() < 0.93)) {
                    RoadClass rc = j == 5 ? RoadClass.ARTERIAL : (j == 2 || j == 8) ? RoadClass.COLLECTOR : RoadClass.LOCAL;
                    bp.addEdge(id[i][j], id[i + 1][j], rc);
                }
                if (j + 1 < n && id[i][j + 1] >= 0 && (i == 4 || rng.nextDouble() < 0.93)) {
                    RoadClass rc = i == 4 ? RoadClass.ARTERIAL : (i == 1 || i == 8) ? RoadClass.COLLECTOR : RoadClass.LOCAL;
                    bp.addEdge(id[i][j], id[i][j + 1], rc);
                }
            }
        }
        // One diagonal boulevard plus scattered diagonal shortcuts (at most one diagonal per cell).
        for (int i = 0; i + 1 < n; i++) {
            for (int j = 0; j + 1 < n; j++) {
                int a = id[i][j], b = id[i + 1][j], d = id[i][j + 1], e = id[i + 1][j + 1];
                if (a < 0 || b < 0 || d < 0 || e < 0) {
                    continue;
                }
                if (i == j && i >= 1 && i <= n - 3) {
                    bp.addEdge(a, e, RoadClass.ARTERIAL);
                } else if (rng.nextDouble() < 0.18) {
                    if (rng.nextBoolean()) {
                        bp.addEdge(a, e, RoadClass.LOCAL);
                    } else {
                        bp.addEdge(b, d, RoadClass.LOCAL);
                    }
                }
            }
        }
        bp.carveLocalEdges(rng, 0.08, 3);
    }

    // ------------------------------------------------------------------ land use

    private static District districtAt(Vec2 p, double radius) {
        double dc = p.length() / radius;
        if (dc < 0.30) {
            return District.DOWNTOWN;
        }
        return dc < 0.60 ? District.MIDTOWN : District.RESIDENTIAL;
    }

    private List<Park> placeParks(RoadGraph graph, RoadIndex roads, double radius) {
        List<Park> parks = new ArrayList<>();
        if (layout == CityLayout.RADIAL) {
            double clearance = roads.clearance(Vec2.ZERO);
            if (clearance > 20) {
                parks.add(new Park(0, "Central Plaza", 0, 0, clearance - 5));
            }
        }
        int target = layout == CityLayout.RADIAL ? 4 : 3;
        List<Vec2> candidates = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            double r = radius * Math.sqrt(rng.nextDouble(0.04, 0.85));
            candidates.add(Vec2.fromAngle(rng.nextDouble(0, Math.PI * 2)).scale(r));
        }
        double[] scores = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            scores[i] = roads.clearance(candidates.get(i));
        }
        while (parks.size() < target) {
            int best = -1;
            double bestScore = 25;
            for (int i = 0; i < candidates.size(); i++) {
                Vec2 c = candidates.get(i);
                if (scores[i] <= bestScore || c.length() < radius * 0.14) {
                    continue;
                }
                double r = Math.min(scores[i] - 4, 72);
                boolean clear = true;
                for (Park p : parks) {
                    if (c.distance(new Vec2(p.x(), p.y())) < p.radius() + r + 70) {
                        clear = false;
                        break;
                    }
                }
                if (clear) {
                    best = i;
                    bestScore = scores[i];
                }
            }
            if (best < 0) {
                break;
            }
            Vec2 c = candidates.get(best);
            String name = NAME_A[(int) Math.floorMod(seed + parks.size() * 5L, NAME_A.length)] + " "
                    + PARK_NAMES[parks.size() % PARK_NAMES.length];
            parks.add(new Park(parks.size(), name, c.x(), c.y(), Math.min(bestScore - 4, 72)));
        }
        return parks;
    }

    private void placeBuildings(RoadGraph graph, RoadIndex roads, List<Park> parks, double radius,
                                List<Building> out, List<Rect> rects, SpatialHash hash) {
        for (Road road : graph.roads()) {
            Vec2 a = road.a().position();
            Vec2 b = road.b().position();
            Vec2 dir = b.sub(a).normalized();
            Vec2 normal = dir.rightNormal();
            double len = a.distance(b);
            boolean arterial = road.roadClass() == RoadClass.ARTERIAL;
            for (int side = 1; side >= -1; side -= 2) {
                double t = road.a().radius() + 4;
                double tEnd = len - road.b().radius() - 4;
                while (t < tEnd - 7) {
                    Vec2 probe = a.add(dir.scale(t));
                    District district = districtAt(probe, radius);
                    double w, depth, gap, setback;
                    switch (district) {
                        case DOWNTOWN -> {
                            w = rng.nextDouble(16, 30);
                            depth = rng.nextDouble(16, 30);
                            gap = rng.nextDouble(1.5, 3.5);
                            setback = 3.0;
                        }
                        case MIDTOWN -> {
                            w = rng.nextDouble(13, 24);
                            depth = rng.nextDouble(13, 22);
                            gap = rng.nextDouble(2, 4.5);
                            setback = 4.0;
                        }
                        default -> {
                            w = rng.nextDouble(8, 14);
                            depth = rng.nextDouble(9, 14);
                            gap = rng.nextDouble(3, 6.5);
                            setback = 5.0;
                        }
                    }
                    if (t + w > tEnd) {
                        w = tEnd - t;
                    }
                    if (w < 7) {
                        break;
                    }
                    double heightRoll = rng.nextDouble();
                    int variant = rng.nextInt();
                    boolean placed = false;
                    for (double depthScale : new double[]{1.0, 0.62}) {
                        double d = depth * depthScale;
                        Vec2 centre = a.add(dir.scale(t + w / 2))
                                .add(normal.scale(side * (road.halfWidth() + setback + d / 2)));
                        if (centre.length() > radius * 1.08) {
                            break;
                        }
                        Rect rect = new Rect(centre, dir, w / 2, d / 2);
                        if (!roads.rectIsClear(rect, 1.5) || overlapsParks(rect, parks) || hash.overlaps(rect, rects, 1.2)) {
                            continue;
                        }
                        double dc = centre.length() / radius;
                        double height;
                        BuildingStyle style;
                        switch (district) {
                            case DOWNTOWN -> {
                                height = 38 + 132 * Math.pow(heightRoll, 1.7) * (1 - dc / 0.30 * 0.45);
                                style = height > 82 && (variant & 1) == 0 ? BuildingStyle.SETBACK_TOWER
                                        : height > 55 ? BuildingStyle.TOWER : BuildingStyle.OFFICE;
                            }
                            case MIDTOWN -> {
                                height = 14 + 34 * Math.pow(heightRoll, 1.3);
                                style = (variant & 2) == 0 ? BuildingStyle.OFFICE : BuildingStyle.APARTMENT;
                            }
                            default -> {
                                height = 5 + 10 * Math.pow(heightRoll, 1.5);
                                style = height < 10 ? BuildingStyle.HOUSE : BuildingStyle.APARTMENT;
                            }
                        }
                        if (arterial) {
                            height *= 1.15;
                        }
                        Building bld = new Building(out.size(), centre.x(), centre.y(), w, d, height,
                                dir.angle(), style, district, variant);
                        hash.insert(rect, rects.size());
                        rects.add(rect);
                        out.add(bld);
                        placed = true;
                        break;
                    }
                    t += placed ? w + gap : 5;
                }
            }
        }
    }

    private static boolean overlapsParks(Rect r, List<Park> parks) {
        for (Park p : parks) {
            if (r.distanceTo(new Vec2(p.x(), p.y())) < p.radius() + 2) {
                return true;
            }
        }
        return false;
    }

    private List<Vec2> placeTrees(RoadGraph graph, List<Park> parks, double radius, List<Rect> rects, SpatialHash hash) {
        List<Vec2> trees = new ArrayList<>();
        for (Park p : parks) {
            double step = 7.5;
            for (double x = p.x() - p.radius(); x <= p.x() + p.radius(); x += step) {
                for (double y = p.y() - p.radius(); y <= p.y() + p.radius(); y += step) {
                    Vec2 t = new Vec2(x + rng.nextDouble(-2.4, 2.4), y + rng.nextDouble(-2.4, 2.4));
                    double d = t.distance(new Vec2(p.x(), p.y()));
                    // Leave a clearing in the middle and a footpath ring for visual rhythm.
                    if (d < p.radius() - 3 && d > p.radius() * 0.22 && Math.abs(d - p.radius() * 0.62) > 2.2) {
                        trees.add(t);
                    }
                }
            }
        }
        for (Road road : graph.roads()) {
            if (road.roadClass() == RoadClass.ARTERIAL) {
                continue;
            }
            Vec2 a = road.a().position();
            Vec2 b = road.b().position();
            Vec2 dir = b.sub(a).normalized();
            Vec2 normal = dir.rightNormal();
            double len = a.distance(b);
            for (double t = road.a().radius() + 6; t < len - road.b().radius() - 6; t += 14) {
                Vec2 base = a.add(dir.scale(t));
                if (districtAt(base, radius) != District.RESIDENTIAL) {
                    continue;
                }
                for (int side = -1; side <= 1; side += 2) {
                    Vec2 p = base.add(normal.scale(side * (road.halfWidth() + 1.9)));
                    if (!hash.containsPoint(p, rects, 0.6)) {
                        trees.add(p);
                    }
                }
            }
        }
        return trees;
    }

    private static List<StreetLight> placeStreetLights(RoadGraph graph) {
        List<StreetLight> lights = new ArrayList<>();
        for (Road road : graph.roads()) {
            Vec2 a = road.a().position();
            Vec2 b = road.b().position();
            Vec2 dir = b.sub(a).normalized();
            Vec2 normal = dir.rightNormal();
            double len = a.distance(b);
            double spacing = road.roadClass() == RoadClass.LOCAL ? 34 : 28;
            int k = 0;
            for (double t = road.a().radius() + 4; t < len - road.b().radius() - 3; t += spacing, k++) {
                int side = k % 2 == 0 ? 1 : -1;
                Vec2 p = a.add(dir.scale(t)).add(normal.scale(side * (road.halfWidth() + 0.9)));
                lights.add(new StreetLight(p.x(), p.y(), normal.scale(-side).angle()));
            }
        }
        return lights;
    }

    private static List<Integer> chooseBusStops(RoadGraph graph) {
        List<RoadSegment> candidates = new ArrayList<>();
        for (RoadSegment s : graph.segments()) {
            if (s.roadClass() != RoadClass.LOCAL && s.length() > 50) {
                candidates.add(s);
            }
        }
        List<Integer> stops = new ArrayList<>();
        if (candidates.isEmpty()) {
            return stops;
        }
        RoadSegment first = candidates.get(0);
        for (RoadSegment s : candidates) {
            if (mid(s).length() < mid(first).length()) {
                first = s;
            }
        }
        List<RoadSegment> chosen = new ArrayList<>();
        chosen.add(first);
        while (chosen.size() < Math.min(10, candidates.size())) {
            RoadSegment best = null;
            double bestDist = -1;
            for (RoadSegment s : candidates) {
                double d = Double.MAX_VALUE;
                for (RoadSegment c : chosen) {
                    d = Math.min(d, mid(s).distance(mid(c)));
                }
                if (d > bestDist) {
                    bestDist = d;
                    best = s;
                }
            }
            chosen.add(best);
        }
        for (RoadSegment s : chosen) {
            stops.add(s.id());
        }
        return stops;
    }

    private static Vec2 mid(RoadSegment s) {
        return s.start().lerp(s.end(), 0.5);
    }

    private static int chooseStationNode(RoadGraph graph, double radius) {
        Vec2 target = new Vec2(-0.48 * radius, -0.42 * radius);
        RoadNode best = graph.node(0);
        for (RoadNode n : graph.nodes()) {
            if (n.degree() >= 3 && n.position().distance(target) < best.position().distance(target)) {
                best = n;
            }
        }
        return best.id();
    }

    private static int markStationBuilding(Vec2 station, List<Building> buildings) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (Building b : buildings) {
            double d = station.distance(new Vec2(b.x(), b.y()));
            if (d < bestDist) {
                bestDist = d;
                best = b.id();
            }
        }
        if (best >= 0) {
            Building b = buildings.get(best);
            buildings.set(best, new Building(b.id(), b.x(), b.y(), b.width(), b.depth(), 13,
                    b.rotation(), BuildingStyle.CIVIC, b.district(), b.variant()));
        }
        return best;
    }

    // ------------------------------------------------------------------ helpers

    /** Mutable street sketch: points plus undirected classified edges, pruned before graph build. */
    static final class Blueprint {
        final List<Vec2> points = new ArrayList<>();
        final List<int[]> edges = new ArrayList<>();
        private final Set<Long> keys = new HashSet<>();

        int addPoint(Vec2 p) {
            points.add(p);
            return points.size() - 1;
        }

        void addEdge(int a, int b, RoadClass c) {
            if (a == b) {
                return;
            }
            long key = (long) Math.min(a, b) * 1_000_003L + Math.max(a, b);
            if (keys.add(key)) {
                edges.add(new int[]{a, b, c.ordinal()});
            }
        }

        int[] degrees() {
            int[] deg = new int[points.size()];
            for (int[] e : edges) {
                deg[e[0]]++;
                deg[e[1]]++;
            }
            return deg;
        }

        /** Removes a fraction of local streets while keeping every endpoint at degree >= minDegree. */
        void carveLocalEdges(SeededRandom rng, double probability, int minDegree) {
            int[] deg = degrees();
            List<int[]> kept = new ArrayList<>();
            for (int[] e : edges) {
                boolean local = e[2] == RoadClass.LOCAL.ordinal();
                if (local && rng.nextDouble() < probability && deg[e[0]] > minDegree - 1 && deg[e[1]] > minDegree - 1) {
                    deg[e[0]]--;
                    deg[e[1]]--;
                } else {
                    kept.add(e);
                }
            }
            edges.clear();
            edges.addAll(kept);
        }

        void pruneDeadEnds() {
            boolean changed = true;
            while (changed) {
                int[] deg = degrees();
                List<int[]> kept = new ArrayList<>();
                for (int[] e : edges) {
                    if (deg[e[0]] > 1 && deg[e[1]] > 1) {
                        kept.add(e);
                    }
                }
                changed = kept.size() != edges.size();
                edges.clear();
                edges.addAll(kept);
            }
        }

        void keepLargestComponent() {
            int[] parent = new int[points.size()];
            for (int i = 0; i < parent.length; i++) {
                parent[i] = i;
            }
            for (int[] e : edges) {
                int ra = find(parent, e[0]);
                int rb = find(parent, e[1]);
                if (ra != rb) {
                    parent[Math.max(ra, rb)] = Math.min(ra, rb);
                }
            }
            int[] size = new int[points.size()];
            int[] deg = degrees();
            for (int i = 0; i < points.size(); i++) {
                if (deg[i] > 0) {
                    size[find(parent, i)]++;
                }
            }
            int best = 0;
            for (int i = 0; i < size.length; i++) {
                if (size[i] > size[best]) {
                    best = i;
                }
            }
            List<int[]> kept = new ArrayList<>();
            for (int[] e : edges) {
                if (find(parent, e[0]) == best) {
                    kept.add(e);
                }
            }
            edges.clear();
            edges.addAll(kept);
        }

        private static int find(int[] parent, int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }

        RoadGraph build() {
            RoadGraph g = new RoadGraph();
            int[] deg = degrees();
            int[] remap = new int[points.size()];
            for (int i = 0; i < points.size(); i++) {
                remap[i] = deg[i] > 0 ? g.addNode(points.get(i)).id() : -1;
            }
            for (int[] e : edges) {
                g.addRoad(g.node(remap[e[0]]), g.node(remap[e[1]]), RoadClass.values()[e[2]]);
            }
            return g;
        }
    }

    /** Oriented rectangle with half extents {@code hu} along {@code u} and {@code hv} across. */
    static final class Rect {
        final Vec2 c;
        final Vec2 u;
        final Vec2 v;
        final double hu;
        final double hv;

        Rect(Vec2 c, Vec2 u, double hu, double hv) {
            this.c = c;
            this.u = u;
            this.v = u.rightNormal();
            this.hu = hu;
            this.hv = hv;
        }

        boolean contains(Vec2 p, double margin) {
            Vec2 d = p.sub(c);
            return Math.abs(d.dot(u)) <= hu + margin && Math.abs(d.dot(v)) <= hv + margin;
        }

        double distanceTo(Vec2 p) {
            Vec2 d = p.sub(c);
            double lu = Math.max(0, Math.abs(d.dot(u)) - hu);
            double lv = Math.max(0, Math.abs(d.dot(v)) - hv);
            return Math.hypot(lu, lv);
        }

        double boundingRadius() {
            return Math.hypot(hu, hv);
        }

        boolean overlaps(Rect o, double margin) {
            Vec2[] axes = {u, v, o.u, o.v};
            Vec2 d = o.c.sub(c);
            for (Vec2 ax : axes) {
                double ra = hu * Math.abs(u.dot(ax)) + hv * Math.abs(v.dot(ax));
                double rb = o.hu * Math.abs(o.u.dot(ax)) + o.hv * Math.abs(o.v.dot(ax));
                if (Math.abs(d.dot(ax)) > ra + rb + margin) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Uniform grid bucketing of rectangles for overlap queries. */
    static final class SpatialHash {
        private final double cell;
        private final Map<Long, List<Integer>> buckets = new HashMap<>();

        SpatialHash(double cell) {
            this.cell = cell;
        }

        private long key(int cx, int cy) {
            return ((long) cx << 32) ^ (cy & 0xffffffffL);
        }

        void insert(Rect r, int index) {
            double br = r.boundingRadius();
            for (int cx = (int) Math.floor((r.c.x() - br) / cell); cx <= (int) Math.floor((r.c.x() + br) / cell); cx++) {
                for (int cy = (int) Math.floor((r.c.y() - br) / cell); cy <= (int) Math.floor((r.c.y() + br) / cell); cy++) {
                    buckets.computeIfAbsent(key(cx, cy), k -> new ArrayList<>()).add(index);
                }
            }
        }

        boolean overlaps(Rect r, List<Rect> rects, double margin) {
            double br = r.boundingRadius() + margin;
            for (int cx = (int) Math.floor((r.c.x() - br) / cell); cx <= (int) Math.floor((r.c.x() + br) / cell); cx++) {
                for (int cy = (int) Math.floor((r.c.y() - br) / cell); cy <= (int) Math.floor((r.c.y() + br) / cell); cy++) {
                    List<Integer> bucket = buckets.get(key(cx, cy));
                    if (bucket == null) {
                        continue;
                    }
                    for (int i : bucket) {
                        if (rects.get(i).overlaps(r, margin)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        boolean containsPoint(Vec2 p, List<Rect> rects, double margin) {
            List<Integer> bucket = buckets.get(key((int) Math.floor(p.x() / cell), (int) Math.floor(p.y() / cell)));
            if (bucket == null) {
                return false;
            }
            for (int i : bucket) {
                if (rects.get(i).contains(p, margin)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Road centreline samples bucketed on a grid, for clearance tests against roads and junctions. */
    static final class RoadIndex {
        private static final double CELL = 32;
        private final List<double[]> samples = new ArrayList<>(); // x, y, clearance radius
        private final Map<Long, List<Integer>> buckets = new HashMap<>();
        private final RoadGraph graph;

        RoadIndex(RoadGraph graph) {
            this.graph = graph;
            for (Road r : graph.roads()) {
                Vec2 a = r.a().position();
                Vec2 b = r.b().position();
                double len = a.distance(b);
                int steps = Math.max(1, (int) Math.ceil(len / 2.5));
                for (int i = 0; i <= steps; i++) {
                    Vec2 p = a.lerp(b, (double) i / steps);
                    add(p, r.halfWidth());
                }
            }
            for (RoadNode n : graph.nodes()) {
                add(n.position(), n.radius() + 1.0);
            }
        }

        private void add(Vec2 p, double radius) {
            samples.add(new double[]{p.x(), p.y(), radius});
            long k = key((int) Math.floor(p.x() / CELL), (int) Math.floor(p.y() / CELL));
            buckets.computeIfAbsent(k, x -> new ArrayList<>()).add(samples.size() - 1);
        }

        private static long key(int cx, int cy) {
            return ((long) cx << 32) ^ (cy & 0xffffffffL);
        }

        boolean rectIsClear(Rect r, double margin) {
            double reach = r.boundingRadius() + 12;
            for (int cx = (int) Math.floor((r.c.x() - reach) / CELL); cx <= (int) Math.floor((r.c.x() + reach) / CELL); cx++) {
                for (int cy = (int) Math.floor((r.c.y() - reach) / CELL); cy <= (int) Math.floor((r.c.y() + reach) / CELL); cy++) {
                    List<Integer> bucket = buckets.get(key(cx, cy));
                    if (bucket == null) {
                        continue;
                    }
                    for (int i : bucket) {
                        double[] s = samples.get(i);
                        if (r.contains(new Vec2(s[0], s[1]), s[2] + margin)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        /** Distance from {@code p} to the nearest kerb or junction edge. */
        double clearance(Vec2 p) {
            double best = Double.MAX_VALUE;
            for (Road r : graph.roads()) {
                best = Math.min(best, Vec2.pointSegmentDistance(p, r.a().position(), r.b().position()) - r.halfWidth());
            }
            for (RoadNode n : graph.nodes()) {
                best = Math.min(best, p.distance(n.position()) - n.radius());
            }
            return best;
        }
    }
}
