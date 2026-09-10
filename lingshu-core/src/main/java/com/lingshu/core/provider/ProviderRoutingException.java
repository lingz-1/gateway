package com.lingshu.core.provider;

public class ProviderRoutingException extends RuntimeException {

    private final int inputTokens;
    private final int outputTokens;

    public ProviderRoutingException(String message) {
        this(message, null, 0, 0);
    }

    public ProviderRoutingException(String message, Throwable cause, int inputTokens, int outputTokens) {
        super(message, cause);
        this.inputTokens = Math.max(0, inputTokens);
        this.outputTokens = Math.max(0, outputTokens);
    }

    public int inputTokens() {
        return inputTokens;
    }

    public int outputTokens() {
        return outputTokens;
    }
}
