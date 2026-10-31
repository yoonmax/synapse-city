package com.synapsecity.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsecity.simulation.agents.VehicleType;
import com.synapsecity.simulation.roads.CityLayout;
import com.synapsecity.simulation.scenarios.ScenarioPreset;
import com.synapsecity.simulation.signals.SignalMode;
import com.synapsecity.websocket.protocol.ClientCommand;
import com.synapsecity.websocket.protocol.CommandException;
import com.synapsecity.websocket.protocol.CommandParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CommandParserTest {

    private final CommandParser parser = new CommandParser(new ObjectMapper());

    @Test
    void parsesEveryValidCommandShape() {
        assertInstanceOf(ClientCommand.Pause.class, parser.parse("{\"type\":\"PAUSE\"}"));
        assertEquals(new ClientCommand.SetSpeed(4), parser.parse("{\"type\":\"SET_SPEED\",\"value\":4}"));
        assertEquals(new ClientCommand.AddVehicles(20, VehicleType.TAXI),
                parser.parse("{\"type\":\"ADD_VEHICLES\",\"count\":20,\"vehicleType\":\"TAXI\"}"));
        assertEquals(new ClientCommand.CreateIncident(null), parser.parse("{\"type\":\"CREATE_INCIDENT\"}"));
        assertEquals(new ClientCommand.CreateIncident(12), parser.parse("{\"type\":\"CREATE_INCIDENT\",\"roadId\":12}"));
        assertEquals(new ClientCommand.SelectVehicle(null), parser.parse("{\"type\":\"SELECT_VEHICLE\",\"vehicleId\":null}"));
        assertEquals(new ClientCommand.SetSignalMode(SignalMode.FIXED), parser.parse("{\"type\":\"SET_SIGNAL_MODE\",\"mode\":\"FIXED\"}"));
        assertEquals(new ClientCommand.Regenerate(7, CityLayout.ORGANIC), parser.parse("{\"type\":\"REGENERATE\",\"seed\":7,\"layout\":\"ORGANIC\"}"));
        assertEquals(new ClientCommand.LoadScenario(ScenarioPreset.GRIDLOCK), parser.parse("{\"type\":\"LOAD_SCENARIO\",\"scenario\":\"GRIDLOCK\"}"));
        assertEquals(new ClientCommand.RunComparison(600), parser.parse("{\"type\":\"RUN_COMPARISON\"}"));
        assertEquals(new ClientCommand.Step(1), parser.parse("{\"type\":\"STEP\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "not json",
        "[]",
        "{}",
        "{\"type\":42}",
        "{\"type\":\"LAUNCH_MISSILES\"}",
        "{\"type\":\"SET_SPEED\",\"value\":3}",
        "{\"type\":\"SET_SPEED\",\"value\":\"fast\"}",
        "{\"type\":\"SET_DENSITY\",\"value\":-1}",
        "{\"type\":\"SET_DENSITY\",\"value\":100000}",
        "{\"type\":\"SET_DENSITY\",\"value\":1.5}",
        "{\"type\":\"ADD_VEHICLES\",\"count\":0,\"vehicleType\":\"CAR\"}",
        "{\"type\":\"ADD_VEHICLES\",\"count\":5,\"vehicleType\":\"TANK\"}",
        "{\"type\":\"CLOSE_ROAD\"}",
        "{\"type\":\"CLOSE_ROAD\",\"roadId\":-3}",
        "{\"type\":\"SET_SIGNAL_MODE\",\"mode\":\"chaos\"}",
        "{\"type\":\"REGENERATE\",\"seed\":1}",
        "{\"type\":\"RUN_COMPARISON\",\"durationSeconds\":5}",
        "{\"type\":\"INSPECT_INTERSECTION\",\"nodeId\":\"seven\"}"
    })
    void rejectsInvalidCommands(String payload) {
        assertThrows(CommandException.class, () -> parser.parse(payload));
    }

    @Test
    void rejectsOversizedPayloads() {
        String huge = "{\"type\":\"PAUSE\",\"pad\":\"" + "x".repeat(CommandParser.MAX_PAYLOAD_CHARS) + "\"}";
        CommandException e = assertThrows(CommandException.class, () -> parser.parse(huge));
        assertTrue(e.getMessage().contains("too large"));
    }
}
