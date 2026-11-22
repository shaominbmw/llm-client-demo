package com.wuxi.ai.llm;

import java.util.List;

/**
 * 统一的请求对象——业务代码只认识它，不认识任何一家厂商。
 *
 * 采样参数说明：
 *   temperature  控制「随机性」，越高越发散。推理/创作调高，抽取/分类调低（0~0.3）
 *   topP         核采样，只从累积概率前 P 的词里选。0.9 表示忽略最不可能的 10%
 *   maxTokens    输出上限
 *   stop         遇到这些字符串就停止生成（用于截断多余内容）
 *
 * temperature 和 topP 都是「控制随机性」，一般只调一个，另一个保持默认。
 * 需要可复现结果时，两个都调到最保守（temperature=0, topP=0.1）。
 *
 * @param provider       指定用哪个厂商；null / 空 = 用配置里的默认
 * @param model          指定模型；null / 空 = 用该厂商配置里的默认模型
 * @param messages       对话历史
 * @param temperature    0~2，越低越稳定
 * @param maxTokens      最大输出长度
 * @param responseFormat 输出格式约束；null = 纯文本
 * @param tools          可用的工具声明；空 = 不启用 Function Calling
 * @param topP           核采样阈值 (0,1]，null = 用服务端默认
 * @param stop           停止序列，null / 空 = 不设置
 */
public record LlmRequest(
        String provider,
        String model,
        List<Msg> messages,
        Double temperature,
        Integer maxTokens,
        ResponseFormat responseFormat,
        List<ToolDefinition> tools,
        Double topP,
        List<String> stop
) {

    public LlmRequest {
        if (tools == null) {
            tools = List.of();
        }
        if (stop == null) {
            stop = List.of();
        }
    }

    /** 兼容不带 responseFormat / tools / topP / stop 的调用 */
    public LlmRequest(String provider, String model, List<Msg> messages,
                      Double temperature, Integer maxTokens) {
        this(provider, model, messages, temperature, maxTokens, null, List.of(), null, List.of());
    }

    public LlmRequest(String provider, String model, List<Msg> messages,
                      Double temperature, Integer maxTokens, ResponseFormat responseFormat) {
        this(provider, model, messages, temperature, maxTokens, responseFormat, List.of(), null, List.of());
    }

    public LlmRequest(String provider, String model, List<Msg> messages,
                      Double temperature, Integer maxTokens,
                      ResponseFormat responseFormat, List<ToolDefinition> tools) {
        this(provider, model, messages, temperature, maxTokens, responseFormat, tools, null, List.of());
    }

    public static LlmRequest of(String userText) {
        return new LlmRequest(null, null, List.of(Msg.user(userText)), null, null, null, List.of(), null, List.of());
    }

    public static LlmRequest of(List<Msg> messages) {
        return new LlmRequest(null, null, List.copyOf(messages), null, null, null, List.of(), null, List.of());
    }

    public LlmRequest withProvider(String p) {
        return new LlmRequest(p, model, messages, temperature, maxTokens, responseFormat, tools, topP, stop);
    }

    public LlmRequest withTemperature(double t) {
        return new LlmRequest(provider, model, messages, t, maxTokens, responseFormat, tools, topP, stop);
    }

    public LlmRequest withMaxTokens(int m) {
        return new LlmRequest(provider, model, messages, temperature, m, responseFormat, tools, topP, stop);
    }

    public LlmRequest withResponseFormat(ResponseFormat rf) {
        return new LlmRequest(provider, model, messages, temperature, maxTokens, rf, tools, topP, stop);
    }

    public LlmRequest withMessages(List<Msg> msgs) {
        return new LlmRequest(provider, model, List.copyOf(msgs), temperature, maxTokens,
                responseFormat, tools, topP, stop);
    }

    public LlmRequest withTools(List<ToolDefinition> toolDefs) {
        return new LlmRequest(provider, model, messages, temperature, maxTokens,
                responseFormat, toolDefs, topP, stop);
    }

    public LlmRequest withTopP(double p) {
        return new LlmRequest(provider, model, messages, temperature, maxTokens,
                responseFormat, tools, p, stop);
    }

    public LlmRequest withStop(List<String> stopSequences) {
        return new LlmRequest(provider, model, messages, temperature, maxTokens,
                responseFormat, tools, topP, stopSequences);
    }

    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }
}
