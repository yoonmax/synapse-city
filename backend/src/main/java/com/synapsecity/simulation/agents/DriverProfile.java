package com.synapsecity.simulation.agents;

/**
 * Per-agent driving characteristics, sampled at spawn.
 *
 * @param speedFactor       multiplier on the type's desired speed
 * @param headwayFactor     multiplier on the desired time headway
 * @param accelFactor       multiplier on maximum acceleration
 * @param rerouteAwareness  probability of re-evaluating the route at each periodic check
 */
public record DriverProfile(double speedFactor, double headwayFactor, double accelFactor, double rerouteAwareness) {
}
