package com.wuxi.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxi.ai.llm.LlmException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 走 **OpenAI 兼容 /v1/embeddings** 协议的向量化客户端。
 *
 * 因为接口是标准协议，所以同一份代码可以对接：
 *   - 本地模型服务（我们自己的 embed_server.py，监听 8001）
 *   - Ollama（监听 11434）
 *   - 云端 API（通义 / OpenAI）
 * 换的只是 baseUrl 和 model，代码不用动。
 *
 * 这正是第 1 层「统一 ChatClient」的同一个思路——
 * 把厂商差异挡在适配层，上层只认接口。
 */
public final class OpenAiCompatibleEmbedder implements Embedder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final String model;
    private final int dimension;
    private final Duration timeout;

    public OpenAiCompatibleEmbedder(String baseUrl, String model, int dimension) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.dimension = dimension;
        this.timeout = Duration.ofSeconds(120);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 本机 embed_server.py（bge-m3，1024 维） */
    public static OpenAiCompatibleEmbedder local() {
        return new OpenAiCompatibleEmbedder("http://127.0.0.1:8001", "bge-m3", 1024);
    }

    /** Ollama 的 bge-m3 */
    public static OpenAiCompatibleEmbedder ollama() {
        return new OpenAiCompatibleEmbedder("http://127.0.0.1:11434", "bge-m3", 1024);
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new LlmException(LlmException.Kind.BAD_REQUEST, "embed", "待向量化文本为空", null);
        }
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", texts);

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/embeddings"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.BAD_REQUEST, "embed", "请求构造失败", e);
        }

        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                throw new LlmException(LlmException.Kind.BAD_REQUEST, "embed",
                        "HTTP " + resp.statusCode() + " -> " + resp.body(), null);
            }
            return parse(resp.body(), texts.size());
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.TIMEOUT, "embed",
                    "连不上向量服务（是否已启动？" + baseUrl + "）: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.UNKNOWN, "embed", "被中断", e);
        }
    }

    private List<float[]> parse(String raw, int expected) {
        try {
            JsonNode root = JSON.readTree(raw);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new IllegalStateException("响应里没有 data 数组");
            }
            List<float[]> out = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode arr = item.path("embedding");
                float[] v = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    v[i] = (float) arr.get(i).asDouble();
                }
                if (dimension > 0 && v.length != dimension) {
                    throw new IllegalStateException(
                            "维度不符：期望 " + dimension + "，实际 " + v.length
                                    + "。模型换了就要重建向量表。");
                }
                out.add(v);
            }
            if (out.size() != expected) {
                throw new IllegalStateException(
                        "返回条数不符：请求 " + expected + " 条，返回 " + out.size() + " 条");
            }
            return out;
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.UNKNOWN, "embed",
                    "解析 embedding 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelName() {
        return model;
    }
}
