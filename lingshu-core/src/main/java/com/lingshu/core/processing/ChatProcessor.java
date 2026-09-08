package com.lingshu.core.processing;

public interface ChatProcessor {

    String name();

    default boolean shouldProcess(ChatProcessingContext context) {
        return true;
    }

    void process(ChatProcessingContext context);
}
