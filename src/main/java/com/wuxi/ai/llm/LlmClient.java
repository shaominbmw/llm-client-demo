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
     * 流式拿到回答：模型每吐一个片段，就回调一次 onDelta。
     * 返回值和 chat() 一样，是聚合后的完整结果（含 token 统计）。
     *
     * @param onDelta 每收到一段文本就调用一次，UI 层用它做打字机效果
     */
    LlmResponse stream(LlmRequest request, Consumer<String> onDelta);

    /** 厂商名，用于日志与排查 */
    String provider();

    /** 默认模型名，用于展示 */
    String defaultModel();
}
