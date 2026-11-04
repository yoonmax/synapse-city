package com.synapsecity.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsecity.session.ClientView;
import com.synapsecity.session.SimulationSession;
import com.synapsecity.simulation.agents.Vehicle;
import com.synapsecity.simulation.core.SimulationConfig;
import com.synapsecity.simulation.core.SimulationEngine;
import com.synapsecity.simulation.scenarios.ComparisonRunner;
import com.synapsecity.simulation.scenarios.ScenarioPreset;
import com.synapsecity.websocket.protocol.BinaryFrameEncoder;
import com.synapsecity.websocket.protocol.ClientCommand;
import com.synapsecity.websocket.protocol.Messages;
import com.synapsecity.websocket.protocol.ProtocolMapper;
import com.synapsecity.websocket.protocol.WireJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The browser host's hand-written JSON must match what the server sends through Jackson. */
class WireJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private void assertSameJson(Object message) throws Exception {
        JsonNode jackson = mapper.readTree(mapper.writeValueAsString(message));
        JsonNode wire = mapper.readTree(WireJson.write(message));
        assertEquals(jackson, wire, message.getClass().getSimpleName());
    }

    @Test
    void everyMessageTypeMatchesJacksonOnRealData() throws Exception {
        SimulationEngine e = new SimulationEngine(SimulationConfig.defaults());
        e.runFor(120);
        e.closeRoad(3);
        e.createIncident(null);
        Vehicle unit = e.dispatchEmergency(null);
        assertNotNull(unit);
        e.runFor(10);

        assertSameJson(new Messages.Hello("hello", 1, 0.1, 20));
        assertSameJson(ProtocolMapper.city(e));
        assertSameJson(new Messages.ScenariosMessage("scenarios", ProtocolMapper.scenarios()));
        assertSameJson(new Messages.ErrorMessage("error", "Bad \"input\"\nwith\tescapes \\ \u0001"));
        assertSameJson(new Messages.AckMessage("vehicleGone", "SELECT_VEHICLE", "gone"));
        for (Vehicle v : e.vehicles().subList(0, 30)) {
            assertSameJson(ProtocolMapper.vehicle(e, v));
        }
        assertSameJson(ProtocolMapper.vehicle(e, unit));
        e.intersections().forEach(ix -> assertDoesNotThrow(() -> assertSameJson(ProtocolMapper.intersection(e, ix))));

        var result = ComparisonRunner.compare(ScenarioPreset.SMART_SIGNALS.config(), ScenarioPreset.SMART_SIGNALS, 60, p -> { });
        assertSameJson(new Messages.ComparisonMessage("comparison", "done", 1, null, result));
        assertSameJson(new Messages.ComparisonMessage("comparison", "running", 0.5, "working", null));
    }

    @Test
    void stateMessagesFromASessionMatchJackson() throws Exception {
        SimulationSession session = new SimulationSession(new SimulationSession.Host() {
            @Override
            public void onCityChanged() { }

            @Override
            public void onFrameDirty() { }

            @Override
            public void onDetailsRequested(ClientView view) { }

            @Override
            public void onComparisonRequested(SimulationConfig base, ScenarioPreset preset, double duration) { }
        });
        session.start();
        ClientView view = new ClientView();
        session.onClientConnected(view);
        for (int i = 0; i < 80; i++) {
            session.advance(0.25);
        }
        session.apply(view, new ClientCommand.CreateIncident(null));
        session.advance(0.25);
        Messages.StateMessage base = session.baseState(new Messages.ServerStats(1, 1234, 10.5, 10, 1), false);
        Messages.StateMessage state = session.stateFor(view, base);
        assertFalse(state.events().isEmpty());
        assertFalse(state.series().isEmpty());
        assertSameJson(state);
        assertEquals(0, session.stateFor(view, base).events().size(), "cursors advance");

        session.apply(view, new ClientCommand.SelectVehicle(session.engine().vehicles().get(0).id()));
        List<Object> details = session.detailsFor(view);
        assertEquals(1, details.size());
        assertSameJson(details.get(0));
        assertEquals(BinaryFrameEncoder.HEADER_BYTES, 16);
    }
}
