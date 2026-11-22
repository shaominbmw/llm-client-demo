package com.wuxi.ai.llm;

/**
 * 模型请求调用某个工具。三个字段都来自模型的响应。
 *
 * @param id        调用 ID，回填结果时必须原样带回去，否则模型对不上号
 * @param name      工具名，必须是我们在 tools 里声明过的
 * @param arguments 参数，是一段 JSON 字符串（注意：是字符串，不是对象）
 */
public record ToolCall(String id, String name, String arguments) {
}
