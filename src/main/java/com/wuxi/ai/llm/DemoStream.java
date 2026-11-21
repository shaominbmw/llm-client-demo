package com.wuxi.ai.llm;

import java.util.List;

/**
 * 流式演示：打字机效果 + 首字延迟（TTFT）。
 *
 * 运行：
 *   mvn -q exec:java -Dexec.mainClass=com.wuxi.ai.llm.DemoStream
 * 指定厂商：
 *   mvn -q exec:java -Dexec.mainClass=com.wuxi.ai.llm.DemoStream -Dexec.args="deepseek"
 */
public final class DemoStream {

    public static void main(String[] args) {
        String wantProvider = args.length > 0 ? args[0] : null;

        LlmConfig config = LlmConfig.load();
        LlmClientRegistry registry = LlmClientRegistry.from(config);

        System.out.println("==== 流式输出 Demo ====");
        System.out.println("已配置 provider : " + registry.providers());
        System.out.println("默认 provider   : " + config.defaultProvider());

        LlmClient client = registry.resolve(wantProvider);
        System.out.println("本次使用        : " + client.provider() + " / " + client.defaultModel());

        ProviderConfig pc = config.providers().get(client.provider());
        if (pc == null || !pc.hasApiKey()) {
            System.out.println("\n⚠ 没有找到 API Key，先设置环境变量再运行。");
            return;
        }

        LlmRequest req = new LlmRequest(
                wantProvider,
                null,
                List.of(
                        Msg.system("你是一名资深 Java 架构师，回答简洁、给结论、别啰嗦。"),
                        Msg.user("用三句话解释什么是流式输出（SSE），以及它为什么能改善体验。")
                ),
                0.3,
                null
        );

        System.out.println("\n回答：");
        System.out.println("-".repeat(60));

        long begin = System.nanoTime();
        long[] firstTokenNanos = {0};

        try {
            LlmResponse resp = client.stream(req, delta -> {
                if (firstTokenNanos[0] == 0) {
                    firstTokenNanos[0] = System.nanoTime();
                }
                System.out.print(delta);
                System.out.flush();
            });

            long end = System.nanoTime();
            double ttftMs = firstTokenNanos[0] == 0 ? -1 : (firstTokenNanos[0] - begin) / 1_000_000.0;
            double totalMs = (end - begin) / 1_000_000.0;

            System.out.println();
            System.out.println("-".repeat(60));
            System.out.printf("首字延迟(TTFT) %.0f ms | 总耗时 %.0f ms | tokens: 入 %d + 出 %d = %d | finish=%s%n",
                    ttftMs, totalMs,
                    resp.promptTokens(), resp.completionTokens(), resp.totalTokens(),
                    resp.finishReason());
            System.out.println("字符数: " + resp.text().length());
        } catch (LlmException e) {
            System.out.println("\n\n调用失败：" + e.getMessage());
            System.out.println("可重试？" + e.retryable() + "  类型：" + e.kind());
        }
    }

    private DemoStream() {
    }
}
