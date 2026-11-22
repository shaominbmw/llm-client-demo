package com.wuxi.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不花钱的本地测试：起一个假的 OpenAI 兼容服务，验证整条链路。
 * 这一步等价于「真接 API 之前先把管道打通」。
 */
class LlmClientRegistryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static HttpServer server;
    private static String baseUrl;
    private static volatile String lastRequestBody;

    @BeforeAll
    static void startMockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // 正常响应：把收到的 model 原样回显，方便验证「切换是否生效」
        server.createContext("/v1/chat/completions", exchange -> {
            String reqBody = readBody(exchange);
            lastRequestBody = reqBody;
            String model = "unknown";
            try {
                model = JSON.readTree(reqBody).path("model").asText("unknown");
            } catch (Exception ignored) {
                // 解析失败就用 unknown，让断言暴露问题
            }
            String resp = """
                    {
                      "id": "mock-1",
                      "model": "%s",
                      "choices": [
                        {"index": 0,
                         "message": {"role": "assistant", "content": "mock 回复：收到 %s"},
                         "finish_reason": "stop"}
                      ],
                      "usage": {"prompt_tokens": 12, "completion_tokens": 7, "total_tokens": 19}
                    }
                    """.formatted(model, model);
            writeJson(exchange, 200, resp);
        });

        // 401：验证认证错误映射
        server.createContext("/v1/unauthorized/chat/completions", exchange -> {
            readBody(exchange);
            writeJson(exchange, 401, "{\"error\":{\"message\":\"invalid api key\"}}");
        });

        // 403 + quota 关键字：验证「额度用尽」不被误判成认证失败
        server.createContext("/v1/quota/chat/completions", exchange -> {
            readBody(exchange);
            writeJson(exchange, 403, """
                    {"error":{"message":"Free quota exhausted. Please add funds.",
                     "type":"AllocationQuota.FreeTierOnly"}}
                    """);
        });

        // 404：验证路径/模型错误被映射成 BAD_REQUEST
        server.createContext("/v1/notfound/chat/completions", exchange -> {
            readBody(exchange);
            writeJson(exchange, 404, "{\"type\":\"error\",\"error\":{\"message\":\"not support\"}}");
        });

        // SSE 流式：分三片推送，最后一片带 finish_reason 和 usage
        server.createContext("/v1/stream/chat/completions", exchange -> {
            readBody(exchange);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                for (String piece : new String[]{"你", "好", "呀"}) {
                    String chunk = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                            + piece + "\"}}]}\n\n";
                    os.write(chunk.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                os.write(("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopMockServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 两个「厂商」指向同一个假服务，只是名字和模型不同——模拟 DeepSeek / 通义 */
    private LlmClientRegistry registry() {
        Map<String, LlmClient> clients = new LinkedHashMap<>();
        clients.put("mock-a", new OpenAiCompatibleClient(
                new ProviderConfig("mock-a", baseUrl, "key-a", "model-a", Duration.ofSeconds(10))));
        clients.put("mock-b", new OpenAiCompatibleClient(
                new ProviderConfig("mock-b", baseUrl, "key-b", "model-b", Duration.ofSeconds(10))));
        return new LlmClientRegistry(clients, "mock-a");
    }

    @Test
    @DisplayName("同一段业务代码，只换 provider 参数就能切模型")
    void sameBusinessCode_switchProvider() {
        LlmClientRegistry registry = registry();

        // 业务代码长这样——它不知道 deepseek 也不知道 qwen
        LlmResponse a = registry.chat(LlmRequest.of("你好"));
        LlmResponse b = registry.chat(LlmRequest.of("你好").withProvider("mock-b"));

        assertEquals("mock-a", a.provider());
        assertEquals("model-a", a.model());
        assertEquals("mock-b", b.provider());
        assertEquals("model-b", b.model());

        assertTrue(a.text().contains("mock 回复"));
        assertTrue(b.text().contains("model-b"));
    }

    @Test
    @DisplayName("token 用量与结束原因被正确解析")
    void usage_isParsed() {
        LlmResponse r = registry().chat(LlmRequest.of("count tokens"));

        assertEquals(12, r.promptTokens());
        assertEquals(7, r.completionTokens());
        assertEquals(19, r.totalTokens());
        assertEquals("stop", r.finishReason());
        assertNotNull(r.text());
        assertFalse(r.text().isBlank());
    }

    @Test
    @DisplayName("请求体符合 OpenAI 协议：有 model 和 messages")
    void requestBody_followsProtocol() throws Exception {
        lastRequestBody = null;
        registry().chat(LlmRequest.of(List.of(Msg.system("你是助手"), Msg.user("hi")))
                .withTemperature(0.2)
                .withMaxTokens(100));

        assertNotNull(lastRequestBody, "假服务没有收到请求");
        JsonNode node = JSON.readTree(lastRequestBody);
        assertEquals("model-a", node.path("model").asText());
        assertEquals(2, node.path("messages").size());
        assertEquals("system", node.path("messages").path(0).path("role").asText());
        assertEquals("user", node.path("messages").path(1).path("role").asText());
        assertEquals(0.2, node.path("temperature").asDouble(), 0.0001);
        assertEquals(100, node.path("max_tokens").asInt());
    }

    @Test
    @DisplayName("top_p 与 stop 会正确出现在请求体里")
    void samplingParams_reachWire() throws Exception {
        lastRequestBody = null;
        registry().chat(LlmRequest.of("hi")
                .withTemperature(0.2)
                .withTopP(0.85)
                .withStop(List.of("\n\n", "###")));

        JsonNode node = JSON.readTree(lastRequestBody);
        assertEquals(0.2, node.path("temperature").asDouble(), 0.0001);
        assertEquals(0.85, node.path("top_p").asDouble(), 0.0001);
        assertEquals(2, node.path("stop").size());
        assertEquals("\n\n", node.path("stop").path(0).asText());
        assertEquals("###", node.path("stop").path(1).asText());
    }

    @Test
    @DisplayName("未知 provider 立刻报错，并列出已配置的厂商")
    void unknownProvider_failsFast() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry().chat(LlmRequest.of("hi").withProvider("not-exist")));
        assertTrue(e.getMessage().contains("not-exist"));
        assertTrue(e.getMessage().contains("mock-a"));
    }

    @Test
    @DisplayName("401 被映射成 AUTH，且标记为不可重试")
    void unauthorized_mapsToAuth() {
        LlmClient bad = new OpenAiCompatibleClient(
                new ProviderConfig("bad", baseUrl + "/unauthorized", "wrong-key", "m", Duration.ofSeconds(10)));

        LlmException e = assertThrows(LlmException.class, () -> bad.chat(LlmRequest.of("hi")));

        assertEquals(LlmException.Kind.AUTH, e.kind());
        assertFalse(e.retryable(), "认证失败重试没有意义");
    }

    @Test
    @DisplayName("403 + quota 关键字被映射成 QUOTA_EXCEEDED，而不是 AUTH")
    void quota_mapsToQuotaExceeded() {
        LlmClient noMoney = new OpenAiCompatibleClient(
                new ProviderConfig("nomoney", baseUrl + "/quota", "ok-key", "m", Duration.ofSeconds(10)));

        LlmException e = assertThrows(LlmException.class, () -> noMoney.chat(LlmRequest.of("hi")));

        assertEquals(LlmException.Kind.QUOTA_EXCEEDED, e.kind());
        assertFalse(e.retryable(), "没钱了重试也没用，得去充值");
    }

    @Test
    @DisplayName("404 被映射成 BAD_REQUEST（base-url 或 model 写错）")
    void notFound_mapsToBadRequest() {
        LlmClient wrongPath = new OpenAiCompatibleClient(
                new ProviderConfig("badpath", baseUrl + "/notfound", "ok-key", "m", Duration.ofSeconds(10)));

        LlmException e = assertThrows(LlmException.class, () -> wrongPath.chat(LlmRequest.of("hi")));

        assertEquals(LlmException.Kind.BAD_REQUEST, e.kind());
        assertTrue(e.getMessage().contains("404"));
    }

    @Test
    @DisplayName("流式：逐片回调，最后聚合成完整文本与 usage")
    void stream_collectsDeltas() {
        LlmClient streaming = new OpenAiCompatibleClient(
                new ProviderConfig("streamer", baseUrl + "/stream", "k", "m", Duration.ofSeconds(10)));

        List<String> deltas = new ArrayList<>();
        LlmResponse resp = streaming.stream(LlmRequest.of("hi"), deltas::add);

        assertEquals(List.of("你", "好", "呀"), deltas, "应该收到 3 次回调");
        assertEquals("你好呀", String.join("", deltas));
        assertEquals("你好呀", resp.text(), "聚合结果应与回调拼接一致");
        assertEquals(5, resp.promptTokens());
        assertEquals(3, resp.completionTokens());
        assertEquals("stop", resp.finishReason());
    }

    @Test
    @DisplayName("没配 API Key 时，在发请求之前就失败")
    void missingApiKey_failsBeforeNetwork() {
        LlmClient noKey = new OpenAiCompatibleClient(
                new ProviderConfig("nokey", baseUrl, "", "m", Duration.ofSeconds(5)));

        LlmException e = assertThrows(LlmException.class, () -> noKey.chat(LlmRequest.of("hi")));

        assertEquals(LlmException.Kind.AUTH, e.kind());
    }
}
