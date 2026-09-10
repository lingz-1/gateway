package com.lingshu.core.provider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "lingshu.provider.openai.enabled=true",
        "lingshu.provider.openai.api-key=test-key",
        "lingshu.provider.openai.model=shared-chat-model",
        "lingshu.provider.openai.upstream-model=openai-test-model",
        "lingshu.provider.deepseek.enabled=false"
})
class OpenAiProviderConfigurationTest {

    @Autowired
    private ModelProviderRouter router;

    @Test
    void registersOpenAiWithoutRequiringDeepSeek() {
        assertTrue(router.availableModels().stream().anyMatch(model ->
                "shared-chat-model".equals(model.id())
                        && "openai".equals(model.provider())));
    }
}
