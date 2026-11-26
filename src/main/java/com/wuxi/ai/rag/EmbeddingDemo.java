package com.wuxi.ai.rag;

import java.util.List;

/**
 * 演示：Java 调本地 embedding 服务，验证向量可用。
 *
 * 前置：先启动 D:\AI学习\Tools\embed_server.py（监听 8001）
 *
 * 运行：
 *   mvn -q compile exec:java -Dexec.mainClass=com.wuxi.ai.rag.EmbeddingDemo
 */
public final class EmbeddingDemo {

    public static void main(String[] args) {
        Embedder embedder = OpenAiCompatibleEmbedder.local();

        System.out.println("==== 本地 Embedding 验证 ====");
        System.out.println("模型: " + embedder.modelName() + "  期望维度: " + embedder.dimension());
        System.out.println();

        List<String> texts = List.of(
                "J1轴的最大动作范围是多少",
                "J1轴允许范围±180°，通过限位螺钉M12×25调整",
                "液压系统压力不足导致动作缓慢",
                "更换润滑脂的周期是多久"
        );

        long start = System.currentTimeMillis();
        try {
            List<float[]> vectors = embedder.embedBatch(texts);
            long cost = System.currentTimeMillis() - start;

            System.out.printf("向量化成功：%d 条，维度 %d，耗时 %d ms%n%n",
                    vectors.size(), vectors.get(0).length, cost);

            System.out.println("两两余弦相似度：");
            for (int i = 0; i < texts.size(); i++) {
                for (int j = i + 1; j < texts.size(); j++) {
                    System.out.printf("  %.4f   %s ↔ %s%n",
                            cosine(vectors.get(i), vectors.get(j)),
                            truncate(texts.get(i), 14),
                            truncate(texts.get(j), 14));
                }
            }
            System.out.println();
            System.out.println("判定：语义相近的那对分数应明显最高（0.7 左右），其余在 0.4-0.5");
        } catch (Exception e) {
            System.out.println("失败: " + e.getMessage());
        }
    }

    /** 服务端已做 L2 归一化，点积即余弦相似度 */
    private static double cosine(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private EmbeddingDemo() {
    }
}
