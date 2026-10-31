package com.synapsecity.websocket.protocol;

/** A command that failed validation; the message is safe to show to the client. */
public class CommandException extends RuntimeException {

    public CommandException(String message) {
        super(message);
    }
}
