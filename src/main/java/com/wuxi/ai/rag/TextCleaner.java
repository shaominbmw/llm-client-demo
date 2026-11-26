package com.wuxi.ai.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文本清洗：把 PDF 提取出来的「脏文本」变成能切分的干净文本。
 *
 * PDF 提取出来的典型噪音：
 *   1. 页眉页脚（每页重复的公司名、文件编号）
 *   2. 页码（孤零零一个数字）
 *   3. 目录行（"6.3 合模系统 ........ 43"）
 *   4. 硬换行（一句话被切成两三行）
 *   5. 多余空行、控制字符
 *
 * 其中第 1 条要靠「跨页统计」发现——单看一页，你不知道哪行是页眉。
 */
public final class TextCleaner {

    /** 出现在这么多页以上，才认为是页眉页脚 */
    private static final int BOILERPLATE_MIN_PAGES = 3;
    /** 页眉页脚一般很短 */
    private static final int BOILERPLATE_MAX_LEN = 40;

    private static final Pattern PURE_NUMBER = Pattern.compile("^\\s*[-—－]?\\s*\\d{1,4}\\s*[-—－]?\\s*$");
    private static final Pattern TOC_LINE = Pattern.compile(".*[.．·…]{3,}\\s*\\d+\\s*$");
    private static final Pattern MULTI_BLANK = Pattern.compile("\\n{3,}");

    /**
     * 字体缺少 ToUnicode 映射时，PDFBox 会把字形 ID 当文本吐出来，
     * 形成 "00 00 13 80 20 26 19 14 06" 这种十六进制串。
     * 连续 4 个以上的两位十六进制 token 视为垃圾（正常参数不会这么写）。
     */
    private static final Pattern GARBAGE_HEX =
            Pattern.compile("(?:\\b[0-9a-fA-F]{2}\\b[\\s\\u3000\\u00A0]*){4,}");

    /** 句末标点：有这些结尾就不合并下一行 */
    private static final String SENTENCE_END = "。！？；：…）】」』\"'.!?;:";

    /** 标题：第X章 / 第X节 / 1.2.3 / 附录 / 一、 */
    private static final Pattern HEADING = Pattern.compile(
            "^(第\\s*[一二三四五六七八九十百零〇\\d]+\\s*[章节篇]"
                    + "|\\d+(\\.\\d+){0,3}[\\s、.]"
                    + "|[一二三四五六七八九十]+、"
                    + "|附录\\s*[A-Z一二三四五六七八九十\\d]"
                    + "|Chapter\\s+\\d+).*");

    /** 清洗整份文档 */
    public List<Page> clean(List<Page> pages) {
        Set<String> boilerplate = detectBoilerplate(pages);
        List<Page> out = new ArrayList<>(pages.size());
        for (Page p : pages) {
            out.add(new Page(p.number(), cleanPage(p.text(), boilerplate)));
        }
        return out;
    }

    /**
     * 找出跨页重复出现的短行（页眉页脚）。
     * 归一化后统计出现页数，超过阈值就拉黑。
     */
    Set<String> detectBoilerplate(List<Page> pages) {
        Map<String, Integer> pageHits = new HashMap<>();
        for (Page p : pages) {
            Set<String> seenThisPage = new HashSet<>();
            for (String line : safeLines(p.text())) {
                // 数字占比高的行（参数表、页码）不参与页眉统计。
                // 必须在归一化之前判断：归一化会把数字换成 #，之后就判断不出来了。
                int digits = line.replaceAll("[^0-9]", "").length();
                if (line.length() > 0 && digits * 2 > line.length()) {
                    continue;
                }
                String norm = normalizeForCompare(line);
                if (norm.isEmpty() || norm.length() > BOILERPLATE_MAX_LEN) {
                    continue;
                }
                if (seenThisPage.add(norm)) {
                    pageHits.merge(norm, 1, Integer::sum);
                }
            }
        }
        int threshold = Math.max(BOILERPLATE_MIN_PAGES, (int) Math.ceil(pages.size() * 0.3));
        Set<String> boilerplate = new HashSet<>();
        for (Map.Entry<String, Integer> e : pageHits.entrySet()) {
            if (e.getValue() >= threshold) {
                boilerplate.add(e.getKey());
            }
        }
        return boilerplate;
    }

