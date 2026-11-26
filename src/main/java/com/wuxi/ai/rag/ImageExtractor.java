package com.wuxi.ai.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片抽取器：把 PDF 每页的图片导出来，供「回答时附图示」使用。
 *
 * 为什么需要它：
 *   维修手册 83% 的页面有图，图里的信息（零件位置、接线方式、示意图）
 *   文本提取完全拿不到。我们没法让模型「看懂」图，
 *   但至少可以在回答时告诉用户：相关内容见 P14 图 2-4。
 *
 * 过滤策略：太小的图（图标、logo、装饰线）不导出，只留真正的示意图。
 */
public final class ImageExtractor {

    /** 小于这个像素面积的视为图标，跳过 */
    private static final long MIN_AREA = 40_000;

    /**
     * @return 页码 → 该页导出的图片文件路径列表
     */
    public Map<Integer, List<String>> extract(Path pdf, Path outDir, String docId) throws IOException {
        Path docDir = outDir.resolve(docId);
        Files.createDirectories(docDir);

        Map<Integer, List<String>> byPage = new LinkedHashMap<>();

        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int n = doc.getNumberOfPages();
            for (int i = 1; i <= n; i++) {
                PDPage page = doc.getPage(i - 1);
                PDResources res = page.getResources();
                if (res == null) {
                    continue;
                }

                List<String> paths = new ArrayList<>();
                int index = 0;
                for (COSName name : res.getXObjectNames()) {
                    PDXObject xo;
                    try {
                        xo = res.getXObject(name);
                    } catch (IOException e) {
                        continue;   // 个别图损坏，跳过这一张，不影响整页
                    }
                    if (!(xo instanceof PDImageXObject img)) {
                        continue;
                    }
                    long area = (long) img.getWidth() * img.getHeight();
                    if (area < MIN_AREA) {
                        continue;
                    }
                    try {
                        BufferedImage bi = img.getImage();
                        if (bi == null) {
                            continue;
                        }
                        index++;
                        String fileName = "p" + i + "_" + index + ".png";
                        File out = docDir.resolve(fileName).toFile();
                        ImageIO.write(bi, "png", out);
                        paths.add(out.toString());
                    } catch (Exception e) {
                        // 单张图失败不影响整体
                    }
                }
                if (!paths.isEmpty()) {
                    byPage.put(i, List.copyOf(paths));
                }
            }
        }
        return byPage;
    }
}
