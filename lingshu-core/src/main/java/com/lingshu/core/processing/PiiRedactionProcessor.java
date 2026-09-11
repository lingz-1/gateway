package com.lingshu.core.processing;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ChatMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
@Order(140)
public class PiiRedactionProcessor implements ChatProcessor {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("(?i)(?<![\\w.])[\\w.%+-]+@[\\w.-]+\\.[A-Z]{2,}(?![\\w.])");

    @Override
    public String name() {
        return "pii-redaction";
    }

    @Override
    public boolean shouldProcess(ChatProcessingContext context) {
        return context.tenantPolicy() == null || context.tenantPolicy().piiRedactionEnabled();
    }

    @Override
    public void process(ChatProcessingContext context) {
        ChatCompletionRequest request = context.request();
        List<ChatMessage> redacted = request.messages().stream()
                .map(message -> new ChatMessage(message.role(), redact(message.content())))
                .toList();
        context.request(new ChatCompletionRequest(
                request.model(),
                redacted,
                request.stream(),
                request.temperature(),
                request.max_tokens(),
                request.top_p(),
                request.seed(),
                request.frequency_penalty(),
                request.presence_penalty()
        ));
    }

    String redact(String value) {
        String redacted = ID_CARD.matcher(value).replaceAll("[ID_CARD]");
        redacted = PHONE.matcher(redacted).replaceAll("[PHONE]");
        return EMAIL.matcher(redacted).replaceAll("[EMAIL]");
    }
}
