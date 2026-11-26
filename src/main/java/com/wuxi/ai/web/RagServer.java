package com.wuxi.ai.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 一个极简的 HTTP 服务，把 RAG 能力暴露成一个网页。
 *
 * 为什么用 JDK 自带的 HttpServer 而不是 Spring Boot：
 *   这个阶段的目标是把 RAG 跑通、能点开看效果，
 *   不是搭一套企业级框架。零新增依赖，启动 1 秒，
 *   以后要换 Spring Boot，只需要把路由搬过去，RagService 一行不改。
 *
 * 启动：
 *   mvn -q compile exec:java -Dexec.mainClass=com.wuxi.ai.web.RagServer
 * 然后浏览器打开 http://127.0.0.1:8080
 */
public final class RagServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        System.out.println("==== 启动 RAG 服务 ====");
        System.out.println("正在连接向量库和模型...");

        RagService service;
        try {
            service = new RagService();
        } catch (Exception e) {
            System.err.println();
            System.err.println("启动失败: " + e.getMessage());
            System.err.println();
            System.err.println("请检查：");
            System.err.println("  1. Docker 是否运行、pgvector 容器是否启动：docker ps");
            System.err.println("  2. Embedding 服务是否启动：curl http://127.0.0.1:8001/health");
            System.err.println("  3. 是否已建索引：mvn -q exec:java -Dexec.mainClass=com.wuxi.ai.rag.RagIndexDemo");
            System.exit(1);
            return;
        }

        System.out.println("模型: " + service.llm().provider() + " / " + service.llm().defaultModel());
        System.out.println("向量模型: bge-m3 (1024 维)");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        // 首页
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if ("/".equals(path) || "/index.html".equals(path)) {
                byte[] html = loadResource("/web/index.html");
                send(ex, 200, "text/html; charset=utf-8", html);
            } else {
                sendJson(ex, 404, Map.of("error", "not found"));
            }
        });

        // 问答接口
        server.createContext("/api/ask", ex -> {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, Map.of("error", "只支持 POST"));
                return;
            }
            try {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                com.fasterxml.jackson.databind.JsonNode req = JSON.readTree(body);
                String question = req.path("question").asText("");
                int topK = req.path("topK").asInt(5);

                long t0 = System.currentTimeMillis();
                AskResponse resp = service.ask(question, topK);
                long total = System.currentTimeMillis() - t0;

                System.out.printf("[%s] %s  →  %.1fs, %d 字, %d tokens%n",
                        java.time.LocalTime.now().withNano(0), abbreviate(question),
                        total / 1000.0, resp.answer().length(), resp.tokens());

                sendJson(ex, 200, resp);
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
            }
        });

        // 健康检查
        server.createContext("/api/health", ex -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "ok");
            m.put("model", service.llm().provider() + "/" + service.llm().defaultModel());
            sendJson(ex, 200, m);
        });

        server.start();
        System.out.println();
        System.out.println("✅ 服务已启动: http://127.0.0.1:" + port);
        System.out.println("   按 Ctrl+C 停止");
        System.out.println();
    }

    private static byte[] loadResource(String path) throws IOException {
        try (InputStream in = RagServer.class.getResourceAsStream(path)) {
            if (in == null) {
                return ("<h1>资源缺失: " + path + "</h1>").getBytes(StandardCharsets.UTF_8);
            }
            return in.readAllBytes();
        }
    }

    private static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static void sendJson(HttpExchange ex, int code, Object payload) throws IOException {
        byte[] body = JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        send(ex, code, "application/json; charset=utf-8", body);
    }

    private static String abbreviate(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() <= 30 ? t : t.substring(0, 30) + "…";
    }

    private RagServer() {
    }
}
