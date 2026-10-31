package com.synapsecity.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsecity.api.ClientSession;
import com.synapsecity.api.SimulationService;
import com.synapsecity.websocket.protocol.CommandException;
import com.synapsecity.websocket.protocol.CommandParser;
import com.synapsecity.websocket.protocol.Messages;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** WebSocket endpoint: validates inbound commands and hands them to the simulation thread. */
@Component
public class SimulationSocketHandler extends TextWebSocketHandler {

    private final SimulationService service;
    private final CommandParser parser;
    private final ObjectMapper mapper;
    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();

    public SimulationSocketHandler(SimulationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
        this.parser = new CommandParser(mapper);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(CommandParser.MAX_PAYLOAD_CHARS * 2);
        ClientSession client = new ClientSession(session);
        sessions.put(session.getId(), client);
        service.register(client);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ClientSession client = sessions.get(session.getId());
        if (client == null) {
            return;
        }
        if (!client.tryAcquire()) {
            reject(session, "Rate limit exceeded; slow down");
            return;
        }
        try {
            service.submit(client, parser.parse(message.getPayload()));
        } catch (CommandException e) {
            reject(session, e.getMessage());
        }
    }

    private void reject(WebSocketSession session, String reason) throws Exception {
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(new Messages.ErrorMessage("error", reason))));
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ClientSession client = sessions.remove(session.getId());
        if (client != null) {
            service.unregister(client);
        }
    }
}
