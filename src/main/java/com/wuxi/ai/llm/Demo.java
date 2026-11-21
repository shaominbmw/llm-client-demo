package com.wuxi.ai.llm;

import java.util.List;

/**
 * 演示：同一段代码，切换 provider 只改参数。
 *
 * 运行前先设置环境变量（PowerShell）：
 *   $env:DEEPSEEK_API_KEY="sk-你的key"
 * 然后运行：
 *   mvn -q compile exec:java
 * 指定厂商：
 *   mvn -q exec:java -Dexec.args="qwen"
 */
public final class Demo {

    public static void main(String[] args) {
        String wantProvider = args.length > 0 ? args[0] : null;

        LlmConfig config = LlmConfig.load();
        LlmClientRegistry registry = LlmClientRegistry.from(config);

        System.out.println("==== 统一 ChatClient Demo ====");
        System.out.println("已配置 provider : " + registry.providers());
        System.out.println("默认 provider   : " + config.defaultProvider());

        LlmClient client = registry.resolve(wantProvider);
        System.out.println("本次使用        : " + client.provider() + " / " + client.defaultModel());

        ProviderConfig pc = config.providers().get(client.provider());
        if (pc == null || !pc.hasApiKey()) {
            System.out.println();
            System.out.println("⚠ 没有找到 API Key，先设置环境变量再运行：");
            System.out.println("    $env:DEEPSEEK_API_KEY=\"sk-...\"");
            System.out.println("  然后重跑：mvn -q compile exec:java");
            System.out.println();
            System.out.println("  想不花钱先看效果？直接跑单元测试：mvn test");
            System.out.println("  （测试里有一个本地假服务，模拟真实接口）");
            return;
        }

        LlmRequest req = new LlmRequest(
                wantProvider,
                null,
                List.of(
                        Msg.system("你是一名资深 Java 架构师，回答简洁、给结论、别啰嗦。"),
                        Msg.user("用一句话解释什么是 RAG，再说它最适合解决什么问题。")
                ),
                0.3,
                // 传 null：让 provider 自己决定输出上限（推理模型在 yaml 里配了 max-tokens）
                null
        );

        System.out.println("\n--- 请求中... ---");
        long start = System.currentTimeMillis();
        try {
            LlmResponse resp = client.chat(req);
            long cost = System.currentTimeMillis() - start;

            System.out.printf("[%s] 耗时 %d ms | tokens: 入 %d + 出 %d = %d | finish=%s%n",
                    resp.provider(), cost,
                    resp.promptTokens(), resp.completionTokens(), resp.totalTokens(),
                    resp.finishReason());
            System.out.println("\n回答：\n" + resp.text());

            if ("length".equals(resp.finishReason())) {
                System.out.println();
                System.out.println("⚠ finish=length：输出被 max_tokens 截断了。");
                System.out.println("  推理类模型（如 qwen4-max）思考也占 token，"
                        + "去 llm.yaml 把该厂商的 max-tokens 调大（比如 4096）。");
            }
        } catch (LlmException e) {
            System.out.println("\n调用失败：" + e.getMessage());
            System.out.println("可重试？" + e.retryable());
            switch (e.kind()) {
                case AUTH -> System.out.println("→ 检查 API Key 是否正确、是否过期、是否有该模型权限");
                case QUOTA_EXCEEDED -> System.out.println(
                        "→ 余额不足或免费额度用尽：去控制台充值，或关闭「仅免费额度」模式，或换一个还能用的模型");
                case RATE_LIMIT -> System.out.println("→ 触发限流，等几秒重试或降低频率");
                case TIMEOUT -> System.out.println("→ 网络问题，检查代理或调大 timeout-seconds");
                case CONTENT_FILTER -> System.out.println("→ 内容被安全策略拦截，改提示词");
                case BAD_REQUEST -> System.out.println(
                        "→ 检查 base-url（是否多写了 /responses、/chat/completions）和 model 名");
                case SERVER_ERROR -> System.out.println("→ 对方服务端故障，稍后重试");
                default -> System.out.println("→ 看上面的原始错误信息");
            }
        }
    }

    private Demo() {
    }
}
