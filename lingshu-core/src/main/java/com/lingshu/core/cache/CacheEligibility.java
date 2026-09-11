package com.lingshu.core.cache;

import com.lingshu.common.dto.ChatCompletionRequest;

public final class CacheEligibility {

    private CacheEligibility() {
    }

    public static boolean isCacheable(ChatCompletionRequest request) {
        if ((request.tools() != null && !request.tools().isEmpty()) || request.tool_choice() != null) {
            return false;
        }
        return request.messages().stream().noneMatch(message ->
                "tool".equals(message.role())
                        || (message.tool_calls() != null && !message.tool_calls().isEmpty())
        );
    }
}
