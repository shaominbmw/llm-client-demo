package com.wuxi.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * RAG 第 1 步演示：PDF → 文本 → 清洗 → 切分。
 *
 * 运行：
 *   mvn -q compile exec:java -Dexec.mainClass=com.wuxi.ai.rag.RagIngestDemo
 * 指定文件：
 *   mvn -q compile exec:java -Dexec.mainClass=com.wuxi.ai.rag.RagIngestDemo -Dexec.args="doc/samples/03-EC3-C控制柜-电气使用维护手册.pdf"
 */
public final class RagIngestDemo {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path pdf = args.length > 0
                ? Path.of(args[0])
                : Path.of("doc/samples/01-ER50-2700-机械使用维护手册.pdf");

        System.out.println("==== RAG 第 1 步：解析 + 清洗 + 切分 ====");
        System.out.println("文件: " + pdf.toAbsolutePath());
        System.out.println();

        // ---------- 1. 解析 ----------
        String docId = pdf.getFileName().toString().replaceAll("\\.pdf$", "");
        PdfLoader loader = new PdfLoader();
        SourceDocument doc = loader.load(pdf, docId);

        System.out.printf("【解析】%d 页，原始 %d 字符%n", doc.pageCount(), doc.totalChars());

        // ---------- 2. 清洗 ----------
        TextCleaner cleaner = new TextCleaner();
        List<Page> cleaned = cleaner.clean(doc.pages());
        int cleanedChars = cleaned.stream().mapToInt(p -> p.text().length()).sum();
        var boilerplate = cleaner.detectBoilerplate(doc.pages());

        System.out.printf("【清洗】%d 字符（减少 %.1f%%），识别出 %d 条页眉页脚%n",
                cleanedChars,
                doc.totalChars() == 0 ? 0 : (1 - (double) cleanedChars / doc.totalChars()) * 100,
                boilerplate.size());
        if (!boilerplate.isEmpty()) {
            System.out.println("        页眉页脚样本: " + firstFew(boilerplate, 3));
        }

        // 诊断：区分「空白」和「真内容」的丢失
        int rawNonWs = doc.pages().stream()
                .mapToInt(p -> p.text() == null ? 0 : p.text().replaceAll("\\s", "").length()).sum();
        int cleanedNonWs = cleaned.stream()
                .mapToInt(p -> p.text().replaceAll("\\s", "").length()).sum();
        System.out.printf("        非空白字符: 原始 %d → 清洗后 %d（实际内容保留 %.1f%%）%n",
                rawNonWs, cleanedNonWs, rawNonWs == 0 ? 0 : cleanedNonWs * 100.0 / rawNonWs);

        // 找出被洗掉最多的 5 页
        List<int[]> loss = new java.util.ArrayList<>();
        for (int i = 0; i < doc.pages().size(); i++) {
            int before = doc.pages().get(i).text() == null ? 0 : doc.pages().get(i).text().replaceAll("\\s", "").length();
            int after = cleaned.get(i).text().replaceAll("\\s", "").length();
            loss.add(new int[]{doc.pages().get(i).number(), before, after});
        }
        System.out.println("        洗掉最多的 5 页：");
        loss.stream()
                .sorted((a, b) -> (b[1] - b[2]) - (a[1] - a[2]))
                .limit(5)
                .forEach(a -> System.out.printf("           P%-3d 原始 %5d 字 → 保留 %5d 字%n", a[0], a[1], a[2]));
        System.out.println();

        // ---------- 3. 抽图 ----------
        Path imageDir = Path.of("target/images");
        Map<Integer, List<String>> imagesByPage = new ImageExtractor().extract(pdf, imageDir, docId);
        int imageCount = imagesByPage.values().stream().mapToInt(List::size).sum();
        System.out.printf("【抽图】%d 张（来自 %d 页），存于 %s%n%n",
                imageCount, imagesByPage.size(), imageDir.toAbsolutePath());

        // ---------- 4. 切分 ----------
        Chunker chunker = Chunker.defaults();
        List<Chunk> chunks = chunker.split(doc, imagesByPage);

        int totalChunkChars = chunks.stream().mapToInt(Chunk::charCount).sum();
        int min = chunks.stream().mapToInt(Chunk::charCount).min().orElse(0);
        int max = chunks.stream().mapToInt(Chunk::charCount).max().orElse(0);
        double avg = chunks.isEmpty() ? 0 : (double) totalChunkChars / chunks.size();

        System.out.printf("【切分】%d 个 chunk，平均 %.0f 字（最小 %d，最大 %d）%n",
                chunks.size(), avg, min, max);
        System.out.printf("        参数字符 %d，切分后合计 %d（膨胀 %.0f%%，来自重叠与标题前缀）%n%n",
                cleanedChars, totalChunkChars,
                cleanedChars == 0 ? 0 : (totalChunkChars - cleanedChars) * 100.0 / cleanedChars);

        long withImg = chunks.stream().filter(Chunk::hasImages).count();
        int imgLinked = chunks.stream().mapToInt(c -> c.images().size()).sum();
        System.out.printf("        含图 chunk: %d / %d（图片关联 %d 次）%n%n", withImg, chunks.size(), imgLinked);

        // ---------- 4. 章节分布 ----------
        Map<String, Integer> bySection = new TreeMap<>();
        for (Chunk c : chunks) {
            String key = (c.section() == null || c.section().isBlank()) ? "(无标题)" : c.section();
            bySection.merge(key, 1, Integer::sum);
        }
        System.out.println("【章节分布】共 " + bySection.size() + " 个章节，chunk 最多的 8 个：");
        bySection.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(8)
                .forEach(e -> System.out.printf("   %-52s %d 块%n", truncate(e.getKey(), 50), e.getValue()));
        System.out.println();

        // ---------- 5. 抽样展示 ----------
        System.out.println("【样例 chunk】");
        show(chunks, 0);
        show(chunks, chunks.size() / 3);
        show(chunks, chunks.size() - 1);

        // ---------- 6. 导出 JSONL ----------
        Path outDir = Path.of("target");
        Files.createDirectories(outDir);
        Path jsonl = outDir.resolve("chunks-" + docId + ".jsonl");
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.id());
            row.put("docId", c.docId());
            row.put("seq", c.seq());
            row.put("section", c.section());
            row.put("startPage", c.startPage());
            row.put("endPage", c.endPage());
            row.put("chars", c.charCount());
            row.put("text", c.text());
            sb.append(JSON.writeValueAsString(row)).append('\n');
        }
        Files.writeString(jsonl, sb.toString(), StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("已导出: " + jsonl.toAbsolutePath() + "（" + chunks.size() + " 行）");
    }

    private static void show(List<Chunk> chunks, int index) {
        if (chunks.isEmpty()) {
            return;
        }
        Chunk c = chunks.get(Math.max(0, Math.min(index, chunks.size() - 1)));
        System.out.println("-".repeat(70));
        System.out.printf("[#%d] %s  %d 字%n", c.seq(), c.citation("文档"), c.charCount());
        System.out.println(truncate(c.text(), 260));
        if (c.hasImages()) {
            System.out.println("   附图: " + c.images().stream()
                    .map(p -> Path.of(p).getFileName().toString())
                    .toList());
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    private static String firstFew(java.util.Set<String> set, int n) {
        return set.stream().limit(n).map(s -> truncate(s, 30)).toList().toString();
    }

    private RagIngestDemo() {
    }
}
