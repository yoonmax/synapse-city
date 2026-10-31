package com.synapsecity.simulation.agents;

/** Spatial distribution of trip origins and destinations. */
public enum DemandProfile {
    /** Origins and destinations spread across the network, weighted by road length. */
    BALANCED,
    /** Origins skewed to residential outskirts, destinations to the downtown core. */
    COMMUTE,
    /** Destinations concentrated tightly around the centre (stress test for central junctions). */
    CENTRAL
}
