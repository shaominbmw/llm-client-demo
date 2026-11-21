package com.wuxi.ai.llm;

import java.time.Duration;

/**
 * 一个厂商的连接配置。全部来自 llm.yaml，代码里不写死任何地址或 key。
 *
 * @param name       厂商名，同时是 yaml 里的 key
 * @param baseUrl    到 /v1 为止，不要带 /chat/completions
 * @param apiKey     从环境变量解析而来
 * @param model      默认模型
 * @param maxTokens  该厂商的默认输出上限；推理类模型要给大一些
 * @param timeout    单次请求超时
 */
public record ProviderConfig(
        String name,
        String baseUrl,
        String apiKey,
        String model,
        Integer maxTokens,
        Duration timeout
) {

    public ProviderConfig {
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
    }

    /** 兼容不写 max-tokens 的配置 */
    public ProviderConfig(String name, String baseUrl, String apiKey, String model, Duration timeout) {
        this(name, baseUrl, apiKey, model, null, timeout);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
