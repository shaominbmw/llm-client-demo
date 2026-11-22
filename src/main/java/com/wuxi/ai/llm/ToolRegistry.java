package com.wuxi.ai.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：按名字找到工具并执行。
 *
 * 两个职责：
 *   1. 给模型提供声明列表（definitions）
 *   2. 按模型给的名字分发执行（execute）
 *
 * 安全提醒：模型可能调一个不存在的工具，或者给一个非法参数。
 * 这两种情况都必须被捕获，然后作为「失败结果」回填，而不是抛异常中断对话。
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools;

    public ToolRegistry(List<Tool> list) {
        Map<String, Tool> map = new LinkedHashMap<>();
        for (Tool t : list) {
            map.put(t.name(), t);
        }
        this.tools = Map.copyOf(map);
    }

    public List<ToolDefinition> definitions() {
        List<ToolDefinition> defs = new ArrayList<>();
        for (Tool t : tools.values()) {
            defs.add(t.toDefinition());
        }
        return defs;
    }

    public boolean exists(String name) {
        return tools.containsKey(name);
    }

    /**
     * 执行一次工具调用，**永远不抛异常**——失败也返回一段 JSON，
     * 让模型看到错误并自己决定下一步（重试、换工具、或告诉用户）。
     */
    public String execute(ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return errorJson("未找到工具: " + call.name() + "，可用工具: " + tools.keySet());
        }
        try {
            String result = tool.execute(call.arguments());
            return result == null || result.isBlank() ? "{\"ok\":true}" : result;
        } catch (IllegalArgumentException e) {
            return errorJson("参数不合法: " + e.getMessage());
        } catch (Exception e) {
            return errorJson("工具执行失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private String errorJson(String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"ok\":false,\"error\":\"" + escaped + "\"}";
    }
}
