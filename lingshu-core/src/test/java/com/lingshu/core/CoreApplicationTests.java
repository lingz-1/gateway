package com.lingshu.core;

import com.lingshu.core.provider.ModelProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CoreApplicationTests {

    @Autowired
    private List<ModelProvider> modelProviders;

    @Test
    void contextLoadsWithStubProvider() {
        assertIterableEquals(
                List.of("stub", "stub-fast"),
                modelProviders.stream().map(ModelProvider::id).sorted().toList()
        );
    }
}
