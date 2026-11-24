package com.wuxi.ai.llm;

import java.util.function.Consumer;

/**
 * 统一客户端接口。业务代码只依赖这个接口。
 *
 * 注意：不要在这个接口上暴露任何厂商特有的方法（比如 setTopP、enableSearch），
 * 一旦暴露，你的「统一」就破了。
 */
public interface LlmClient {

    /** 一次性拿到完整回答 */
    LlmResponse chat(LlmRequest request);

    /**
     * 流式：只要正文。
     *
     * @param onDelta 每收到一段正文就调用一次
     */
    default LlmResponse stream(LlmRequest request, Consumer<String> onDelta) {
        return stream(request, onDelta, null);
    }

    /**
     * 流式：正文和「思考过程」分开回调。
     *
     * 推理模型（如 qwen4-max）会先输出 reasoning_content 再输出正文。
     * 如果不区分，用户在前几秒只看到空白——因为思考内容被我们丢掉了。
     *
     * @param onContent   正文片段（可为 null）
     * @param onReasoning 思考片段（可为 null；非推理模型不会触发）
     */
    LlmResponse stream(LlmRequest request, Consumer<String> onContent, Consumer<String> onReasoning);

    /** 厂商名，用于日志与排查 */
    String provider();

    /** 默认模型名，用于展示 */
    String defaultModel();
}
