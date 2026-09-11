package com.lingshu.core.cache;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ChatMessage;
import com.lingshu.core.config.LingShuProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;

@Component
public class ExactCacheKeyFactory {

    private final String promptVersion;

    public ExactCacheKeyFactory(LingShuProperties properties) {
        this.promptVersion = properties.getCache().getExact().getPromptVersion();
    }

    public String create(String tenantId, ChatCompletionRequest request) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, tenantId);
        append(canonical, request.model());
        append(canonical, promptVersion);
        append(canonical, Boolean.toString(Boolean.TRUE.equals(request.stream())));
        append(canonical, request.temperature() == null ? "default" : request.temperature().toString());
        append(canonical, request.max_tokens() == null ? "default" : request.max_tokens().toString());
        append(canonical, request.top_p() == null ? "default" : request.top_p().toString());
        append(canonical, request.seed() == null ? "default" : request.seed().toString());
        append(canonical, request.frequency_penalty() == null ? "default" : request.frequency_penalty().toString());
        append(canonical, request.presence_penalty() == null ? "default" : request.presence_penalty().toString());
        for (ChatMessage message : request.messages()) {
            append(canonical, message.role());
            appendValue(canonical, message.content());
            appendValue(canonical, message.name());
            appendValue(canonical, message.tool_call_id());
            appendValue(canonical, message.tool_calls());
        }
        appendValue(canonical, request.tools());
        appendValue(canonical, request.tool_choice());
        return sha256(canonical.toString());
    }

    private void appendValue(StringBuilder target, Object value) {
        if (value == null) {
            append(target, "null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            append(target, "map");
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        append(target, String.valueOf(entry.getKey()));
                        appendValue(target, entry.getValue());
                    });
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            append(target, "list");
            iterable.forEach(item -> appendValue(target, item));
            return;
        }
        if (value.getClass().isRecord()) {
            append(target, value.getClass().getName());
            for (var component : value.getClass().getRecordComponents()) {
                try {
                    append(target, component.getName());
                    appendValue(target, component.getAccessor().invoke(value));
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Unable to canonicalize record", exception);
                }
            }
            return;
        }
        append(target, value.toString());
    }

    private void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append('|');
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
