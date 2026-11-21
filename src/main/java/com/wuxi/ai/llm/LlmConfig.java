package com.wuxi.ai.llm;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * 配置加载器（YAML 版）：从 classpath 的 llm.yaml 读取，支持 ${ENV_VAR} 占位符。
 *
 * 结构约定：
 * <pre>
 * llm:
 *   default: deepseek
 *   providers:
 *     deepseek:
 *       base-url: ...
 *       model: ...
 *       max-tokens: 1024      # 可选，推理模型建议给大
 *       api-key: ${DEEPSEEK_API_KEY}
 *       timeout-seconds: 120
 * </pre>
 *
 * 设计原则：配置错误在启动阶段就报出来，并且说清楚是哪一行（路径），
 * 不要等到真正调用时才抛一个看不懂的异常。
 */
public final class LlmConfig {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");
    private static final String DEFAULT_RESOURCE = "llm.yaml";

    private final Map<String, ProviderConfig> providers;
    private final String defaultProvider;

    private LlmConfig(Map<String, ProviderConfig> providers, String defaultProvider) {
        this.providers = providers;
        this.defaultProvider = defaultProvider;
    }

    public static LlmConfig load() {
        return load(DEFAULT_RESOURCE);
    }

    public static LlmConfig load(String resourceName) {
        Map<String, Object> root = readYaml(resourceName);

        Map<String, Object> llmNode = asMap(root.get("llm"), "llm");

        String defaultProvider = resolve(System.getenv().getOrDefault(
                "LLM_DEFAULT",
                asString(llmNode.get("default"), "deepseek")));

        Map<String, Object> providersNode = asMap(llmNode.get("providers"), "llm.providers");

        Map<String, ProviderConfig> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : providersNode.entrySet()) {
            String name = entry.getKey();
            String path = "llm.providers." + name;
            Map<String, Object> p = asMap(entry.getValue(), path);

            String baseUrl = resolve(asString(p.get("base-url"), ""));
            if (baseUrl.isBlank()) {
                throw new IllegalStateException(path + ".base-url 不能为空");
            }
            String apiKey = resolve(asString(p.get("api-key"), ""));
            String model = resolve(asString(p.get("model"), ""));
            long seconds = asLong(p.get("timeout-seconds"), 60);
            Integer maxTokens = asInteger(p.get("max-tokens"));

            map.put(name, new ProviderConfig(
                    name, baseUrl, apiKey, model, maxTokens, Duration.ofSeconds(seconds)));
        }

        if (map.isEmpty()) {
            throw new IllegalStateException("llm.providers 下没有任何厂商配置");
        }
        if (!map.containsKey(defaultProvider)) {
            throw new IllegalStateException(
                    "llm.default 指向了不存在的厂商: " + defaultProvider + "，已配置: " + map.keySet());
        }

        return new LlmConfig(map, defaultProvider);
    }

    private static Map<String, Object> readYaml(String resourceName) {
        try (InputStream in = LlmConfig.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("classpath 下找不到 " + resourceName);
            }
            Map<String, Object> map = new Yaml().load(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (map == null) {
                throw new IllegalStateException(resourceName + " 是空文件");
            }
            return map;
        } catch (YAMLException e) {
            throw new IllegalStateException("解析 " + resourceName + " 失败，YAML 语法有误: " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取 " + resourceName + " 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node, String path) {
        if (!(node instanceof Map<?, ?> map)) {
            throw new IllegalStateException("配置项 " + path + " 缺失，或格式不对（应该是对象/缩进块）");
        }
        return (Map<String, Object>) map;
    }

    private static String asString(Object node, String fallback) {
        return node == null ? fallback : String.valueOf(node).trim();
    }

    private static long asLong(Object node, long fallback) {
        if (node instanceof Number n) {
            return n.longValue();
        }
        if (node == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(node).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Integer asInteger(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(node).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 把 ${DEEPSEEK_API_KEY} 替换成真实环境变量；取不到就替换成空字符串 */
    private static String resolve(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = System.getenv(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(sb);
        return sb.toString().trim();
    }

    public Map<String, ProviderConfig> providers() {
        return providers;
    }

    public String defaultProvider() {
        return defaultProvider;
    }
}
