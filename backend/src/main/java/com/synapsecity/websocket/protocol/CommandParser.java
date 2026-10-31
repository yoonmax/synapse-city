package com.synapsecity.websocket.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsecity.simulation.agents.DemandProfile;
import com.synapsecity.simulation.agents.VehicleType;
import com.synapsecity.simulation.core.SimulationConfig;
import com.synapsecity.simulation.roads.CityLayout;
import com.synapsecity.simulation.routing.RoutingAlgorithm;
import com.synapsecity.simulation.scenarios.ScenarioPreset;
import com.synapsecity.simulation.signals.SignalMode;

/**
 * Strict JSON-to-command validation. Rejects unknown types, missing or mistyped fields,
 * out-of-range numbers, unknown enum constants and oversized payloads.
 */
public final class CommandParser {

    public static final int MAX_PAYLOAD_CHARS = 2048;
    public static final double[] SPEEDS = {0.25, 0.5, 1, 2, 4, 8, 16};

    private final ObjectMapper mapper;

    public CommandParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ClientCommand parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new CommandException("Empty command");
        }
        if (payload.length() > MAX_PAYLOAD_CHARS) {
            throw new CommandException("Command payload too large");
        }
        JsonNode node;
        try {
            node = mapper.readTree(payload);
        } catch (Exception e) {
            throw new CommandException("Malformed JSON");
        }
        if (node == null || !node.isObject()) {
            throw new CommandException("Command must be a JSON object");
        }
        String type = text(node, "type");
        return switch (type) {
            case "PAUSE" -> new ClientCommand.Pause();
            case "RESUME" -> new ClientCommand.Resume();
            case "STEP" -> new ClientCommand.Step(optInt(node, "ticks", 1, 1, 600));
            case "SET_SPEED" -> new ClientCommand.SetSpeed(speed(node));
            case "ADD_VEHICLES" -> new ClientCommand.AddVehicles(integer(node, "count", 1, 200),
                    enumValue(node, "vehicleType", VehicleType.class));
            case "SET_DENSITY" -> new ClientCommand.SetDensity(integer(node, "value", 0, SimulationConfig.MAX_VEHICLES));
            case "SET_DEMAND" -> new ClientCommand.SetDemand(enumValue(node, "profile", DemandProfile.class));
            case "CLOSE_ROAD" -> new ClientCommand.CloseRoad(integer(node, "roadId", 0, 100_000));
            case "OPEN_ROAD" -> new ClientCommand.OpenRoad(integer(node, "roadId", 0, 100_000));
            case "CREATE_INCIDENT" -> new ClientCommand.CreateIncident(optionalId(node, "roadId"));
            case "DISPATCH_EMERGENCY" -> new ClientCommand.DispatchEmergency(optionalId(node, "roadId"));
            case "SET_SIGNAL_MODE" -> new ClientCommand.SetSignalMode(enumValue(node, "mode", SignalMode.class));
            case "SET_ROUTING" -> new ClientCommand.SetRouting(enumValue(node, "algorithm", RoutingAlgorithm.class));
            case "SELECT_VEHICLE" -> new ClientCommand.SelectVehicle(optionalId(node, "vehicleId"));
            case "INSPECT_INTERSECTION" -> new ClientCommand.InspectIntersection(optionalId(node, "nodeId"));
            case "RESET" -> new ClientCommand.Reset();
            case "REGENERATE" -> new ClientCommand.Regenerate(integer(node, "seed", 0, Integer.MAX_VALUE),
                    enumValue(node, "layout", CityLayout.class));
            case "LOAD_SCENARIO" -> new ClientCommand.LoadScenario(enumValue(node, "scenario", ScenarioPreset.class));
            case "RUN_COMPARISON" -> new ClientCommand.RunComparison(
                    optInt(node, "durationSeconds", 600, 60, 1800));
            default -> throw new CommandException("Unknown command type: " + type);
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) {
            throw new CommandException("Field '" + field + "' must be a string");
        }
        return v.asText();
    }

    private static int integer(JsonNode node, String field, int min, int max) {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToInt()) {
            throw new CommandException("Field '" + field + "' must be an integer");
        }
        int x = v.asInt();
        if (x < min || x > max) {
            throw new CommandException("Field '" + field + "' must be within [" + min + ", " + max + "]");
        }
        return x;
    }

    private static int optInt(JsonNode node, String field, int def, int min, int max) {
        return node.hasNonNull(field) ? integer(node, field, min, max) : def;
    }

    private static Integer optionalId(JsonNode node, String field) {
        return node.hasNonNull(field) ? integer(node, field, 0, 1_000_000_000) : null;
    }

    private static double speed(JsonNode node) {
        JsonNode v = node.get("value");
        if (v == null || !v.isNumber()) {
            throw new CommandException("Field 'value' must be a number");
        }
        double x = v.asDouble();
        for (double allowed : SPEEDS) {
            if (Math.abs(allowed - x) < 1e-9) {
                return allowed;
            }
        }
        throw new CommandException("Speed must be one of 0.25, 0.5, 1, 2, 4, 8, 16");
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, String field, Class<E> type) {
        String raw = text(node, field);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new CommandException("Unknown " + field + ": " + raw);
        }
    }
}
