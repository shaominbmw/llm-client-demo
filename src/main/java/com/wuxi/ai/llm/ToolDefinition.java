package com.wuxi.ai.llm;

/**
 * 工具声明——告诉模型「我有哪些工具、每个工具接收什么参数」。
 *
 * @param name             工具名，只能用字母/数字/下划线，模型靠它来调用
 * @param description      给模型看的说明，写得好不好直接决定它会不会选对
 * @param parametersSchema 参数的 JSON Schema 字符串
 */
public record ToolDefinition(String name, String description, String parametersSchema) {
}
