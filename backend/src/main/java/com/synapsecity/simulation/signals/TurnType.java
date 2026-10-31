package com.synapsecity.simulation.signals;

import com.synapsecity.simulation.core.Vec2;

/** Turn classification from the signed angle between approach and exit directions. */
public enum TurnType {
    RIGHT(0), STRAIGHT(1), LEFT(2), UTURN(3);

    /** Lateral ordering at the exit, right to left; used for same-approach crossing checks. */
    private final int rank;

    TurnType(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean crossesOncoming() {
        return this == LEFT || this == UTURN;
    }

    public static TurnType classify(Vec2 inDir, Vec2 outDir) {
        double angle = inDir.signedAngleTo(outDir);
        double deg = Math.toDegrees(angle);
        if (Math.abs(deg) < 38) {
            return STRAIGHT;
        }
        if (Math.abs(deg) > 165) {
            return UTURN;
        }
        // CCW (positive) rotation is a left turn in the simulation's x/y plane.
        return deg > 0 ? LEFT : RIGHT;
    }
}
