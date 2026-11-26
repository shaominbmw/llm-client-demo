package com.wuxi.ai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 切分器：把一份文档切成检索单元（chunk）。
 *
 * 切分是 RAG 里影响最大的一步。这里用「章节优先 + 长度兜底」的混合策略：
 *
 *   1. 遇到标题（第X章 / 6.3 / 附录A）→ 结束当前 chunk，更新章节路径
 *   2. 正文累积到 maxChars → 切一刀
 *   3. 同一节内的相邻 chunk 保留 overlapChars 的重叠，避免答案被切断
 *
 * 为什么不按固定字数硬切：
 *   一个故障排查步骤被切成两半，检索到上半段也答不出来。
 *   按章节切能保证「一个 chunk 是一个完整的语义单元」。
 *
 * 为什么 chunk 里要带章节标题：
 *   标题里有大量关键词（"故障排除"、"合模系统"），
 *   带上它，检索命中率能明显提升，模型也知道这段话属于哪。
 *
 * 为什么 chunk 要带图片路径：
 *   手册 83% 的页面有图，文本答不了「零件在哪」这类问题。
 *   附上图片路径，至少能告诉用户「见 P14 图 2-4」。
 */
public final class Chunker {

    private final int maxChars;
    private final int overlapChars;
    private final int minChars;

    public Chunker(int maxChars, int overlapChars, int minChars) {
        this.maxChars = Math.max(100, maxChars);
        this.overlapChars = Math.max(0, Math.min(overlapChars, this.maxChars / 2));
        this.minChars = Math.max(0, minChars);
    }

    /** 600 字一块，100 字重叠，小于 80 字的碎片并入上一块 */
    public static Chunker defaults() {
        return new Chunker(600, 100, 80);
    }

    public List<Chunk> split(SourceDocument doc) {
        return split(doc, Map.of());
    }

    public List<Chunk> split(SourceDocument doc, Map<Integer, List<String>> imagesByPage) {
        TextCleaner cleaner = new TextCleaner();
        List<Page> pages = cleaner.clean(doc.pages());

        List<Chunk> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int[] bufferPages = {0, 0};          // {startPage, endPage}
        String chapter = "";
        String section = "";
        int seq = 0;

        for (Page page : pages) {
            if (page.text() == null || page.text().isBlank()) {
                continue;
            }
            for (String rawLine : page.text().split("\\R")) {
                String line = rawLine.strip();
                if (line.isEmpty()) {
                    continue;
                }

                if (cleaner.isHeading(line)) {
                    // 标题变化 → 先把上一节结算掉
                    seq = flush(chunks, doc, buffer, bufferPages, chapter, section, seq, imagesByPage);

                    if (isChapterLevel(line)) {
                        chapter = line;
                        section = "";
                    } else {
                        section = line;
                    }
                    continue;
                }

                // 清洗后一段话可能被合并成一整行，先按上限拆开
                for (String piece : splitLongLine(line)) {
                    if (buffer.length() == 0) {
                        bufferPages[0] = page.number();
                    }
                    bufferPages[1] = page.number();

                    if (buffer.length() > 0 && buffer.length() + piece.length() + 1 > maxChars) {
                        seq = flush(chunks, doc, buffer, bufferPages, chapter, section, seq, imagesByPage);
                        bufferPages[0] = page.number();
                        bufferPages[1] = page.number();
                    }
                    buffer.append(piece).append('\n');
                }
            }
        }
        flush(chunks, doc, buffer, bufferPages, chapter, section, seq, imagesByPage);

        return List.copyOf(chunks);
    }

