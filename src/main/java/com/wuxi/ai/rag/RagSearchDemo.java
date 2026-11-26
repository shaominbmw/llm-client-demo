package com.wuxi.ai.rag;

import java.util.List;

/**
 * 语义检索：问题 → 向量 → 在 pgvector 里找最相似的 chunk。
 *
 * 运行：
 *   mvn -q compile exec:java -Dexec.mainClass=com.wuxi.ai.rag.RagSearchDemo
 * 自定义问题：
 *   mvn -q compile exec:java -Dexec.mainClass=com.wuxi.ai.rag.RagSearchDemo -Dexec.args="J1轴的最大动作范围是多少"
 */
public final class RagSearchDemo {

    private static final String DEFAULT_QUERY = "J1轴的最大动作范围是多少";

    public static void main(String[] args) throws Exception {
        String query = args.length > 0 ? String.join(" ", args) : DEFAULT_QUERY;

        System.out.println("==== RAG 语义检索 ====");
        System.out.println("问题: " + query);
        System.out.println();

        Embedder embedder = OpenAiCompatibleEmbedder.local();

        long t0 = System.currentTimeMillis();
        float[] queryVector = embedder.embed(query);
        long embedCost = System.currentTimeMillis() - t0;

        try (PgVectorStore store = PgVectorStore.local()) {
            long t1 = System.currentTimeMillis();
            List<PgVectorStore.Hit> hits = store.search(queryVector, 5);
            long searchCost = System.currentTimeMillis() - t1;

            System.out.printf("向量化 %d ms | 检索 %d ms | 命中 %d 条%n%n",
                    embedCost, searchCost, hits.size());

            if (hits.isEmpty()) {
                System.out.println("⚠ 没有检索到内容，可能还没建索引（先跑 RagIndexDemo）");
                return;
            }

            int rank = 0;
            for (PgVectorStore.Hit h : hits) {
                rank++;
                System.out.println("-".repeat(70));
                System.out.printf("#%d  相似度 %.4f  [P%d-%d]%n", rank, h.score(), h.startPage(), h.endPage());
                System.out.println("章节: " + (h.section() == null ? "(无)" : h.section()));
                System.out.println("来源: " + h.docId());
                if (!h.images().isEmpty()) {
                    String first = java.nio.file.Path.of(h.images().get(0)).getFileName().toString();
                    System.out.println("附图: " + h.images().size() + " 张，例如 " + first);
                }
                System.out.println("内容:");
                System.out.println("  " + truncate(h.content(), 220));
            }
            System.out.println("-".repeat(70));
        }
    }

    private static String truncate(String s, int n) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    private RagSearchDemo() {
    }
}
