package com.lingshu.core.processing;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiiRedactionProcessorTest {

    private final PiiRedactionProcessor processor = new PiiRedactionProcessor();

    @Test
    void redactsChinesePhoneIdCardAndEmail() {
        String source = "手机13800138000，身份证11010519491231002X，邮箱user@example.com";

        assertEquals("手机[PHONE]，身份证[ID_CARD]，邮箱[EMAIL]", processor.redact(source));
    }

    @Test
    void redactsTextPartsWithoutChangingMediaPayload() {
        String image = "data:image/png;base64,user@example.com";
        ChatCompletionRequest request = new ChatCompletionRequest(
                "stub-echo-v1",
                List.of(new ChatMessage(
                        "user",
                        List.of(
                                Map.of("type", "text", "text", "邮箱user@example.com"),
                                Map.of("type", "image_url", "image_url", Map.of("url", image))
                        ),
                        null,
                        null,
                        null
                )),
                false,
                null,
                null,
                null
        );
        ChatProcessingContext context = new ChatProcessingContext(request, "trace-1", "tenant-1");

        processor.process(context);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) context.request().messages().getFirst().content();
        assertEquals("邮箱[EMAIL]", parts.getFirst().get("text"));
        @SuppressWarnings("unchecked")
        Map<String, Object> imageUrl = (Map<String, Object>) parts.get(1).get("image_url");
        assertEquals(image, imageUrl.get("url"));
    }
}
