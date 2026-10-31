package com.synapsecity.simulation.roads;

/** Architectural massing family; the renderer maps each to a distinct silhouette. */
public enum BuildingStyle {
    /** Slender high-rise with a crown. */
    TOWER,
    /** High-rise with stepped setbacks. */
    SETBACK_TOWER,
    /** Mid-rise office slab. */
    OFFICE,
    /** Mid-rise residential block. */
    APARTMENT,
    /** Low-rise house or townhouse. */
    HOUSE,
    /** Civic building (the emergency services station). */
    CIVIC
}
