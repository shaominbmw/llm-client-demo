package com.wuxi.ai.llm;

import java.util.List;

/**
 * 一条对话消息。
 *
 * role 有四种：
 *   system / user / assistant —— 和以前一样
 *   tool —— 工具执行结果，必须带 toolCallId
 *
 * assistant 消息还可能携带 toolCalls（模型说"我要调这几个工具"），
 * 此时 content 通常为空。
 */
public record Msg(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public Msg {
        if (toolCalls == null) {
            toolCalls = List.of();
        }
    }

    public Msg(String role, String content) {
        this(role, content, List.of(), null);
    }

    public static Msg system(String content) {
        return new Msg("system", content);
    }

    public static Msg user(String content) {
        return new Msg("user", content);
    }

    public static Msg assistant(String content) {
        return new Msg("assistant", content);
    }

    /** 模型要求调用工具时的 assistant 消息 */
    public static Msg assistantWithTools(String content, List<ToolCall> calls) {
        return new Msg("assistant", content == null ? "" : content, calls, null);
    }

    /** 工具执行结果 */
    public static Msg tool(String toolCallId, String content) {
        return new Msg("tool", content, List.of(), toolCallId);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean isToolResult() {
        return "tool".equals(role);
    }
}
