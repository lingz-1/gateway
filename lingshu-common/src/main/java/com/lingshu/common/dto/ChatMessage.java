package com.lingshu.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ChatMessage(
        @NotBlank String role,
        String content,
        String name,
        String tool_call_id,
        List<@Valid ChatToolCall> tool_calls
) {
    public ChatMessage(String role, String content) {
        this(role, content, null, null, null);
    }

    public ChatMessage {
        if (tool_calls != null) {
            tool_calls = List.copyOf(tool_calls);
        }
        if (role != null && !role.isBlank()) {
            boolean hasContent = content != null && !content.isBlank();
            boolean valid = switch (role) {
                case "assistant" -> hasContent || (tool_calls != null && !tool_calls.isEmpty());
                case "tool" -> hasContent && tool_call_id != null && !tool_call_id.isBlank();
                default -> hasContent;
            };
            if (!valid) {
                throw new IllegalArgumentException("message content or tool_calls must match its role");
            }
        }
    }
}
