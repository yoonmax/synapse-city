package com.synapsecity.browser;

import com.synapsecity.session.ClientView;
import com.synapsecity.session.RateMeter;
import com.synapsecity.session.SimulationSession;
import com.synapsecity.simulation.core.SimulationConfig;
import com.synapsecity.simulation.core.SimulationEngine;
import com.synapsecity.simulation.roads.CityLayout;
import com.synapsecity.simulation.scenarios.ComparisonRunner;
import com.synapsecity.simulation.scenarios.ScenarioPreset;
import com.synapsecity.websocket.protocol.BinaryFrameEncoder;
import com.synapsecity.websocket.protocol.CommandException;
import com.synapsecity.websocket.protocol.CommandParser;
import com.synapsecity.websocket.protocol.Messages;
import com.synapsecity.websocket.protocol.ProtocolMapper;
import com.synapsecity.websocket.protocol.WireJson;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Browser host for a {@link SimulationSession}, compiled to JavaScript by TeaVM and run in a Web
 * Worker. It speaks exactly the messages the WebSocket host sends (JSON strings plus binary frames),
 * so the client uses the same code path for both. Each visitor gets a private simulation.
 *
 * <p>{@code main(["hash", layout, vehicles, seconds, seed])} prints a state hash instead of starting
 * the worker; {@code scripts/check-browser-parity.sh} compares it against the JVM.</p>
 */
public final class BrowserMain implements SimulationSession.Host {

    private static final int LOOP_MILLIS = 50;
    private static final long STATE_INTERVAL_NANOS = 500_000_000L;
    private static final long DETAIL_INTERVAL_NANOS = 250_000_000L;

    @JSFunctor
    interface StringCallback extends JSObject {
        void accept(String value);
    }

    @JSFunctor
    interface Callback extends JSObject {
        void run();
    }

    @JSBody(params = "callback", script = "self.onmessage = function(e) { callback(e.data); };")
    private static native void onMessage(StringCallback callback);

    @JSBody(params = {"callback", "millis"}, script = "setInterval(callback, millis);")
    private static native void every(Callback callback, int millis);

    @JSBody(params = "callback", script = "setTimeout(callback, 0);")
    private static native void later(Callback callback);

    @JSBody(params = "text", script = "self.postMessage(text);")
    private static native void postText(String text);

    @JSBody(params = "bytes", script = "var copy = bytes.slice().buffer; self.postMessage(copy, [copy]);")
    private static native void postBytes(@JSByRef byte[] bytes);

    private final SimulationSession session = new SimulationSession(this);
    private final CommandParser parser = new CommandParser();
    private final ClientView view = new ClientView();
    private final RateMeter frameRate = new RateMeter();
    private final RateMeter stepRate = new RateMeter();
    private long lastWallNanos;
    private long lastStateNanos;
    private long lastDetailNanos;
    private int lastFrameBytes;
    private boolean comparisonRunning;

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("hash")) {
            printHash(args);
            return;
        }
        new BrowserMain().start();
    }

    private static void printHash(String[] a) {
        SimulationEngine e = new SimulationEngine(SimulationConfig.defaults()
                .withLayout(CityLayout.valueOf(a[1])).withTargetVehicles(Integer.parseInt(a[2]))
                .withSeed(Long.parseLong(a[4])));
        e.runFor(Double.parseDouble(a[3]));
        System.out.println(Long.toHexString(e.stateHash()) + " trips=" + e.metrics().completedTrips());
    }

    private void start() {
        onMessage(this::receive);
        send(new Messages.Hello("hello", Messages.PROTOCOL_VERSION, SimulationConfig.DT,
                BinaryFrameEncoder.VEHICLE_STRIDE));
        send(new Messages.ScenariosMessage("scenarios", ProtocolMapper.scenarios()));
        session.start();
        session.onClientConnected(view);
        lastWallNanos = System.nanoTime();
        lastStateNanos = lastWallNanos;
        sendState(lastWallNanos);
        sendFrame(lastWallNanos);
        every(this::tick, LOOP_MILLIS);
    }

    private void receive(String payload) {
        try {
            session.apply(view, parser.parse(payload));
        } catch (CommandException e) {
            send(new Messages.ErrorMessage("error", e.getMessage()));
        } catch (Throwable t) {
            // One failed command must not take down the visitor's simulation.
            send(new Messages.ErrorMessage("error", "That action failed; the simulation is still running"));
        }
    }

    private void tick() {
        long now = System.nanoTime();
        int steps = session.advance((now - lastWallNanos) / 1e9);
        lastWallNanos = now;
        stepRate.add(steps, now);
        if (steps > 0) {
            sendFrame(now);
        }
        if (now - lastStateNanos >= STATE_INTERVAL_NANOS) {
            lastStateNanos = now;
            sendState(now);
        }
        if (now - lastDetailNanos >= DETAIL_INTERVAL_NANOS) {
            lastDetailNanos = now;
            sendDetails();
        }
    }

    private void sendState(long now) {
        double stepsPerSecond = stepRate.perSecond(now);
        send(session.stateFor(view, session.baseState(new Messages.ServerStats(1, lastFrameBytes,
                frameRate.perSecond(now), stepsPerSecond, stepsPerSecond * SimulationConfig.DT), comparisonRunning)));
    }

    private void sendFrame(long now) {
        byte[] frame = session.encodeFrame();
        lastFrameBytes = frame.length;
        frameRate.add(1, now);
        postBytes(frame);
    }

    private void sendDetails() {
        for (Object message : session.detailsFor(view)) {
            send(message);
        }
    }

    private static void send(Object message) {
        postText(WireJson.write(message));
    }

    @Override
    public void onCityChanged() {
        view.reset();
        send(session.city());
    }

    @Override
    public void onFrameDirty() {
        sendFrame(System.nanoTime());
    }

    @Override
    public void onDetailsRequested(ClientView ignored) {
        sendDetails();
    }

    /**
     * There is one worker thread, so the comparison runs on it and the live simulation holds still
     * until it finishes. Progress messages still reach the page while this runs.
     */
    @Override
    public void onComparisonRequested(SimulationConfig base, ScenarioPreset preset, double duration) {
        if (comparisonRunning) {
            throw new CommandException("A comparison is already running");
        }
        comparisonRunning = true;
        send(new Messages.ComparisonMessage("comparison", "running", 0,
                "Running fixed-time and adaptive control on seed " + base.seed(), null));
        later(() -> {
            try {
                double[] lastSent = {0};
                var result = ComparisonRunner.compare(base, preset, duration, p -> {
                    if (p - lastSent[0] >= 0.05) {
                        lastSent[0] = p;
                        send(new Messages.ComparisonMessage("comparison", "running", p, null, null));
                    }
                });
                send(new Messages.ComparisonMessage("comparison", "done", 1, null, result));
            } catch (RuntimeException e) {
                send(new Messages.ComparisonMessage("comparison", "error", 0, e.getMessage(), null));
            } finally {
                comparisonRunning = false;
                lastWallNanos = System.nanoTime();
            }
        });
    }
}
