package com.wuxi.ai.llm;

import java.util.List;

/**
 * 统一的响应对象——不管哪家模型返回，业务代码拿到的都是这个。
 *
 * @param provider         实际调用的厂商
 * @param model            实际使用的模型
 * @param text             模型回答的正文
 * @param promptTokens     输入消耗的 token
 * @param completionTokens 输出消耗的 token
 * @param finishReason     stop / length / tool_calls / content_filter
 * @param toolCalls        模型要求调用的工具（finishReason 通常是 tool_calls）
 */
public record LlmResponse(
        String provider,
        String model,
        String text,
        int promptTokens,
        int completionTokens,
        String finishReason,
        List<ToolCall> toolCalls
) {

    public LlmResponse {
        if (toolCalls == null) {
            toolCalls = List.of();
        }
    }

    /** 兼容不带 toolCalls 的构造 */
    public LlmResponse(String provider, String model, String text,
                       int promptTokens, int completionTokens, String finishReason) {
        this(provider, model, text, promptTokens, completionTokens, finishReason, List.of());
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