    /** 把 buffer 结算成一个 chunk，返回新的 seq */
    private int flush(List<Chunk> chunks, SourceDocument doc, StringBuilder buffer,
                      int[] bufferPages, String chapter, String section, int seq,
                      Map<Integer, List<String>> imagesByPage) {
        if (buffer.length() == 0) {
            return seq;
        }
        String body = buffer.toString().strip();
        String prefix = buildSectionPrefix(chapter, section);
        int startPage = bufferPages[0] == 0 ? 1 : bufferPages[0];
        int endPage = bufferPages[1] == 0 ? startPage : bufferPages[1];

        if (body.length() < minChars && !chunks.isEmpty()) {
            // 太碎的尾巴并进上一块，避免产生无意义的短块
            Chunk prev = chunks.remove(chunks.size() - 1);
            String merged = prev.text() + "\n" + body;
            List<String> mergedImages = new ArrayList<>(prev.images());
            mergedImages.addAll(imagesFor(imagesByPage, prev.startPage(), Math.max(prev.endPage(), endPage)));
            chunks.add(new Chunk(prev.id(), prev.docId(), prev.seq(), prev.section(),
                    prev.startPage(), Math.max(prev.endPage(), endPage), merged,
                    List.copyOf(dedupe(mergedImages))));
            buffer.setLength(0);
            return seq;
        }

        String text = (prefix.isEmpty() ? "" : prefix + "\n") + body;

        chunks.add(new Chunk(doc.id() + "#" + seq, doc.id(), seq,
                section.isEmpty() ? chapter : section, startPage, endPage, text,
                imagesFor(imagesByPage, startPage, endPage)));

        // 保留尾部做重叠（仅同一节内有意义）
        String tail = "";
        if (overlapChars > 0 && body.length() > overlapChars) {
            tail = body.substring(body.length() - overlapChars);
            int cut = tail.indexOf('\n');
            if (cut >= 0) {
                tail = tail.substring(cut + 1);
            }
        }
        buffer.setLength(0);
        if (!tail.isBlank()) {
            buffer.append(tail).append('\n');
        }
        return seq + 1;
    }

    /**
     * 每个 chunk 最多挂几张图。
     * 跨页 chunk（比如 P12-18）会把它覆盖的所有页的图都算进来，
     * 不加限制会挂十几张，展示时反而干扰。
     */
    private static final int MAX_IMAGES_PER_CHUNK = 8;

    private List<String> imagesFor(Map<Integer, List<String>> imagesByPage, int from, int to) {
        if (imagesByPage == null || imagesByPage.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int p = from; p <= to && out.size() < MAX_IMAGES_PER_CHUNK; p++) {
            List<String> l = imagesByPage.get(p);
            if (l != null) {
                for (String path : l) {
                    if (out.size() >= MAX_IMAGES_PER_CHUNK) {
                        break;
                    }
                    out.add(path);
                }
            }
        }
        return List.copyOf(dedupe(out));
    }

    private List<String> dedupe(List<String> list) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(list));
    }

    /**
     * 单行超过上限时，按标点切开。
     * 清洗会把被硬换行打断的句子合并成长段落，所以这里必须再兜一层，
     * 否则会出现 1000+ 字的超长 chunk。
     */
    private List<String> splitLongLine(String line) {
        if (line.length() <= maxChars) {
            return List.of(line);
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < line.length()) {
            int end = Math.min(start + maxChars, line.length());
            if (end < line.length()) {
                // 从右往左找一个标点，尽量不把句子切断
                for (int i = end; i > start + maxChars / 2; i--) {
                    char c = line.charAt(i - 1);
                    if ("。；！？，、.;!?,".indexOf(c) >= 0) {
                        end = i;
                        break;
                    }
                }
            }
            out.add(line.substring(start, end));
            start = end;
        }
        return out;
    }

    private String buildSectionPrefix(String chapter, String section) {
        if (section != null && !section.isBlank()) {
            return section;
        }
        return chapter == null ? "" : chapter;
    }

    private boolean isChapterLevel(String line) {
        return line.matches("^第\\s*[一二三四五六七八九十百零〇\\d]+\\s*[章篇].*") || line.startsWith("附录");
    }
}
