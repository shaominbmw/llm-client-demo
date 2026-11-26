package com.wuxi.ai.rag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 建索引：PDF → 解析 → 切分 → 抽图 → 向量化 → 写入 pgvector。
 *
 * 运行：
 *   mvn -q compile exec:java -Dexec.mainClass=com.wuxi.ai.rag.RagIndexDemo
 * 指定文件：
 *   mvn -q compile exec:java -Dexec.mainClass=com.wuxi.ai.rag.RagIndexDemo -Dexec.args="doc/samples/02-ER35-2700-机械使用维护手册.pdf"
 */
public final class RagIndexDemo {

    private static final String DEFAULT_PDFS = String.join(",",
            "doc/samples/01-ER50-2700-机械使用维护手册.pdf",
            "doc/samples/03-EC3-C控制柜-电气使用维护手册.pdf");

    public static void main(String[] args) throws Exception {
        List<Path> pdfs = new ArrayList<>();
        if (args.length > 0) {
            for (String a : args[0].split(",")) {
                pdfs.add(Path.of(a.trim()));
            }
        } else {
            for (String p : DEFAULT_PDFS.split(",")) {
                pdfs.add(Path.of(p));
            }
        }

        System.out.println("==== RAG 建索引 ====");
        System.out.println("文档数: " + pdfs.size());
        System.out.println();

        Embedder embedder = OpenAiCompatibleEmbedder.local();
        System.out.println("向量模型: " + embedder.modelName() + "  维度: " + embedder.dimension());
        System.out.println();

        try (PgVectorStore store = PgVectorStore.local()) {
            store.ensureSchema(embedder.dimension());
            System.out.println("表结构已就绪");
            System.out.println();

            PdfLoader loader = new PdfLoader();
            Chunker chunker = Chunker.defaults();
            ImageExtractor imageExtractor = new ImageExtractor();
            Path imageRoot = Path.of("target/images");

            int totalChunks = 0;
            long t0 = System.currentTimeMillis();

            for (Path pdf : pdfs) {
                String docId = pdf.getFileName().toString().replaceAll("\\.pdf$", "");
                System.out.println("▶ " + docId);

                SourceDocument doc = loader.load(pdf, docId);
                Map<Integer, List<String>> images = imageExtractor.extract(pdf, imageRoot, docId);
                List<Chunk> chunks = chunker.split(doc, images);

                store.upsertDocument(doc);
                int written = store.insertChunks(chunks, embedder, 16);

                System.out.printf("   页数 %d，chunk %d，图片 %d，已入库 %d%n%n",
                        doc.pageCount(), chunks.size(),
                        images.values().stream().mapToInt(List::size).sum(), written);
                totalChunks += written;
            }

            long cost = System.currentTimeMillis() - t0;
            System.out.println("=".repeat(60));
            System.out.printf("完成：入库 %d 个 chunk，总耗时 %.1f 秒%n", totalChunks, cost / 1000.0);
            System.out.println("数据库现有 chunk: " + store.count());
        }
    }

    private RagIndexDemo() {
    }
}
