package com.synapsecity.simulation.routing;

import java.util.Arrays;

/**
 * Minimal binary min-heap of (priority, node) pairs with lazy deletion. Ties break on node id
 * so that search order, and therefore every route, is fully deterministic.
 */
final class SearchQueue {

    private double[] keys = new double[64];
    private int[] nodes = new int[64];
    private int size;

    boolean isEmpty() {
        return size == 0;
    }

    void push(double key, int node) {
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            nodes = Arrays.copyOf(nodes, size * 2);
        }
        int i = size++;
        keys[i] = key;
        nodes[i] = node;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (!less(i, p)) {
                break;
            }
            swap(i, p);
            i = p;
        }
    }

    double peekKey() {
        return keys[0];
    }

    /** Removes the minimum entry and returns its node. */
    int pop() {
        int top = nodes[0];
        size--;
        if (size > 0) {
            keys[0] = keys[size];
            nodes[0] = nodes[size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1;
                int r = l + 1;
                int m = i;
                if (l < size && less(l, m)) {
                    m = l;
                }
                if (r < size && less(r, m)) {
                    m = r;
                }
                if (m == i) {
                    break;
                }
                swap(i, m);
                i = m;
            }
        }
        return top;
    }

    private boolean less(int a, int b) {
        return keys[a] < keys[b] || (keys[a] == keys[b] && nodes[a] < nodes[b]);
    }

    private void swap(int a, int b) {
        double k = keys[a];
        keys[a] = keys[b];
        keys[b] = k;
        int n = nodes[a];
        nodes[a] = nodes[b];
        nodes[b] = n;
    }
}
