package com.wuxi.ai.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Function Calling 的执行循环。
 *
 * 模型不会自己执行工具——它只会说「我想调 query_device_status，参数是 {...}」。
 * 真正的执行在这里：
 *
 *   1. 发请求（带上 tools 声明）
 *   2. 如果响应里有 toolCalls → 执行 → 把结果作为 tool 消息追加
 *   3. 带着工具结果再发一次，直到模型不再调工具
 *
 * 关键防线：maxSteps。模型偶尔会陷入「调工具 → 不满意 → 再调」的循环，
 * 没有上限就是线上事故（烧钱 + 卡死）。
 */
public final class ToolLoop {

    /**
     * @param text        模型最终回答
     * @param transcript  完整对话轨迹（含工具调用），可用于展示/审计
     * @param steps       实际循环了几轮
     * @param totalTokens 累计消耗
     */
    public record Result(String text, List<Msg> transcript, int steps, int totalTokens) {
    }

    private final LlmClient client;
    private final ToolRegistry registry;
    private final int maxSteps;
    private final Consumer<String> tracer;

    public ToolLoop(LlmClient client, ToolRegistry registry) {
        this(client, registry, 5, null);
    }

    public ToolLoop(LlmClient client, ToolRegistry registry, int maxSteps, Consumer<String> tracer) {
        this.client = client;
        this.registry = registry;
        this.maxSteps = Math.max(1, maxSteps);
        this.tracer = tracer;
    }

    public Result run(LlmRequest baseRequest) {
        LlmRequest req = baseRequest.withTools(registry.definitions());
        List<Msg> msgs = new ArrayList<>(req.messages());
        int totalTokens = 0;

        for (int step = 1; step <= maxSteps; step++) {
            LlmResponse resp = client.chat(req.withMessages(msgs));
            totalTokens += resp.totalTokens();

            if (!resp.hasToolCalls()) {
                trace("第 " + step + " 轮：模型给出最终回答");
                return new Result(resp.text(), List.copyOf(msgs), step, totalTokens);
            }

            trace("第 " + step + " 轮：模型要求调用 " + resp.toolCalls().size() + " 个工具");
            msgs.add(Msg.assistantWithTools(resp.text(), resp.toolCalls()));

            for (ToolCall call : resp.toolCalls()) {
                String result = registry.execute(call);
                trace("   → " + call.name() + "(" + call.arguments() + ")");
                trace("     = " + abbreviate(result));
                msgs.add(Msg.tool(call.id(), result));
            }
        }

        throw new LlmException(LlmException.Kind.BAD_REQUEST, client.provider(),
                "工具调用超过最大步数 " + maxSteps + "，疑似死循环。最后一次请求的工具: "
                        + registry.definitions().stream().map(ToolDefinition::name).toList());
    }

    private void trace(String message) {
        if (tracer != null) {
            tracer.accept(message);
        }
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 120 ? t : t.substring(0, 120) + "...";
    }
}
