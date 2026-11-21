package com.wuxi.ai.llm;

/**
 * 从模型的原始输出里「抠」出 JSON。
 *
 * 为什么需要它：即使要求模型只输出 JSON，它也可能给你：
 *   - ```json ... ``` 围栏
 *   - "好的，以下是结果：{...} 希望对你有帮助"
 *   - 前后多余的空行
 *
 * 所以永远不要直接 readValue(原始文本)，先抠出来再解析。
 */
public final class JsonExtractor {

    private JsonExtractor() {
    }

    /**
     * @param raw 模型返回的原始文本
     * @return 一个从第一个 { / [ 到最后一个 } / ] 的子串
     * @throws IllegalArgumentException 找不到 JSON 结构时
     */
    public static String extract(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("响应为空");
        }
        String s = raw.trim();

        // 1. 去掉 markdown 代码围栏
        if (s.startsWith("```")) {
            int firstLineEnd = s.indexOf('\n');
            if (firstLineEnd > 0) {
                s = s.substring(firstLineEnd + 1);
            }
            int fenceEnd = s.lastIndexOf("```");
            if (fenceEnd >= 0) {
                s = s.substring(0, fenceEnd);
            }
            s = s.trim();
        }

        // 2. 截取最外层的 JSON 结构
        int start = firstOf(s, '{', '[');
        int end = lastOf(s, '}', ']');
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalArgumentException("响应中没有找到 JSON 结构: " + abbreviate(raw));
        }
        return s.substring(start, end + 1);
    }

    private static int firstOf(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) {
            return j;
        }
        if (j < 0) {
            return i;
        }
        return Math.min(i, j);
    }

    private static int lastOf(String s, char a, char b) {
        return Math.max(s.lastIndexOf(a), s.lastIndexOf(b));
    }

    private static String abbreviate(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 120 ? t : t.substring(0, 120) + "...";
    }
}
