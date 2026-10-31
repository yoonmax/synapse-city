package com.synapsecity.simulation;

import com.synapsecity.simulation.core.SimulationEngine;
import com.synapsecity.simulation.events.EventType;
import com.synapsecity.simulation.scenarios.ComparisonRunner;
import com.synapsecity.simulation.scenarios.ScenarioPreset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioReproducibilityTest {

    @ParameterizedTest
    @EnumSource(ScenarioPreset.class)
    void replayingAPresetReproducesTheSameRun(ScenarioPreset preset) {
        SimulationEngine a = preset.createEngine();
        SimulationEngine b = preset.createEngine();
        a.runFor(120);
        b.runFor(120);
        assertEquals(a.stateHash(), b.stateHash());
        assertEquals(a.metrics().completedTrips(), b.metrics().completedTrips());
        long scriptedDue = preset.script().stream().filter(s -> s.time() <= 120).count();
        long executed = a.events().all().stream().filter(ev -> ev.type() == EventType.SCENARIO_STEP).count();
        assertEquals(scriptedDue, executed, "every scripted step due by t=120 s ran exactly once");
    }

    @Test
    void roadFailureScenarioClosesARoadAndTriggersRerouting() {
        SimulationEngine e = ScenarioPreset.ROAD_FAILURE.createEngine();
        e.runFor(59);
        assertTrue(e.graph().roads().stream().noneMatch(r -> r.isClosed()));
        e.runFor(5);
        assertEquals(1, e.graph().roads().stream().filter(r -> r.isClosed()).count());
        assertTrue(e.events().all().stream().anyMatch(ev -> ev.type() == EventType.ROAD_CLOSED));
    }

    @Test
    void comparisonIsDeterministicAndUsesTheSameSeedForBothStrategies() {
        var r1 = ComparisonRunner.compare(ScenarioPreset.SMART_SIGNALS.config(), ScenarioPreset.SMART_SIGNALS, 90, p -> { });
        var r2 = ComparisonRunner.compare(ScenarioPreset.SMART_SIGNALS.config(), ScenarioPreset.SMART_SIGNALS, 90, p -> { });
        assertEquals(r1.fixed().stateHash(), r2.fixed().stateHash());
        assertEquals(r1.adaptive().stateHash(), r2.adaptive().stateHash());
        assertNotEquals(r1.fixed().stateHash(), r1.adaptive().stateHash(), "strategies should actually behave differently");
        assertEquals(ScenarioPreset.SMART_SIGNALS.config().seed(), r1.seed());
    }
}
