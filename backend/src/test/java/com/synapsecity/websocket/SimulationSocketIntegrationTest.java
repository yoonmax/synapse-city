package com.synapsecity.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Boots the real server and talks to it over a real WebSocket. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulationSocketIntegrationTest {

    @LocalServerPort
    int port;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void handshakeStreamsCityStateAndFramesAndRejectsBadCommands() throws Exception {
        BlockingQueue<JsonNode> texts = new LinkedBlockingQueue<>();
        BlockingQueue<ByteBuffer> frames = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient().execute(new AbstractWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
                texts.add(mapper.readTree(m.getPayload()));
            }

            @Override
            protected void handleBinaryMessage(WebSocketSession s, BinaryMessage m) {
                frames.add(m.getPayload());
            }
        }, "ws://localhost:" + port + "/ws/simulation").get(5, TimeUnit.SECONDS);
        session.setTextMessageSizeLimit(4 * 1024 * 1024);

        assertEquals("hello", awaitType(texts, "hello").get("type").asText());
        JsonNode city = awaitType(texts, "city");
        assertTrue(city.get("nodes").size() > 30);
        assertEquals(city.get("buildings").size() % 9, 0);
        awaitType(texts, "state");

        ByteBuffer frame = frames.poll(5, TimeUnit.SECONDS);
        assertNotNull(frame);
        frame.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, frame.get(0));
        int vehicles = Short.toUnsignedInt(frame.getShort(2));
        int segments = Short.toUnsignedInt(frame.getShort(12));
        int approaches = Short.toUnsignedInt(frame.getShort(14));
        assertEquals(city.get("segments").size(), segments);
        assertEquals(city.get("approaches").size(), approaches);
        assertEquals(16 + vehicles * 20 + segments + approaches, frame.remaining());

        session.sendMessage(new TextMessage("{\"type\":\"SET_SPEED\",\"value\":99}"));
        JsonNode error = awaitType(texts, "error");
        assertTrue(error.get("message").asText().contains("Speed"));

        session.sendMessage(new TextMessage("{\"type\":\"CLOSE_ROAD\",\"roadId\":99999}"));
        assertTrue(awaitType(texts, "error").get("message").asText().contains("Unknown road"));

        session.sendMessage(new TextMessage("{\"type\":\"PAUSE\"}"));
        JsonNode paused = null;
        for (int i = 0; i < 10 && (paused == null || paused.get("running").asBoolean()); i++) {
            paused = awaitType(texts, "state");
        }
        assertFalse(paused.get("running").asBoolean());
        session.close();
    }

    private static JsonNode awaitType(BlockingQueue<JsonNode> q, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode n = q.poll(200, TimeUnit.MILLISECONDS);
            if (n != null && type.equals(n.path("type").asText())) {
                return n;
            }
        }
        throw new AssertionError("timed out waiting for message type " + type);
    }
}
