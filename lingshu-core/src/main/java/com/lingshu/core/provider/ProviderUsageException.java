package com.lingshu.core.provider;

public class ProviderUsageException extends IllegalStateException {

    private final int inputTokens;
    private final int outputTokens;

    public ProviderUsageException(String message, int inputTokens, int outputTokens) {
        super(message);
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
