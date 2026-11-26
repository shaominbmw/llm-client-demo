package com.wuxi.ai.web;

import java.util.List;

/**
 * 一条引用来源，返回给前端展示。
 *
 * @param doc       文档名
 * @param section   章节
 * @param pages     页码，如 "12-15"
 * @param score     相似度（0~1）
 * @param snippet   原文片段
 * @param images    附图文件路径（可能为空）
 */
public record Source(
        String doc,
        String section,
        String pages,
        double score,
        String snippet,
        List<String> images
) {
}
