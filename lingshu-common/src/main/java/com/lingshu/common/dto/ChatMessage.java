package com.lingshu.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ChatMessage(
        @NotBlank String role,
        Object content,
        String name,
        String tool_call_id,
        List<@Valid ChatToolCall> tool_calls
) {
    public ChatMessage(String role, String content) {
        this(role, content, null, null, null);
    }

    public ChatMessage {
        content = freeze(content);
        if (tool_calls != null) {
            tool_calls = List.copyOf(tool_calls);
        }
        if (role != null && !role.isBlank()) {
            boolean hasContent = validContent(role, content);
            boolean valid = switch (role) {
                case "assistant" -> hasContent || (tool_calls != null && !tool_calls.isEmpty());
                case "tool" -> hasContent
                        && tool_call_id != null && !tool_call_id.isBlank()
                        && textOnly(content);
                case "system", "developer" -> hasContent && textOnly(content);
                default -> hasContent;
            };
            if (!valid) {
                throw new IllegalArgumentException("message content or tool_calls must match its role");
            }
        }
    }

    public boolean hasStructuredContent() {
        return content instanceof List<?>;
    }

    public String promptText() {
        StringBuilder value = new StringBuilder(role).append(':');
        appendPromptContent(value, content);
        if (tool_call_id != null) {
            value.append("|tool_call_id=").append(tool_call_id);
        }
        if (tool_calls != null) {
            tool_calls.forEach(call -> value
                    .append("|tool_call=").append(call.id())
                    .append(':').append(call.function().name())
                    .append(':').append(call.function().arguments()));
        }
        return value.toString();
    }

    private static boolean validContent(String role, Object value) {
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (!(value instanceof List<?> parts) || parts.isEmpty()) {
            return false;
        }
        return parts.stream().allMatch(part -> validPart(role, part));
    }

    private static boolean validPart(String role, Object value) {
        if (!(value instanceof Map<?, ?> part) || !(part.get("type") instanceof String type)) {
            return false;
        }
        return switch (type) {
            case "text" -> nonBlank(part.get("text"));
            case "refusal" -> "assistant".equals(role) && nonBlank(part.get("refusal"));
            case "image_url" -> "user".equals(role) && validImage(part.get("image_url"));
            case "input_audio" -> "user".equals(role) && validAudio(part.get("input_audio"));
            case "file" -> "user".equals(role) && validFile(part.get("file"));
            default -> false;
        };
    }

    private static boolean validImage(Object value) {
        if (!(value instanceof Map<?, ?> image) || !nonBlank(image.get("url"))) {
            return false;
        }
        Object detail = image.get("detail");
        return detail == null || Set.of("auto", "low", "high").contains(detail);
    }

    private static boolean validAudio(Object value) {
        if (!(value instanceof Map<?, ?> audio) || !nonBlank(audio.get("data"))) {
            return false;
        }
        return Set.of("wav", "mp3").contains(audio.get("format"));
    }

    private static boolean validFile(Object value) {
        if (!(value instanceof Map<?, ?> file)) {
            return false;
        }
        return nonBlank(file.get("file_data")) || nonBlank(file.get("file_id"));
    }

    private static boolean textOnly(Object value) {
        if (value instanceof String) {
            return true;
        }
        return value instanceof List<?> parts && parts.stream().allMatch(part ->
                part instanceof Map<?, ?> map && "text".equals(map.get("type"))
        );
    }

    private static boolean nonBlank(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), freeze(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ChatMessage::freeze).toList();
        }
        return value;
    }

    private static void appendPromptContent(StringBuilder target, Object value) {
        if (value instanceof String text) {
            target.append(text);
            return;
        }
        if (!(value instanceof List<?> parts)) {
            return;
        }
        for (Object item : parts) {
            if (!(item instanceof Map<?, ?> part)) {
                continue;
            }
            String type = String.valueOf(part.get("type"));
            switch (type) {
                case "text" -> target.append(part.get("text"));
                case "refusal" -> target.append(part.get("refusal"));
                case "image_url" -> target.append("[image]");
                case "input_audio" -> target.append("[audio]");
                case "file" -> target.append("[file]");
                default -> target.append("[content]");
            }
        }
    }
}
