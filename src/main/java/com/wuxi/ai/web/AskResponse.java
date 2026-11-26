package com.wuxi.ai.web;

import java.util.List;

/**
 * 问答接口的响应。
 *
 * @param answer     模型生成的答案
 * @param sources    引用的资料
 * @param answered   是否真的基于资料回答了（false = 资料里没有）
 * @param embedMs    向量化耗时
 * @param searchMs   检索耗时
 * @param llmMs      模型生成耗时
 * @param tokens     消耗 token
 */
public record AskResponse(
        String answer,
        List<Source> sources,
        boolean answered,
        long embedMs,
        long searchMs,
        long llmMs,
        int tokens
) {
}
