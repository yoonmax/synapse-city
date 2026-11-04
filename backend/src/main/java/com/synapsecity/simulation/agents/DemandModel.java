package com.synapsecity.simulation.agents;

import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadSegment;

import java.util.ArrayList;
import java.util.List;
import com.synapsecity.simulation.core.SeededRandom;

/**
 * Samples trip origins and destinations from road-length-weighted distributions shaped by a
 * {@link DemandProfile}. Only segments long enough to host a stopped vehicle are eligible.
 */
public final class DemandModel {

    public static final double MIN_SPAWN_LENGTH = 35;

    private final List<RoadSegment> eligible = new ArrayList<>();
    private final double radius;
    private DemandProfile profile;
    private double[] originCdf;
    private double[] destinationCdf;

    public DemandModel(RoadGraph graph, double radius, DemandProfile profile) {
        this.radius = Math.max(1, radius);
        for (RoadSegment s : graph.segments()) {
            if (s.length() >= MIN_SPAWN_LENGTH) {
                eligible.add(s);
            }
        }
        setProfile(profile);
    }

    public void setProfile(DemandProfile profile) {
        this.profile = profile;
        double[] o = new double[eligible.size()];
        double[] d = new double[eligible.size()];
        for (int i = 0; i < eligible.size(); i++) {
            RoadSegment s = eligible.get(i);
            double dc = s.start().lerp(s.end(), 0.5).length() / radius;
            double len = s.length();
            switch (profile) {
                case BALANCED -> {
                    o[i] = len;
                    d[i] = len;
                }
                case COMMUTE -> {
                    o[i] = len * (0.15 + Math.pow(dc, 1.5));
                    d[i] = len * (0.10 + 2.2 * Math.pow(Math.max(0, 1 - dc), 2.5));
                }
                case CENTRAL -> {
                    o[i] = len;
                    d[i] = len * (0.03 + 6 * Math.exp(-(dc / 0.28) * (dc / 0.28)));
                }
            }
        }
        originCdf = cumulative(o);
        destinationCdf = cumulative(d);
    }

    private static double[] cumulative(double[] w) {
        double[] c = new double[w.length];
        double total = 0;
        for (int i = 0; i < w.length; i++) {
            total += w[i];
            c[i] = total;
        }
        return c;
    }

    private RoadSegment sample(double[] cdf, SeededRandom rng) {
        if (cdf.length == 0) {
            return null;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            double x = rng.nextDouble() * cdf[cdf.length - 1];
            int lo = 0;
            int hi = cdf.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cdf[mid] < x) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            RoadSegment s = eligible.get(lo);
            if (!s.isClosed()) {
                return s;
            }
        }
        return null;
    }

    public RoadSegment sampleOrigin(SeededRandom rng) {
        return sample(originCdf, rng);
    }

    public RoadSegment sampleDestination(SeededRandom rng) {
        return sample(destinationCdf, rng);
    }

    public DemandProfile profile() {
        return profile;
    }

    public boolean isEmpty() {
        return eligible.isEmpty();
    }
}
