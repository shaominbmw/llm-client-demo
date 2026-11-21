package com.wuxi.ai.llm;

/**
 * 一条对话消息。role 只有三种：system / user / assistant。
 * 用 record 是为了不可变——消息列表一旦构造就不该被改。
 */
public record Msg(String role, String content) {

    public static Msg system(String content) {
        return new Msg("system", content);
    }

    public static Msg user(String content) {
        return new Msg("user", content);
    }

    public static Msg assistant(String content) {
        return new Msg("assistant", content);
    }
}
