package com.synapsecity.api;

import com.synapsecity.session.ClientView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;

/**
 * One connected client: a send-buffered WebSocket, its view state and a token-bucket command
 * rate limit.
 */
public final class ClientSession {

    private static final Logger log = LoggerFactory.getLogger(ClientSession.class);
    private static final double BUCKET_CAPACITY = 40;
    private static final double REFILL_PER_SECOND = 20;

    private final WebSocketSession socket;
    /** Only touched on the sim thread. */
    final ClientView view = new ClientView();

    private double tokens = BUCKET_CAPACITY;
    private long lastRefill = System.nanoTime();

    public ClientSession(WebSocketSession raw) {
        // Slow clients drop frames instead of stalling the simulation loop.
        this.socket = new ConcurrentWebSocketSessionDecorator(raw, 2000, 4 * 1024 * 1024,
                ConcurrentWebSocketSessionDecorator.OverflowStrategy.DROP);
    }

    public String id() {
        return socket.getId();
    }

    /** Returns false when the client exceeds its command budget. */
    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        tokens = Math.min(BUCKET_CAPACITY, tokens + (now - lastRefill) / 1e9 * REFILL_PER_SECOND);
        lastRefill = now;
        if (tokens < 1) {
            return false;
        }
        tokens -= 1;
        return true;
    }

    void send(String json) {
        if (json == null || !socket.isOpen()) {
            return;
        }
        try {
            socket.sendMessage(new TextMessage(json));
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping text message to {}: {}", id(), e.getMessage());
        }
    }

    void sendBinary(byte[] frame) {
        if (!socket.isOpen()) {
            return;
        }
        try {
            socket.sendMessage(new BinaryMessage(frame));
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping frame to {}: {}", id(), e.getMessage());
        }
    }
}
