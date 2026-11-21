package com.wuxi.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 唯一的实现：OpenAI 兼容协议。
 * DeepSeek / 通义千问 / 智谱 GLM / Kimi / 豆包，全部用它，区别只在配置。
 */
public final class OpenAiCompatibleClient implements LlmClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final Integer defaultMaxTokens;
    private final Duration timeout;

    public OpenAiCompatibleClient(ProviderConfig cfg) {
        this.provider = cfg.name();
        String url = cfg.baseUrl() == null ? "" : cfg.baseUrl().trim();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.apiKey = cfg.apiKey() == null ? "" : cfg.apiKey().trim();
        this.defaultModel = cfg.model();
        this.defaultMaxTokens = cfg.maxTokens();
        this.timeout = cfg.timeout();
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }

    // ==================== 非流式 ====================

    @Override
    public LlmResponse chat(LlmRequest req) {
        String model = resolveModel(req);
        HttpRequest httpReq = buildRequest(toJson(req, model, false));
        try {
            HttpResponse<String> resp = HTTP.send(httpReq,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                throw mapError(resp.statusCode(), resp.body());
            }
            return parse(resp.body(), model);
        } catch (IOException e) {
            throw timeoutError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.UNKNOWN, provider, "请求被中断", e);
        }
    }

    // ==================== 流式（SSE） ====================

    @Override
    public LlmResponse stream(LlmRequest req, Consumer<String> onDelta) {
        String model = resolveModel(req);
        HttpRequest httpReq = buildRequest(toJson(req, model, true));

        StringBuilder text = new StringBuilder();
        int promptTokens = 0;
        int completionTokens = 0;
        String finishReason = "";

        try {
            HttpResponse<Stream<String>> resp = HTTP.send(httpReq,
                    HttpResponse.BodyHandlers.ofLines());

            // 流式下也可能直接返回错误（比如 401/404），此时 body 是一段 JSON 而不是 SSE
            if (resp.statusCode() >= 400) {
                String errBody = resp.body().collect(Collectors.joining("\n"));
                throw mapError(resp.statusCode(), errBody);
            }

            try (Stream<String> lines = resp.body()) {
                for (String rawLine : (Iterable<String>) lines::iterator) {
                    String line = rawLine.trim();
                    // SSE 只关心 data: 开头的行；event:/id:/注释行一律跳过
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }

                    JsonNode node = JSON.readTree(data);
                    JsonNode choice = node.path("choices").path(0);

                    String delta = choice.path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        text.append(delta);
                        if (onDelta != null) {
                            onDelta.accept(delta);
                        }
                    }

                    String fr = choice.path("finish_reason").asText("");
                    if (!fr.isEmpty()) {
                        finishReason = fr;
                    }

                    // 最后一片会带 usage（需要请求里 stream_options.include_usage=true）
                    JsonNode usage = node.path("usage");
                    if (!usage.isMissingNode() && !usage.isNull()) {
                        promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                        completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                    }
                }
            }
        } catch (IOException e) {
            throw timeoutError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.UNKNOWN, provider, "请求被中断", e);
        }

        return new LlmResponse(provider, model, text.toString(), promptTokens, completionTokens, finishReason);
    }

    // ==================== 内部工具 ====================

    private String resolveModel(LlmRequest req) {
        return (req.model() == null || req.model().isBlank()) ? defaultModel : req.model();
    }

    private HttpRequest buildRequest(String payload) {
        if (baseUrl.isBlank()) {
            throw new LlmException(LlmException.Kind.BAD_REQUEST, provider, "base-url 未配置", null);
        }
        if (apiKey.isBlank()) {
            throw new LlmException(LlmException.Kind.AUTH, provider, "api-key 为空，请先设置环境变量", null);
        }
        return HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
    }

    private String toJson(LlmRequest req, String model, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Map<String, String>> msgs = req.messages().stream()
                .map(m -> {
                    Map<String, String> mm = new LinkedHashMap<>();
                    mm.put("role", m.role());
                    mm.put("content", m.content());
                    return mm;
                })
                .toList();
        body.put("messages", msgs);
        if (req.temperature() != null) {
            body.put("temperature", req.temperature());
        }
        Integer maxTokens = req.maxTokens() != null ? req.maxTokens() : defaultMaxTokens;
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        body.put("stream", stream);
        if (stream) {
            // 让服务端在最后一片里带上 usage，否则流式下拿不到 token 统计
            body.put("stream_options", Map.of("include_usage", true));
        }
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.BAD_REQUEST, provider, "请求序列化失败", e);
        }
    }

    private LlmException timeoutError(IOException e) {
        return new LlmException(LlmException.Kind.TIMEOUT, provider,
                "网络异常或超时: " + e.getMessage(), e);
    }

    private LlmResponse parse(String rawBody, String fallbackModel) {
        try {
            JsonNode root = JSON.readTree(rawBody);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            JsonNode usage = root.path("usage");

            // 推理模型（如 qwen4-max）会把思考过程放在 reasoning_content，
            // 正文在 content。如果正文为空（思考把 token 用光了），退回到 reasoning_content，
            // 至少让调用方看到东西，而不是一片空白。
            String text = message.path("content").asText("");
            if (text.isBlank()) {
                String reasoning = message.path("reasoning_content").asText("");
                if (!reasoning.isBlank()) {
                    text = "[仅返回思考过程，正文被截断] " + reasoning;
                }
            }

            return new LlmResponse(
                    provider,
                    root.path("model").asText(fallbackModel),
                    text,
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    choice.path("finish_reason").asText("")
            );
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.UNKNOWN, provider,
                    "响应解析失败，原始内容: " + rawBody, e);
        }
    }

    private LlmException mapError(int status, String rawBody) {
        String lower = rawBody == null ? "" : rawBody.toLowerCase(Locale.ROOT);

        // 余额/免费额度类问题，各家措辞不同，先按关键字识别
        boolean quotaIssue = lower.contains("quota")
                || lower.contains("insufficient")
                || lower.contains("balance")
                || lower.contains("arrears")
                || lower.contains("billing")
                || lower.contains("free tier");

        LlmException.Kind kind;
        if (quotaIssue) {
            kind = LlmException.Kind.QUOTA_EXCEEDED;
        } else {
            kind = switch (status) {
                case 401 -> LlmException.Kind.AUTH;
                case 403 -> LlmException.Kind.AUTH;
                case 402 -> LlmException.Kind.QUOTA_EXCEEDED;
                case 404 -> LlmException.Kind.BAD_REQUEST;
                case 429 -> LlmException.Kind.RATE_LIMIT;
                case 400 -> (lower.contains("content_filter") || lower.contains("content_policy")
                        || lower.contains("敏感"))
                        ? LlmException.Kind.CONTENT_FILTER
                        : LlmException.Kind.BAD_REQUEST;
                case 500, 502, 503, 504 -> LlmException.Kind.SERVER_ERROR;
                default -> LlmException.Kind.UNKNOWN;
            };
        }

        String hint = "";
        if (status == 404) {
            hint = " （常见原因：base-url 多写了 /responses 或 /chat/completions，"
                    + "或者 model 名在该端点上不存在）";
        }
        return new LlmException(kind, provider, "HTTP " + status + hint + " -> " + rawBody, null);
    }
}
