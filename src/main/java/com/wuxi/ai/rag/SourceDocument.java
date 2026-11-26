package com.wuxi.ai.rag;

import java.util.List;
import java.util.Map;

/**
 * 一份已解析的文档。
 *
 * @param id       文档唯一标识（用文件名即可，后面要进向量库的元数据）
 * @param title    展示用标题
 * @param path     原始文件路径
 * @param pages    按页的原始文本（清洗前的）
 * @param metadata 额外信息：设备型号、版本号等，用于检索时过滤
 */
public record SourceDocument(
        String id,
        String title,
        String path,
        List<Page> pages,
        Map<String, String> metadata
) {

    public int pageCount() {
        return pages.size();
    }

    public int totalChars() {
        return pages.stream().mapToInt(p -> p.text() == null ? 0 : p.text().length()).sum();
    }
}