    String cleanPage(String raw, Set<String> boilerplate) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String[] rawLines = raw.split("\\R");

        // ---- 目录页整页丢弃：目录对检索毫无价值，还会污染关键词 ----
        int nonEmpty = 0;
        int tocLike = 0;
        for (String l : rawLines) {
            String t = l.strip();
            if (t.isEmpty()) {
                continue;
            }
            nonEmpty++;
            if (TOC_LINE.matcher(t).matches()) {
                tocLike++;
            }
        }
        if (nonEmpty >= 5 && tocLike >= Math.max(3, (int) Math.ceil(nonEmpty * 0.3))) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        for (String l : rawLines) {
            String t = l.strip();
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }

        List<String> kept = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            // 控制字符
            l = l.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
            // 去掉字形 ID 垃圾串
            l = GARBAGE_HEX.matcher(l).replaceAll("").replaceAll("\\s{2,}", " ").strip();
            if (l.isEmpty()) {
                continue;
            }
            String norm = normalizeForCompare(l);
            if (norm.isEmpty()) {
                continue;
            }
            if (boilerplate.contains(norm)) {
                continue;
            }
            // 纯数字只当「页码」处理——而且只在页面首尾两行。
            // 表格里大量数字单元格，一律删掉会把参数表清空。
            boolean nearEdge = i < 2 || i >= lines.size() - 2;
            if (nearEdge && PURE_NUMBER.matcher(l).matches()) {
                continue;
            }
            if (TOC_LINE.matcher(l).matches()) {
                continue;
            }
            kept.add(l);
        }
        String merged = mergeBrokenLines(kept);
        // 字形 ID 垃圾常是「一行一个 16 进制 token」，合并后才连成串，
        // 所以必须在 merge 之后再过滤一次。
        merged = GARBAGE_HEX.matcher(merged).replaceAll("");
        merged = merged.replaceAll("(?m)^[ \\t\\u3000]+$", "");
        merged = MULTI_BLANK.matcher(merged).replaceAll("\n\n");
        return merged.strip();
    }

    /**
     * 合并被硬换行打断的句子。
     *
     * 规则：当前行没有句末标点、且不是标题 → 和下一行拼起来（中文不加空格）。
     */
    String mergeBrokenLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        StringBuilder buffer = new StringBuilder();

        for (String line : lines) {
            boolean heading = isHeading(line);
            if (heading) {
                flush(sb, buffer);
                sb.append(line).append('\n');
                continue;
            }
            if (buffer.length() > 0) {
                char last = buffer.charAt(buffer.length() - 1);
                if (SENTENCE_END.indexOf(last) < 0 && last != ' ') {
                    // 上一句没结束 → 拼接。中文直接连，英文补空格
                    if (needsSpace(last, line.charAt(0))) {
                        buffer.append(' ');
                    }
                    buffer.append(line);
                    continue;
                }
                flush(sb, buffer);
            }
            buffer.append(line);
        }
        flush(sb, buffer);
        return sb.toString().strip();
    }

    private void flush(StringBuilder sb, StringBuilder buffer) {
        if (buffer.length() > 0) {
            sb.append(buffer).append('\n');
            buffer.setLength(0);
        }
    }

    private boolean needsSpace(char prev, char next) {
        boolean prevAscii = prev < 128;
        boolean nextAscii = next < 128;
        return prevAscii && nextAscii && prev != ' ' && next != ' ';
    }

    public boolean isHeading(String line) {
        if (line == null) {
            return false;
        }
        String t = line.strip();
        if (t.isEmpty() || t.length() > 60) {
            return false;
        }
        return HEADING.matcher(t).matches();
    }

    private List<String> safeLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String l : text.split("\\R")) {
            String t = l.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 比较用归一化：去空白、转小写、去数字（页码会变） */
    private String normalizeForCompare(String line) {
        return line.strip()
                .replaceAll("\\s+", "")
                .replaceAll("\\d+", "#")
                .toLowerCase();
    }
}
