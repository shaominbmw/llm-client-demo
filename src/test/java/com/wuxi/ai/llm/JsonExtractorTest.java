package com.wuxi.ai.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 提取的单元测试——不涉及网络，纯逻辑。
 */
class JsonExtractorTest {

    @Test
    @DisplayName("纯 JSON：原样返回")
    void plainJson() {
        assertEquals("{\"a\":1}", JsonExtractor.extract("{\"a\":1}"));
    }

    @Test
    @DisplayName("带 ```json 围栏：剥掉围栏")
    void fencedJson() {
        String raw = """
                ```json
                {"name":"注塑机","severity":"HIGH"}
                ```
                """;
        assertEquals("{\"name\":\"注塑机\",\"severity\":\"HIGH\"}", JsonExtractor.extract(raw));
    }

    @Test
    @DisplayName("带无语言标记的围栏：也能剥掉")
    void fencedWithoutLanguage() {
        String raw = """
                ```
                {"a":1}
                ```
                """;
        assertEquals("{\"a\":1}", JsonExtractor.extract(raw));
    }

    @Test
    @DisplayName("前后有废话：只截 JSON 部分")
    void withProse() {
        String raw = "好的，以下是抽取结果：{\"a\":1,\"b\":2} 希望对你有帮助。";
        assertEquals("{\"a\":1,\"b\":2}", JsonExtractor.extract(raw));
    }

    @Test
    @DisplayName("嵌套对象：截到最外层")
    void nested() {
        String raw = "结果：{\"outer\":{\"inner\":1}} 完毕";
        assertEquals("{\"outer\":{\"inner\":1}}", JsonExtractor.extract(raw));
    }

    @Test
    @DisplayName("数组也能提取")
    void array() {
        assertEquals("[1,2,3]", JsonExtractor.extract("结果数组：[1,2,3] 完"));
    }

    @Test
    @DisplayName("完全没有 JSON：抛出一眼看得懂的异常")
    void noJson() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JsonExtractor.extract("抱歉，我无法完成这个请求。"));
        assertTrue(e.getMessage().contains("没有找到 JSON"));
    }

    @Test
    @DisplayName("空响应：抛异常")
    void blank() {
        assertThrows(IllegalArgumentException.class, () -> JsonExtractor.extract("   "));
    }
}
