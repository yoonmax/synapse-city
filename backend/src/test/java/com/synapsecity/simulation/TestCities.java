package com.synapsecity.simulation;

import com.synapsecity.simulation.core.Vec2;
import com.synapsecity.simulation.roads.CityLayout;
import com.synapsecity.simulation.roads.CityModel;
import com.synapsecity.simulation.roads.RoadClass;
import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.roads.RoadNode;

import java.util.List;

/** Small hand-built networks with known answers. */
public final class TestCities {

    private TestCities() {
    }

    /**
     * 3x3 grid, 100 m spacing, all LOCAL except the middle row (ARTERIAL, faster).
     * Node ids: row-major, id = row * 3 + col, (0,0) at bottom-left.
     */
    public static RoadGraph grid3() {
        RoadGraph g = new RoadGraph();
        RoadNode[][] n = new RoadNode[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                n[r][c] = g.addNode(new Vec2(c * 100, r * 100));
            }
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 2; c++) {
                g.addRoad(n[r][c], n[r][c + 1], r == 1 ? RoadClass.ARTERIAL : RoadClass.LOCAL);
            }
        }
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 2; r++) {
                g.addRoad(n[r][c], n[r + 1][c], RoadClass.LOCAL);
            }
        }
        g.finalizeGeometry();
        return g;
    }

    /** Two separate two-node islands: 0-1 and 2-3. */
    public static RoadGraph disconnected() {
        RoadGraph g = new RoadGraph();
        RoadNode a = g.addNode(new Vec2(0, 0));
        RoadNode b = g.addNode(new Vec2(100, 0));
        RoadNode c = g.addNode(new Vec2(500, 0));
        RoadNode d = g.addNode(new Vec2(600, 0));
        g.addRoad(a, b, RoadClass.LOCAL);
        g.addRoad(c, d, RoadClass.LOCAL);
        g.finalizeGeometry();
        return g;
    }

    public static CityModel wrap(RoadGraph g) {
        return new CityModel("Test", 1, CityLayout.GRID, g, List.of(), List.of(), List.of(), List.of(), List.of(),
                0, -1, 200);
    }
}
