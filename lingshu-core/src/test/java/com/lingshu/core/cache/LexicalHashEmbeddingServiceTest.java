package com.lingshu.core.cache;

import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexicalHashEmbeddingServiceTest {

    private final LexicalHashEmbeddingService service = service();

    @Test
    void createsDeterministicNormalizedVector() {
        double[] first = service.embed("Reset my password now");
        double[] second = service.embed("Reset my password now");

        assertArrayEquals(first, second);
        double norm = Math.sqrt(java.util.Arrays.stream(first).map(value -> value * value).sum());
        assertEquals(1.0, norm, 0.000001);
    }

    @Test
    void scoresLexicallySimilarTextAboveUnrelatedText() {
        double[] source = service.embed("reset my account password now");
        double[] similar = service.embed("reset my account password please");
        double[] unrelated = service.embed("forecast tomorrow weather temperature");

        assertTrue(
                LexicalHashEmbeddingService.cosineSimilarity(source, similar)
                        > LexicalHashEmbeddingService.cosineSimilarity(source, unrelated)
        );
    }

    private LexicalHashEmbeddingService service() {
        LingShuProperties properties = new LingShuProperties();
        properties.getCache().getSemantic().setEmbeddingDimension(128);
        return new LexicalHashEmbeddingService(properties);
    }
}
