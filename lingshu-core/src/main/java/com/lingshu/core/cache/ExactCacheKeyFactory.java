package com.lingshu.core.cache;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ChatMessage;
import com.lingshu.core.config.LingShuProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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
        for (ChatMessage message : request.messages()) {
            append(canonical, message.role());
            append(canonical, message.content());
        }
        return sha256(canonical.toString());
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
