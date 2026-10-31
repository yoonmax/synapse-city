package com.synapsecity.simulation.roads;

/** Supported procedural street-network layouts. */
public enum CityLayout {
    /** Dense orthogonal blocks with arterial avenues every fourth street. */
    GRID,
    /** Concentric ring roads crossed by boulevards radiating from a central plaza. */
    RADIAL,
    /** Irregular, jittered street mesh with diagonal shortcuts and a noisy coastline-like boundary. */
    ORGANIC
}
