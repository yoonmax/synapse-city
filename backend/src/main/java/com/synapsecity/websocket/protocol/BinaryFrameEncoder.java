package com.synapsecity.websocket.protocol;

import com.synapsecity.simulation.agents.Vehicle;
import com.synapsecity.simulation.agents.VehicleState;
import com.synapsecity.simulation.core.SimulationEngine;
import com.synapsecity.simulation.roads.RoadSegment;
import com.synapsecity.simulation.signals.Approach;
import com.synapsecity.simulation.signals.Intersection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Compact little-endian snapshot of everything that changes every tick.
 *
 * <pre>
 * header (16 bytes)
 *   u8  kind = 1        u8  version = 1      u16 vehicleCount
 *   u32 tick
 *   f32 simTime
 *   u16 segmentCount    u16 approachCount
 * vehicles (20 bytes each)
 *   u32 id   f32 x   f32 y   i16 heading (rad * 10000)
 *   u8 speed (0.25 m/s units)   u8 type   u8 state   u8 flags (1 braking, 2 siren, 4 in junction)
 *   u8 fade (0..255)   u8 lane
 * segments: u8 congestion (0..255) per segment, in segment id order
 * approaches: u8 colour (0 red, 1 amber, 2 green) per approach, in city-message order
 * </pre>
 * 500 vehicles on a 270-segment city encode to roughly 10.6 kB.
 */
public final class BinaryFrameEncoder {

    public static final int HEADER_BYTES = 16;
    public static final int VEHICLE_STRIDE = 20;

    private BinaryFrameEncoder() {
    }

    public static byte[] encode(SimulationEngine engine, int approachCount) {
        List<Vehicle> vehicles = engine.vehicles();
        List<RoadSegment> segments = engine.graph().segments();
        int count = Math.min(vehicles.size(), 65535);
        int size = HEADER_BYTES + count * VEHICLE_STRIDE + segments.size() + approachCount;
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 1).put((byte) 1).putShort((short) count);
        buf.putInt((int) engine.tick());
        buf.putFloat((float) engine.time());
        buf.putShort((short) segments.size()).putShort((short) approachCount);
        for (int i = 0; i < count; i++) {
            Vehicle v = vehicles.get(i);
            buf.putInt(v.id());
            buf.putFloat((float) v.x());
            buf.putFloat((float) v.y());
            buf.putShort((short) Math.round(normalize(v.heading()) * 10000));
            buf.put((byte) Math.min(255, Math.round(v.speed() * 4)));
            buf.put((byte) v.type().ordinal());
            buf.put((byte) v.state().ordinal());
            int flags = 0;
            if (v.accel() < -1.2 || (v.speed() < 0.5 && v.state() != VehicleState.DWELLING)) {
                flags |= 1;
            }
            if (v.sirenActive()) {
                flags |= 2;
            }
            if (v.isInConnector()) {
                flags |= 4;
            }
            buf.put((byte) flags);
            buf.put((byte) Math.round(v.fade() * 255));
            buf.put((byte) v.lane());
        }
        for (RoadSegment s : segments) {
            buf.put((byte) Math.round(Math.max(0, Math.min(1, s.congestion())) * 255));
        }
        int written = 0;
        for (Intersection ix : engine.intersections()) {
            for (Approach a : ix.approaches()) {
                if (written++ < approachCount) {
                    buf.put((byte) a.color().ordinal());
                }
            }
        }
        return buf.array();
    }

    private static double normalize(double a) {
        while (a > Math.PI) {
            a -= 2 * Math.PI;
        }
        while (a < -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }
}
