package com.wuxi.ai.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档构成分析：统计每页的图片数量与尺寸。
 *
 * 为什么要做这个：
 *   PDF 里的图片，文本提取是**拿不到**的。如果一本手册一半内容在图里，
 *   那么纯文本 RAG 的覆盖面就只有一半——这个事实必须在设计前搞清楚，
 *   而不是上线后被客户问「为什么问接线图它答不上来」。
 */
public final class DocInspector {

    public record PageStat(int pageNo, int imageCount, long totalPixels) {
    }

    public static void main(String[] args) throws Exception {
        Path pdf = args.length > 0
                ? Path.of(args[0])
                : Path.of("doc/samples/01-ER50-2700-机械使用维护手册.pdf");

        List<PageStat> stats = new ArrayList<>();
        int totalImages = 0;
        long totalPixels = 0;

        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int n = doc.getNumberOfPages();
            for (int i = 1; i <= n; i++) {
                PDPage page = doc.getPage(i - 1);
                int count = 0;
                long pixels = 0;

                PDResources res = page.getResources();
                if (res != null) {
                    for (COSName name : res.getXObjectNames()) {
                        PDXObject xo = res.getXObject(name);
                        if (xo instanceof PDImageXObject img) {
                            count++;
                            pixels += (long) img.getWidth() * img.getHeight();
                        }
                    }
                }
                stats.add(new PageStat(i, count, pixels));
                totalImages += count;
                totalPixels += pixels;
            }

            long pagesWithImage = stats.stream().filter(s -> s.imageCount() > 0).count();
            long bigImages = stats.stream().mapToLong(PageStat::imageCount).sum();

            System.out.println("==== 手册图片构成分析 ====");
            System.out.println("文件: " + pdf.getFileName());
            System.out.println("页数: " + n);
            System.out.printf("图片总数: %d（平均每页 %.1f 张）%n",
                    totalImages, n == 0 ? 0 : (double) totalImages / n);
            System.out.printf("含图页数: %d / %d（%.0f%%）%n",
                    pagesWithImage, n, n == 0 ? 0 : pagesWithImage * 100.0 / n);
            System.out.printf("图片像素总量: %.1f 百万像素%n", totalPixels / 1_000_000.0);
            System.out.println();

            System.out.println("图片最多的 8 页：");
            stats.stream()
                    .filter(s -> s.imageCount() > 0)
                    .sorted((a, b) -> b.imageCount() - a.imageCount())
                    .limit(8)
                    .forEach(s -> System.out.printf("   P%-4d %2d 张，%.2f 百万像素%n",
                            s.pageNo(), s.imageCount(), s.totalPixels() / 1_000_000.0));

            System.out.println();
            System.out.println("纯文字页（无图）：");
            String textOnly = stats.stream()
                    .filter(s -> s.imageCount() == 0)
                    .map(s -> "P" + s.pageNo())
                    .reduce((a, b) -> a + " " + b).orElse("(无)");
            System.out.println("   " + (textOnly.length() > 300 ? textOnly.substring(0, 300) + "…" : textOnly));
        }
    }

    private DocInspector() {
    }
}
