package com.wuxi.ai.llm;

import java.util.List;

/**
 * 统一的请求对象——业务代码只认识它，不认识任何一家厂商。
 *
 * @param provider    指定用哪个厂商；null / 空 = 用配置里的默认
 * @param model       指定模型；null / 空 = 用该厂商配置里的默认模型
 * @param messages    对话历史
 * @param temperature 0~2，越低越稳定
 * @param maxTokens   最大输出长度
 */
public record LlmRequest(
        String provider,
        String model,
        List<Msg> messages,
        Double temperature,
        Integer maxTokens
) {

    public static LlmRequest of(String userText) {
        return new LlmRequest(null, null, List.of(Msg.user(userText)), null, null);
    }

    public static LlmRequest of(List<Msg> messages) {
        return new LlmRequest(null, null, List.copyOf(messages), null, null);
    }

    public LlmRequest withProvider(String p) {
        return new LlmRequest(p, model, messages, temperature, maxTokens);
    }

    public LlmRequest withTemperature(double t) {
        return new LlmRequest(provider, model, messages, t, maxTokens);
    }

    public LlmRequest withMaxTokens(int m) {
        return new LlmRequest(provider, model, messages, temperature, m);
    }
}
