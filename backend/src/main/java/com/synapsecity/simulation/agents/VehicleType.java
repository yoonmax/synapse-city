package com.synapsecity.simulation.agents;

/**
 * Vehicle classes with Intelligent Driver Model parameters.
 * Lengths and widths are metres; accelerations m/s^2; headway seconds; speed factor is relative
 * to the posted limit.
 */
public enum VehicleType {
    CAR(4.4, 1.85, 1.7, 2.6, 1.3, 2.0, 1.00),
    TAXI(4.7, 1.85, 1.9, 2.8, 1.1, 1.8, 1.05),
    BUS(11.5, 2.55, 1.0, 2.0, 1.6, 2.5, 0.85),
    DELIVERY(6.4, 2.2, 1.2, 2.3, 1.5, 2.2, 0.92),
    EMERGENCY(5.4, 2.05, 2.6, 3.6, 0.9, 1.6, 1.35);

    private final double length;
    private final double width;
    private final double maxAccel;
    private final double comfortDecel;
    private final double timeHeadway;
    private final double minGap;
    private final double speedFactor;

    VehicleType(double length, double width, double maxAccel, double comfortDecel,
                double timeHeadway, double minGap, double speedFactor) {
        this.length = length;
        this.width = width;
        this.maxAccel = maxAccel;
        this.comfortDecel = comfortDecel;
        this.timeHeadway = timeHeadway;
        this.minGap = minGap;
        this.speedFactor = speedFactor;
    }

    public double length() {
        return length;
    }

    public double width() {
        return width;
    }

    public double maxAccel() {
        return maxAccel;
    }

    public double comfortDecel() {
        return comfortDecel;
    }

    public double timeHeadway() {
        return timeHeadway;
    }

    public double minGap() {
        return minGap;
    }

    public double speedFactor() {
        return speedFactor;
    }
}
