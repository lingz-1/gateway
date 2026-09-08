package com.lingshu.core.cache;

import com.lingshu.core.config.LingShuProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.cache.semantic",
        name = "embedding-provider",
        havingValue = "lexical-hash",
        matchIfMissing = true
)
public class LexicalHashEmbeddingService implements EmbeddingService {

    private final int dimension;

    public LexicalHashEmbeddingService(LingShuProperties properties) {
        this.dimension = properties.getCache().getSemantic().getEmbeddingDimension();
        if (dimension < 8) {
            throw new IllegalArgumentException("Embedding dimension must be at least 8");
        }
    }

    @Override
    public double[] embed(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        int[] codePoints = normalized.codePoints().toArray();
        List<String> features = new ArrayList<>();
        for (int index = 0; index < codePoints.length; index++) {
            if (!Character.isWhitespace(codePoints[index])) {
                features.add(new String(codePoints, index, 1));
            }
            if (index + 1 < codePoints.length) {
                features.add(new String(codePoints, index, 2));
            }
        }
        for (String word : normalized.split(" ")) {
            if (!word.isBlank()) {
                features.add("word:" + word);
            }
        }

        double[] vector = new double[dimension];
        for (String feature : features) {
            byte[] digest = sha256(feature);
            int bucket = Math.floorMod(ByteBuffer.wrap(digest, 0, 4).getInt(), dimension);
            double sign = (digest[4] & 1) == 0 ? 1.0 : -1.0;
            vector[bucket] += sign;
        }
        normalize(vector);
        return vector;
    }

    static double cosineSimilarity(double[] left, double[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("Vector dimensions must match");
        }
        double dotProduct = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dotProduct += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dotProduct / Math.sqrt(leftNorm * rightNorm);
    }

    private void normalize(double[] vector) {
        double squaredNorm = 0;
        for (double value : vector) {
            squaredNorm += value * value;
        }
        if (squaredNorm == 0) {
            return;
        }
        double norm = Math.sqrt(squaredNorm);
        for (int index = 0; index < vector.length; index++) {
            vector[index] /= norm;
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
