package com.synapsecity.session;

/** What one connected client is looking at, plus its event and time-series read cursors. */
public final class ClientView {

    public Integer selectedVehicle;
    public Integer inspectedNode;
    public long lastEventId;
    public double lastSampleTime = -1;

    public void reset() {
        selectedVehicle = null;
        inspectedNode = null;
        lastEventId = 0;
        lastSampleTime = -1;
    }
}
