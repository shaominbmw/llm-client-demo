package com.wuxi.ai.llm;

/**
 * 输出格式约束。对应 OpenAI 的 response_format 参数。
 *
 * 三个层次，可靠性依次递增：
 *   1. TEXT        —— 纯文本，靠 Prompt 约束（最不可靠）
 *   2. JSON_OBJECT —— 保证是合法 JSON，但不保证字段结构
 *   3. JSON_SCHEMA —— 服务端按 schema 约束，字段和类型都对（但不是所有厂商支持）
 */
public record ResponseFormat(Mode mode, String schemaName, String jsonSchema) {

    public enum Mode {
        TEXT,
        JSON_OBJECT,
        JSON_SCHEMA
    }

    public ResponseFormat {
        if (mode == null) {
            mode = Mode.TEXT;
        }
    }

    public static ResponseFormat text() {
        return new ResponseFormat(Mode.TEXT, null, null);
    }

    public static ResponseFormat jsonObject() {
        return new ResponseFormat(Mode.JSON_OBJECT, null, null);
    }

    /**
     * @param schemaName schema 的名字，随便起，服务端用来标识
     * @param jsonSchema 一个合法的 JSON Schema 字符串
     */
    public static ResponseFormat jsonSchema(String schemaName, String jsonSchema) {
        return new ResponseFormat(Mode.JSON_SCHEMA, schemaName, jsonSchema);
    }

    public boolean isJson() {
        return mode != Mode.TEXT;
    }
}
