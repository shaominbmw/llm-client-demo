package com.wuxi.ai.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 注册表：按名字找到对应的 client。
 * 业务代码永远通过这里拿 client，永远不 new 具体实现。
 */
public final class LlmClientRegistry {

    private final Map<String, LlmClient> clients;
    private final String defaultProvider;

    public LlmClientRegistry(Map<String, LlmClient> clients, String defaultProvider) {
        if (clients == null || clients.isEmpty()) {
            throw new IllegalArgumentException("至少要配置一个 provider");
        }
        // 用 unmodifiableMap 包一层，保留插入顺序（Map.copyOf 会把顺序打乱）
        this.clients = Collections.unmodifiableMap(new LinkedHashMap<>(clients));
        this.defaultProvider = (defaultProvider == null || defaultProvider.isBlank())
                ? clients.keySet().iterator().next()
                : defaultProvider;
    }

    /** 从配置直接构建——新增厂商只需要改 llm.yaml，这里一行都不用动 */
    public static LlmClientRegistry from(LlmConfig config) {
        Map<String, LlmClient> map = new LinkedHashMap<>();
        for (ProviderConfig pc : config.providers().values()) {
            map.put(pc.name(), new OpenAiCompatibleClient(pc));
        }
        return new LlmClientRegistry(map, config.defaultProvider());
    }

    public LlmClient resolve(String provider) {
        String p = (provider == null || provider.isBlank()) ? defaultProvider : provider;
        LlmClient c = clients.get(p);
        if (c == null) {
            throw new IllegalArgumentException(
                    "未知 provider: " + p + "，已配置: " + clients.keySet());
        }
        return c;
    }

    /** 顺手提供一个快捷方法，省掉 resolve 那一步 */
    public LlmResponse chat(LlmRequest req) {
        return resolve(req.provider()).chat(req);
    }

    /** 流式版本：每收到一段就回调 onDelta */
    public LlmResponse stream(LlmRequest req, Consumer<String> onDelta) {
        return resolve(req.provider()).stream(req, onDelta);
    }

    public Set<String> providers() {
        return clients.keySet();
    }

    public String defaultProvider() {
        return defaultProvider;
    }
}
