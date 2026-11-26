package com.wuxi.ai.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 加载器：把一个 PDF 变成「按页的原始文本」。
 *
 * 注意这里只做「提取」，不做清洗——清洗是 TextCleaner 的事。
 * 每一步只干一件事，出问题时才好定位。
 */
public final class PdfLoader {

    /**
     * @param pdf           文件路径
     * @param docId         文档标识（建议用文件名去掉扩展名）
     * @param sortByPosition 是否按坐标排序
     *
     * 注意：中文 PDF 上这个参数影响极大。
     * 设 true 会让 PDFBox 按文字块的 X/Y 坐标重排，
     * 如果文档有页眉、水印、多栏，就会把不同层的内容交错拼在一起，
     * 出现「工业机 器人 26 6 媛 00 9」这种乱序。
     * 默认 false，保持内容流原始顺序，中文文档通常更干净。
     */
    public SourceDocument load(Path pdf, String docId, boolean sortByPosition) throws IOException {
        if (!Files.exists(pdf)) {
            throw new IOException("文件不存在: " + pdf);
        }

        List<Page> pages = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int total = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(sortByPosition);
            // 保留换行，方便后面按行清洗
            stripper.setLineSeparator("\n");

            for (int i = 1; i <= total; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(doc);
                pages.add(new Page(i, text));
            }
        }

        Path fileName = pdf.getFileName();
        String title = fileName == null ? docId : fileName.toString().replaceAll("\\.pdf$", "");

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("fileName", fileName == null ? "" : fileName.toString());
        meta.put("pageCount", String.valueOf(pages.size()));

        return new SourceDocument(docId, title, pdf.toString(), List.copyOf(pages), Map.copyOf(meta));
    }

    /** 默认不按坐标排序（对中文 PDF 更稳） */
    public SourceDocument load(Path pdf, String docId) throws IOException {
        return load(pdf, docId, false);
    }
}
