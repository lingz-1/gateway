package com.lingshu.core.streaming;

public class StreamRoutingUnavailableException extends RuntimeException {

    public StreamRoutingUnavailableException(String message) {
        super(message);
    }

    public StreamRoutingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
