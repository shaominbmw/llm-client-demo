package com.wuxi.ai.rag;

import java.util.List;

/**
 * 一个检索单元。
 *
 * 这是 RAG 里最核心的数据结构——向量库里存的不是「文档」也不是「页」，而是 chunk。
 * chunk 的质量直接决定检索质量。
 *
 * @param id        唯一标识，建议 docId + "#" + seq
 * @param docId     来源文档
 * @param seq       在文档内的序号（从 0 开始）
 * @param section   所属章节路径，例如 "第 6 章 故障排除 > 6.3 合模系统"
 * @param startPage 起始页码
 * @param endPage   结束页码
 * @param text      正文
 * @param images    该 chunk 覆盖页面上导出的图片路径（可能为空）
 */
public record Chunk(
        String id,
        String docId,
        int seq,
        String section,
        int startPage,
        int endPage,
        String text,
        List<String> images
) {

    public Chunk {
        if (images == null) {
            images = List.of();
        }
    }

    /** 不带图片的便捷构造 */
    public Chunk(String id, String docId, int seq, String section,
                 int startPage, int endPage, String text) {
        this(id, docId, seq, section, startPage, endPage, text, List.of());
    }

    public int charCount() {
        return text == null ? 0 : text.length();
    }

    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }

    /** 给模型看的引用标签，例如 "[ER50-2700 维护手册 P37 §6.3]" */
    public String citation(String docTitle) {
        String pagePart = startPage == endPage ? ("P" + startPage) : ("P" + startPage + "-" + endPage);
        String secPart = (section == null || section.isBlank()) ? "" : (" §" + section);
        String imgPart = hasImages() ? (" 含图 " + images.size() + " 张") : "";
        return "[" + docTitle + " " + pagePart + secPart + imgPart + "]";
    }
}
