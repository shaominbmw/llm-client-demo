package com.wuxi.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化端到端测试：本地假服务 + 真 JSON 解析 + 真重试逻辑。
 * 不花钱、不依赖网络。
 */
class StructuredOutputTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicInteger flakyCalls = new AtomicInteger(0);
    private static volatile String lastRequestBody;

    /** 测试用的目标类型 */
    public record Device(String name, String level) {
    }

    @BeforeAll
    static void startMock() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // 正常：返回合法 JSON
        server.createContext("/v1/ok/chat/completions", ex -> {
            readBody(ex);
            writeChat(ex, "{\"name\":\"注塑机\",\"level\":\"HIGH\"}");
        });

        // 正常，但带围栏和废话
        server.createContext("/v1/messy/chat/completions", ex -> {
            readBody(ex);
            writeChat(ex, "好的，结果如下：\n```json\n{\"name\":\"空压机\",\"level\":\"LOW\"}\n```\n请查收。");
        });

        // 第一次返回垃圾，第二次返回正常 → 验证自动重试
        server.createContext("/v1/flaky/chat/completions", ex -> {
            readBody(ex);
            if (flakyCalls.getAndIncrement() == 0) {
                writeChat(ex, "抱歉，我无法理解这个请求。");
            } else {
                writeChat(ex, "{\"name\":\"冲床\",\"level\":\"MEDIUM\"}");
            }
        });

        // 永远返回垃圾 → 验证最终失败
        server.createContext("/v1/bad/chat/completions", ex -> {
            readBody(ex);
            writeChat(ex, "这个问题我回答不了。");
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopMock() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastRequestBody = body;
        return body;
    }

    private static void writeChat(HttpExchange ex, String content) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", "mock-model");
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", content);
        choice.put("finish_reason", "stop");
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 10);
        usage.put("completion_tokens", 20);

        byte[] bytes = JSON.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private StructuredOutput structured(String path) {
        LlmClient client = new OpenAiCompatibleClient(
                new ProviderConfig("mock", baseUrl + path, "k", "m", Duration.ofSeconds(10)));
        return new StructuredOutput(client);
    }

    private LlmRequest req() {
        return new LlmRequest(null, null, List.of(Msg.user("抽取出设备名和等级")), 0.0, 100,
                ResponseFormat.jsonObject());
    }

    @Test
    @DisplayName("合法 JSON → 直接反序列化成 record")
    void happyPath() {
        Device d = structured("/ok").parse(req(), Device.class);
        assertEquals("注塑机", d.name());
        assertEquals("HIGH", d.level());
    }

    @Test
    @DisplayName("带围栏和废话的输出 → 抠出来照样能解析")
    void messyOutput() {
        Device d = structured("/messy").parse(req(), Device.class);
        assertEquals("空压机", d.name());
        assertEquals("LOW", d.level());
    }

    @Test
    @DisplayName("第一次返回垃圾 → 自动重试一次后成功")
    void retryOnceThenSucceed() {
        flakyCalls.set(0);
        Device d = structured("/flaky").parse(req(), Device.class);
        assertEquals("冲床", d.name());
        assertEquals(2, flakyCalls.get(), "应该正好调用 2 次（首次 + 重试）");
    }

    @Test
    @DisplayName("一直返回垃圾 → 抛 BAD_REQUEST 且不可重试")
    void alwaysBad() {
        LlmException e = assertThrows(LlmException.class,
                () -> structured("/bad").parse(req(), Device.class));
        assertEquals(LlmException.Kind.BAD_REQUEST, e.kind());
        assertTrue(e.getMessage().contains("已重试 1 次"));
    }

    @Test
    @DisplayName("业务校验失败 → 视为解析失败，走重试后仍失败")
    void validatorRejects() {
        LlmException e = assertThrows(LlmException.class,
                () -> structured("/ok").parse(req(), Device.class, d -> false));
        assertEquals(LlmException.Kind.BAD_REQUEST, e.kind());
        assertTrue(e.getMessage().contains("业务校验未通过"));
    }

    @Test
    @DisplayName("请求体里带上了 response_format")
    void requestCarriesResponseFormat() {
        lastRequestBody = null;
        structured("/ok").parse(req(), Device.class);
        assertTrue(lastRequestBody != null && lastRequestBody.contains("\"response_format\""),
                "请求体应包含 response_format，实际: " + lastRequestBody);
        assertTrue(lastRequestBody.contains("\"json_object\""), "应该是 json_object 模式");
    }
}
