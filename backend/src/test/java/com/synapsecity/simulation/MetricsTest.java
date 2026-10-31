package com.synapsecity.simulation;

import com.synapsecity.simulation.agents.VehicleType;
import com.synapsecity.simulation.metrics.LiveMetrics;
import com.synapsecity.simulation.metrics.MetricsCollector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetricsTest {

    @Test
    void tripAggregatesArecomputedFromRecordedTrips() {
        MetricsCollector m = new MetricsCollector();
        for (int i = 1; i <= 10; i++) {
            m.recordTrip(VehicleType.CAR, 100 + i, i * 10, i, 500, i * 5);
        }
        assertEquals(10, m.completedTrips());
        assertEquals(55, m.meanTripTime(), 1e-9);
        assertEquals(5.5, m.meanTripWait(), 1e-9);
        LiveMetrics live = m.computeLive(List.of(), TestCities.grid3(), 110, 0, 0);
        assertEquals(55, live.avgTripTime(), 1e-9);
        assertEquals(100, live.p90TripTime(), 1e-9);
        assertEquals(2.0, live.travelTimeIndex(), 1e-9, "every trip took twice its free-flow time");
        assertEquals(10, live.throughputPerMin(), 1e-9, "all ten completed within the last minute");
        assertEquals(0, live.active());
    }

    @Test
    void emptyNetworkHasFiniteZeroMetrics() {
        LiveMetrics live = new MetricsCollector().computeLive(List.of(), TestCities.grid3(), 0, 0, 0);
        assertEquals(0, live.congestionIndex());
        assertEquals(0, live.avgSpeed());
        assertEquals(1, live.travelTimeIndex());
        assertEquals("FREE FLOW", live.condition());
    }

    @Test
    void samplingHappensOnTheFixedInterval() {
        MetricsCollector m = new MetricsCollector();
        LiveMetrics zero = m.computeLive(List.of(), TestCities.grid3(), 0, 0, 0);
        m.maybeSample(4.9, () -> zero);
        assertTrue(m.series().isEmpty());
        m.maybeSample(5.0, () -> zero);
        m.maybeSample(7.0, () -> zero);
        assertEquals(1, m.series().size());
        m.maybeSample(10.0, () -> zero);
        assertEquals(2, m.series().size());
    }

    @Test
    void conditionBandsAreOrdered() {
        assertEquals("FREE FLOW", MetricsCollector.conditionFor(10));
        assertEquals("MODERATE", MetricsCollector.conditionFor(50));
        assertEquals("HEAVY", MetricsCollector.conditionFor(65));
        assertEquals("SEVERE", MetricsCollector.conditionFor(90));
    }

    @Test
    void emergencyResponsesAverage() {
        MetricsCollector m = new MetricsCollector();
        m.recordEmergencyResponse(60);
        m.recordEmergencyResponse(90);
        LiveMetrics live = m.computeLive(List.of(), TestCities.grid3(), 0, 0, 0);
        assertEquals(75, live.emergencyAvgResponse(), 1e-9);
        assertEquals(2, live.emergencyResponses());
    }
}
