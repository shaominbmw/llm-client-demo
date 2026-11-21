package com.wuxi.ai.llm;

/**
 * 统一的响应对象——不管哪家模型返回，业务代码拿到的都是这个。
 *
 * @param provider         实际调用的厂商
 * @param model            实际使用的模型（厂商可能返回真实版本号）
 * @param text             模型回答的正文
 * @param promptTokens     输入消耗的 token
 * @param completionTokens 输出消耗的 token
 * @param finishReason     stop / length / content_filter ...
 */
public record LlmResponse(
        String provider,
        String model,
        String text,
        int promptTokens,
        int completionTokens,
        String finishReason
) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
