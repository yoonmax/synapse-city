package com.synapsecity.simulation.roads;

import com.synapsecity.simulation.core.Vec2;

import java.util.List;

/** Everything the deterministic generator produces for one seed and layout. */
public record CityModel(
        String name,
        long seed,
        CityLayout layout,
        RoadGraph graph,
        List<Building> buildings,
        List<Park> parks,
        List<Vec2> trees,
        List<StreetLight> streetLights,
        List<Integer> busStopSegments,
        int emergencyStationNode,
        int emergencyStationBuilding,
        double radius) {
}
