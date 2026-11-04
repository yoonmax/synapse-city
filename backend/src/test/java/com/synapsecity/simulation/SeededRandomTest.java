package com.synapsecity.simulation;

import com.synapsecity.simulation.core.SeededRandom;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class SeededRandomTest {

    @Test
    void uniformDrawsMatchTheJdkSplitMixGenerator() {
        for (long seed : new long[]{0, 1, -7, 20261030L, 0x5DEECE66DL}) {
            SeededRandom ours = new SeededRandom(seed);
            SplittableRandom jdk = new SplittableRandom(seed);
            for (int i = 0; i < 2000; i++) {
                assertEquals(jdk.nextLong(), ours.nextLong());
                assertEquals(jdk.nextInt(), ours.nextInt());
                assertEquals(jdk.nextDouble(), ours.nextDouble());
                assertEquals(jdk.nextBoolean(), ours.nextBoolean());
                assertEquals(jdk.nextInt(1 + i), ours.nextInt(1 + i));
                assertEquals(jdk.nextDouble(-3.5, 9.25), ours.nextDouble(-3.5, 9.25));
            }
        }
    }

    @Test
    void gaussianHasStandardMoments() {
        SeededRandom r = new SeededRandom(42);
        double sum = 0;
        double squares = 0;
        int n = 200_000;
        for (int i = 0; i < n; i++) {
            double g = r.nextGaussian();
            sum += g;
            squares += g * g;
        }
        assertEquals(0, sum / n, 0.01);
        assertEquals(1, squares / n, 0.02);
    }
}
