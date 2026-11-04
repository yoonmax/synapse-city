package com.synapsecity.session;

/** Events-per-second counter over a sliding two-second window. */
public final class RateMeter {

    private static final long WINDOW_NANOS = 2_000_000_000L;

    private final long[] times = new long[256];
    private final int[] counts = new int[256];
    private int cursor;

    public void add(int n, long nowNanos) {
        times[cursor] = nowNanos;
        counts[cursor] = n;
        cursor = (cursor + 1) % times.length;
    }

    public double perSecond(long nowNanos) {
        int total = 0;
        for (int i = 0; i < times.length; i++) {
            if (times[i] != 0 && nowNanos - times[i] <= WINDOW_NANOS) {
                total += counts[i];
            }
        }
        return Math.round(total / 2.0 * 10) / 10.0;
    }
}
