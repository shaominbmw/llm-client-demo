package com.wuxi.ai.rag;

import java.util.List;

/**
 * 向量化接口：把一段文本变成一个浮点数组。
 *
 * 为什么要抽象成接口：
 *   本地模型（Ollama + bge-m3）和云端 API（通义 / OpenAI embedding）实现完全不同，
 *   但上层的入库、检索代码不该关心用的是哪个。
 *   这和第 1 层「统一 ChatClient」是同一个思路。
 *
 * ⚠️ 一个致命细节：**换了 embedding 模型，向量空间就变了，旧向量全部作废。**
 *    所以向量表里必须记录 modelName 和 dimension，换模型时要重建索引。
 */
public interface Embedder {

    /** 单条文本向量化 */
    float[] embed(String text);

    /** 批量向量化（默认逐条，实现类可以覆盖成真批量） */
    default List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /** 向量维度，用于建表时声明 vector(N) */
    int dimension();

    /** 模型标识，入库时必须一起存，用于判断是否需要重建 */
    String modelName();
}
