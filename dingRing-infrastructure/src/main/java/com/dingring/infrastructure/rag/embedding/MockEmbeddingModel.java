package com.dingring.infrastructure.rag.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mock EmbeddingModel（Phase E 占位）。
 * <p>Embedding 模型选型待定，先用 Mock 搭建完整 RAG 框架。
 * <p>策略：基于文本 hash 生成确定性向量，保证相同文本得到相同向量（模拟语义相似性）。
 * <p>后续确定模型后，改配置切换为真实 EmbeddingModel（OpenAI 兼容 API 或本地 ONNX）。
 */
@Slf4j
@Component("mockEmbeddingModel")
@Primary
@ConditionalOnProperty(name = "dingring.rag.embedding.provider", havingValue = "mock", matchIfMissing = true)
public class MockEmbeddingModel implements EmbeddingModel {

    /** 向量维度（与设计文档一致，OpenAI text-embedding-ada-002 默认 1536） */
    private static final int DIMENSIONS = 1536;

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        return generateDeterministicVector(text, DIMENSIONS);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<org.springframework.ai.embedding.Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            float[] vector = generateDeterministicVector(request.getInstructions().get(i), DIMENSIONS);
            embeddings.add(new org.springframework.ai.embedding.Embedding(vector, i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    /**
     * 基于文本内容生成确定性向量。
     * <p>用文本字节 hash 作为种子，生成伪随机但确定的 float 向量。
     * 相同文本 → 相同向量（模拟语义一致性），不同文本 → 不同向量（模拟语义差异）。
     */
    private float[] generateDeterministicVector(String text, int dimensions) {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        long seed = 0;
        for (byte b : bytes) {
            seed = seed * 31 + b;
        }
        java.util.Random rng = new java.util.Random(seed);
        float[] vector = new float[dimensions];
        float norm = 0;
        for (int i = 0; i < dimensions; i++) {
            vector[i] = rng.nextFloat() * 2 - 1;  // [-1, 1)
            norm += vector[i] * vector[i];
        }
        // L2 归一化（模拟真实 Embedding，使余弦相似度有意义）
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimensions; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }
}
