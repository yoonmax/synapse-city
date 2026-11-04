package com.synapsecity.simulation.core;

/**
 * Deterministic SplitMix64 generator. Produces the same uniform sequence as
 * {@code java.util.SplittableRandom}, but is plain Java so the engine compiles unchanged to the
 * browser, where that JDK class does not exist. All simulation randomness comes from here.
 */
public final class SeededRandom {

    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;
    private static final double DOUBLE_UNIT = 0x1.0p-53;

    private long seed;

    public SeededRandom(long seed) {
        this.seed = seed;
    }

    private long nextSeed() {
        seed += GOLDEN_GAMMA;
        return seed;
    }

    public long nextLong() {
        long z = nextSeed();
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    public int nextInt() {
        long z = nextSeed();
        z = (z ^ (z >>> 33)) * 0x62a9d9ed799705f5L;
        return (int) (((z ^ (z >>> 28)) * 0xcb24d0a5c88c35b3L) >>> 32);
    }

    /** Uniform integer in [0, bound). */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        int r = nextInt();
        int m = bound - 1;
        if ((bound & m) == 0) {
            return r & m;
        }
        int u = r >>> 1;
        while (u + m - (r = u % bound) < 0) {
            u = nextInt() >>> 1;
        }
        return r;
    }

    /** Uniform double in [0, 1). */
    public double nextDouble() {
        return (nextLong() >>> 11) * DOUBLE_UNIT;
    }

    /** Uniform double in [origin, bound). */
    public double nextDouble(double origin, double bound) {
        double r = nextDouble() * (bound - origin) + origin;
        return r >= bound ? Math.nextDown(bound) : r;
    }

    public boolean nextBoolean() {
        return nextInt() < 0;
    }

    /** Standard normal sample (Marsaglia polar method). */
    public double nextGaussian() {
        double u;
        double v;
        double s;
        do {
            u = nextDouble(-1, 1);
            v = nextDouble(-1, 1);
            s = u * u + v * v;
        } while (s >= 1 || s == 0);
        return u * Math.sqrt(-2 * Math.log(s) / s);
    }
}
