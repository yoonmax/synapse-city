package com.synapsecity.simulation;

import com.synapsecity.simulation.agents.TrafficLanes;
import com.synapsecity.simulation.core.SimulationEngine;
import com.synapsecity.simulation.roads.CityLayout;
import com.synapsecity.simulation.roads.RoadGraph;
import com.synapsecity.simulation.signals.AdaptiveController;
import com.synapsecity.simulation.signals.FixedTimeController;
import com.synapsecity.simulation.signals.Intersection;
import com.synapsecity.simulation.signals.LightColor;
import com.synapsecity.simulation.signals.SignalStage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SignalControlTest {

    @Test
    void fourWayJunctionPairsOpposingApproaches() {
        RoadGraph g = TestCities.grid3();
        Intersection ix = new Intersection(g.node(4));
        assertTrue(ix.isSignalized());
        assertEquals(4, ix.approaches().size());
        assertEquals(2, ix.phaseCount());
        for (int p = 0; p < 2; p++) {
            var a = ix.phaseApproaches(p);
            assertEquals(2, a.size());
            assertTrue(a.get(0).segment().direction().dot(a.get(1).segment().direction()) < -0.9, "opposing pair");
        }
        assertTrue(ix.phaseHasMajorRoad(0), "arterial phase is served first");
    }

    @Test
    void fixedTimeCyclesGreenAmberAllRedWithConfiguredSplits() {
        RoadGraph g = TestCities.grid3();
        Intersection ix = new Intersection(g.node(4));
        ix.setController(new FixedTimeController(), 0, 0);
        TrafficLanes lanes = new TrafficLanes(g);
        List<double[]> greens = new ArrayList<>(); // phase, duration
        SignalStage last = ix.stage();
        int lastPhase = ix.currentPhase();
        double stageStart = 0;
        double dt = 0.1;
        for (int i = 1; i <= 2000; i++) {
            double t = i * dt;
            ix.measure(lanes);
            ix.updateSignal(dt, t);
            assertTrue(ix.satisfiesSignalInvariant());
            if (ix.stage() != last) {
                if (last == SignalStage.GREEN) {
                    greens.add(new double[]{lastPhase, t - stageStart});
                    assertEquals(SignalStage.YELLOW, ix.stage(), "green always ends in amber");
                } else if (last == SignalStage.YELLOW) {
                    assertEquals(SignalStage.ALL_RED, ix.stage());
                    assertEquals(Intersection.YELLOW_TIME, t - stageStart, 0.11);
                } else {
                    assertEquals(SignalStage.GREEN, ix.stage());
                    assertEquals(Intersection.ALL_RED_TIME, t - stageStart, 0.11);
                    assertEquals((lastPhase + 1) % ix.phaseCount(), ix.currentPhase(), "cyclic order");
                }
                stageStart = t;
                last = ix.stage();
                lastPhase = ix.currentPhase();
            }
            if (ix.stage() == SignalStage.ALL_RED) {
                ix.approaches().forEach(a -> assertEquals(LightColor.RED, a.color()));
            }
        }
        assertTrue(greens.size() >= 6);
        for (double[] gr : greens.subList(1, greens.size())) {
            double expected = FixedTimeController.greenTime(ix, (int) gr[0]);
            assertEquals(expected, gr[1], 0.11);
        }
    }

    @Test
    void adaptiveControlHoldsGreenWithoutCompetingDemand() {
        RoadGraph g = TestCities.grid3();
        Intersection ix = new Intersection(g.node(4));
        ix.setController(new AdaptiveController(), 0, -1);
        TrafficLanes lanes = new TrafficLanes(g);
        for (int i = 1; i <= 1200; i++) {
            ix.measure(lanes);
            ix.updateSignal(0.1, i * 0.1);
        }
        assertEquals(0, ix.phaseChanges(), "an empty junction has no reason to cycle");
        assertEquals(SignalStage.GREEN, ix.stage());
    }

    @Test
    void adaptiveControlNeverStarvesAnApproachUnderHeavyLoad() {
        SimulationEngine e = new SimulationEngine(SimulationEngineTest.config(CityLayout.GRID, 650));
        double bound = AdaptiveController.MAX_RED;
        List<Intersection> signals = e.intersections().stream().filter(Intersection::isSignalized).toList();
        int maxPhases = signals.stream().mapToInt(Intersection::phaseCount).max().orElse(2);
        bound += (maxPhases - 1) * (AdaptiveController.MAX_GREEN + Intersection.YELLOW_TIME + Intersection.ALL_RED_TIME)
                + Intersection.YELLOW_TIME + Intersection.ALL_RED_TIME + 1;
        double[][] waitingSince = new double[e.graph().nodeCount()][];
        for (Intersection ix : signals) {
            waitingSince[ix.node().id()] = new double[ix.phaseCount()];
            java.util.Arrays.fill(waitingSince[ix.node().id()], -1);
        }
        double worst = 0;
        for (int tick = 0; tick < 6000; tick++) {
            e.step();
            double t = e.time();
            for (Intersection ix : signals) {
                double[] since = waitingSince[ix.node().id()];
                for (int p = 0; p < ix.phaseCount(); p++) {
                    boolean served = p == ix.currentPhase() && ix.stage() == SignalStage.GREEN;
                    if (served || ix.phaseDemand(p) == 0) {
                        since[p] = -1;
                    } else if (since[p] < 0) {
                        since[p] = t;
                    } else {
                        worst = Math.max(worst, t - since[p]);
                    }
                }
                assertTrue(ix.satisfiesSignalInvariant());
            }
        }
        assertTrue(worst <= bound, "an approach with demand waited " + worst + " s (bound " + bound + " s)");
        assertTrue(worst > AdaptiveController.MIN_GREEN, "sanity: some approaches did wait");
    }
}
