package com.wuxi.ai.llm;

/**
 * 一个可被模型调用的工具。
 *
 * 实现要点：
 *   - parametersSchema 必须是合法 JSON Schema，否则请求直接被拒
 *   - execute 里抛异常不要让整个流程崩，交给 ToolLoop 捕获并作为「工具执行失败」回填给模型
 *   - 返回值建议是 JSON 字符串，模型读结构化结果比读自然语言准
 */
public interface Tool {

    String name();

    String description();

    /** 参数 JSON Schema 字符串，例如 {"type":"object","properties":{...},"required":[...]} */
    String parametersSchema();

    /**
     * 执行工具。
     *
     * @param argumentsJson 模型给出的参数，一段 JSON 字符串
     * @return 执行结果（建议 JSON），会被原样回填到对话里
     */
    String execute(String argumentsJson) throws Exception;

    /** 便捷方法：转成请求里要用的声明 */
    default ToolDefinition toDefinition() {
        return new ToolDefinition(name(), description(), parametersSchema());
    }
}
