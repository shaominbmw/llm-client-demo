package com.wuxi.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Function Calling 的执行循环测试。
 * 本地假服务模拟「先要求调工具，再给出最终答案」的两轮对话。
 */
class ToolLoopTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static HttpServer server;
    private static String baseUrl;

    private static final AtomicInteger normalCalls = new AtomicInteger();
    private static final AtomicInteger loopCalls = new AtomicInteger();
    private static volatile String secondRequestBody;
    private static final AtomicInteger toolInvocations = new AtomicInteger();
    private static volatile String lastToolArgs;

    @BeforeAll
    static void startMock() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // 第一轮：要求调工具；第二轮：给最终答案
        server.createContext("/v1/toolloop/chat/completions", ex -> {
            String body = readBody(ex);
            int n = normalCalls.getAndIncrement();
            if (n == 0) {
                writeToolCall(ex, "call_1", "echo", "{\"text\":\"注塑机\"}");
            } else {
                secondRequestBody = body;
                writeText(ex, "温度 95 度，超标 15 度。");
            }
        });

        // 永远返回工具调用 → 应被 maxSteps 拦住
        server.createContext("/v1/forever/chat/completions", ex -> {
            readBody(ex);
            int n = loopCalls.getAndIncrement();
            writeToolCall(ex, "call_" + n, "echo", "{\"text\":\"again\"}");
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
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void writeText(HttpExchange ex, String content) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", "mock");
        ArrayNode choices = root.putArray("choices");
        ObjectNode c = choices.addObject();
        c.put("index", 0);
        ObjectNode m = c.putObject("message");
        m.put("role", "assistant");
        m.put("content", content);
        c.put("finish_reason", "stop");
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 10);
        usage.put("completion_tokens", 8);
        send(ex, root);
    }

    private static void writeToolCall(HttpExchange ex, String id, String name, String args) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", "mock");
        ArrayNode choices = root.putArray("choices");
        ObjectNode c = choices.addObject();
        c.put("index", 0);
        ObjectNode m = c.putObject("message");
        m.put("role", "assistant");
        m.put("content", "");
        ArrayNode calls = m.putArray("tool_calls");
        ObjectNode tc = calls.addObject();
        tc.put("id", id);
        tc.put("type", "function");
        ObjectNode fn = tc.putObject("function");
        fn.put("name", name);
        fn.put("arguments", args);
        c.put("finish_reason", "tool_calls");
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 12);
        usage.put("completion_tokens", 6);
        send(ex, root);
    }

    private static void send(HttpExchange ex, ObjectNode root) throws IOException {
        byte[] bytes = JSON.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 测试用工具：记录被调用的参数 */
    private Tool echoTool() {
        return new Tool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "原样返回传入的文本";
            }

            @Override
            public String parametersSchema() {
                return "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}";
            }

            @Override
            public String execute(String argumentsJson) {
                toolInvocations.incrementAndGet();
                lastToolArgs = argumentsJson;
                return "{\"ok\":true}";
            }
        };
    }

    private LlmClient client(String path) {
        return new OpenAiCompatibleClient(
                new ProviderConfig("mock", baseUrl + path, "k", "m", Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("先调工具再回答：两轮收敛，且第二轮带上了 tool 结果")
    void oneRoundOfToolThenAnswer() {
        normalCalls.set(0);
        toolInvocations.set(0);
        secondRequestBody = null;

        ToolRegistry registry = new ToolRegistry(List.of(echoTool()));
        ToolLoop loop = new ToolLoop(client("/toolloop"), registry, 5, null);

        ToolLoop.Result r = loop.run(LlmRequest.of("查一下注塑机温度"));

        assertEquals(2, r.steps(), "应该是两轮：调工具 + 最终回答");
        assertEquals("温度 95 度，超标 15 度。", r.text());
        assertEquals(1, toolInvocations.get(), "工具应被调用 1 次");
        assertTrue(lastToolArgs.contains("注塑机"), "工具应收到模型给的参数");

        // 第二轮请求里必须包含 assistant(tool_calls) 和 tool(tool_call_id)
        assertTrue(secondRequestBody.contains("\"tool_calls\""), "第二轮应回传 assistant 的 tool_calls");
        assertTrue(secondRequestBody.contains("\"role\":\"tool\""), "第二轮应包含 tool 角色消息");
        assertTrue(secondRequestBody.contains("tool_call_id"), "tool 消息必须带 tool_call_id");
        assertTrue(secondRequestBody.contains("\"tools\""), "请求应声明 tools");
    }

    @Test
    @DisplayName("模型一直要求调工具 → 被 maxSteps 拦住，不无限循环")
    void stopsAtMaxSteps() {
        loopCalls.set(0);
        toolInvocations.set(0);

        ToolRegistry registry = new ToolRegistry(List.of(echoTool()));
        ToolLoop loop = new ToolLoop(client("/forever"), registry, 3, null);

        LlmException e = assertThrows(LlmException.class,
                () -> loop.run(LlmRequest.of("死循环测试")));

        assertEquals(LlmException.Kind.BAD_REQUEST, e.kind());
        assertTrue(e.getMessage().contains("最大步数"));
        assertEquals(3, loopCalls.get(), "最多请求 maxSteps 次");
        assertEquals(3, toolInvocations.get(), "每轮都执行一次工具");
    }

    @Test
    @DisplayName("模型调了不存在的工具 → 工具层返回错误 JSON，循环不崩")
    void unknownToolDoesNotBreakLoop() {
        ToolRegistry registry = new ToolRegistry(List.of());

        String result = registry.execute(new ToolCall("c1", "not_exist", "{}"));

        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("未找到工具"));
    }

    @Test
    @DisplayName("工具抛异常 → 转成错误 JSON，而不是让整个请求失败")
    void toolExceptionBecomesResult() {
        Tool boom = new Tool() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public String description() {
                return "总是失败";
            }

            @Override
            public String parametersSchema() {
                return "{\"type\":\"object\",\"properties\":{}}";
            }

            @Override
            public String execute(String argumentsJson) {
                throw new IllegalStateException("数据库连不上");
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(boom));
        String result = registry.execute(new ToolCall("c2", "boom", "{}"));

        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("数据库连不上"));
    }

    @Test
    @DisplayName("tools 声明会出现在请求体里，且 schema 被解析成对象")
    void toolsAppearInRequest() {
        normalCalls.set(0);
        toolInvocations.set(0);
        secondRequestBody = null;

        ToolRegistry registry = new ToolRegistry(List.of(echoTool()));
        ToolLoop loop = new ToolLoop(client("/toolloop"), registry, 5, null);
        loop.run(LlmRequest.of("hi"));

        JsonNode node;
        try {
            node = JSON.readTree(secondRequestBody);
        } catch (Exception e) {
            throw new AssertionError("请求体不是合法 JSON", e);
        }
        JsonNode tools = node.path("tools");
        assertEquals(1, tools.size());
        assertEquals("echo", tools.path(0).path("function").path("name").asText());
        // parameters 必须是对象，不能是被转义的字符串
        assertTrue(tools.path(0).path("function").path("parameters").isObject(),
                "parameters 应该是 JSON 对象，实际: " + tools.path(0).path("function").path("parameters"));
        assertEquals("auto", node.path("tool_choice").asText());
    }

    @Test
    @DisplayName("工具注册表能按名字分发（用 Map 包裹，别被顺序影响）")
    void registryDispatch() {
        ToolRegistry registry = new ToolRegistry(List.of(echoTool()));
        assertTrue(registry.exists("echo"));
        assertEquals(1, registry.definitions().size());
        assertEquals("echo", registry.definitions().get(0).name());
        assertTrue(Map.of("a", 1).containsKey("a"));
    }
}
