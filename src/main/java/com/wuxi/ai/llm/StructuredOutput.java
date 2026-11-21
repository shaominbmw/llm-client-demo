package com.wuxi.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 结构化输出：把「模型返回一段自然语言」变成「拿到一个类型安全的对象」。
 *
 * 它做五件事，缺一不可：
 *   1. 强制 JSON 模式（response_format）
 *   2. 把 JSON Schema 也写进提示词——因为不是所有厂商都真的执行 response_format 里的 schema
 *   3. 从输出里抠出 JSON（去掉围栏和前后废话）
 *   4. 反序列化 + 业务校验
 *   5. 失败后把「具体错在哪 + 完整 schema」回灌给模型，自动重试一次
 *
 * 第 2 步是被真实故障逼出来的：
 * DeepSeek 接受 json_schema 参数，但模型仍可能输出中文枚举值（"中"）。
 * 只靠 response_format 不够，提示词里必须再强调一次。
 */
public final class StructuredOutput {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmClient client;

    public StructuredOutput(LlmClient client) {
        this.client = client;
    }

    public <T> T parse(LlmRequest request, Class<T> type) {
        return parse(request, type, null);
    }

    public <T> T parse(LlmRequest request, Class<T> type, Predicate<T> validator) {
        LlmRequest req = prepare(request);
        try {
            return attempt(req, type, validator);
        } catch (LlmException e) {
            // 厂商不支持 json_schema（返回 400）→ 降级到 json_object 再试一次
            if (e.kind() == LlmException.Kind.BAD_REQUEST && isStrictSchema(req)) {
                return attempt(req.withResponseFormat(ResponseFormat.jsonObject()), type, validator);
            }
            throw e;
        }
    }

    /** 补两样东西：JSON 模式 + schema 提示词 */
    private LlmRequest prepare(LlmRequest request) {
        LlmRequest req = request;
        ResponseFormat rf = req.responseFormat();
        if (rf == null || !rf.isJson()) {
            req = req.withResponseFormat(ResponseFormat.jsonObject());
            rf = req.responseFormat();
        }
        if (rf.mode() == ResponseFormat.Mode.JSON_SCHEMA && rf.jsonSchema() != null) {
            req = req.withMessages(prependSchemaInstruction(req.messages(), rf.jsonSchema()));
        }
        return req;
    }

    private List<Msg> prependSchemaInstruction(List<Msg> messages, String schema) {
        List<Msg> out = new ArrayList<>();
        out.add(Msg.system("""
                你是一个严格的数据抽取模块。你的输出必须是一个 JSON 对象，
                且必须完全符合下面这个 JSON Schema：

                %s

                硬性要求：
                1. 只输出 JSON 本身，不要任何解释、不要 markdown 代码块
                2. 枚举字段只能取 schema 里列出的值，必须原样使用英文大写，不要翻译
                3. 必填字段一个都不能少
                """.formatted(schema)));
        out.addAll(messages);
        return out;
    }

    private <T> T attempt(LlmRequest req, Class<T> type, Predicate<T> validator) {
        LlmResponse first = client.chat(req);
        try {
            return decode(first.text(), type, validator);
        } catch (Exception parseError) {
            // 把「你哪里不合格 + 完整 schema」告诉模型，让它自己修一次
            LlmRequest retry = withRepairMessage(req, first.text(), parseError.getMessage());
            LlmResponse second = client.chat(retry);
            try {
                return decode(second.text(), type, validator);
            } catch (Exception secondError) {
                throw new LlmException(LlmException.Kind.BAD_REQUEST, client.provider(),
                        "结构化输出解析失败（已重试 1 次）: " + secondError.getMessage()
                                + "\n最后一次输出: " + abbreviate(second.text()),
                        secondError);
            }
        }
    }

    private <T> T decode(String text, Class<T> type, Predicate<T> validator) throws Exception {
        String jsonText = JsonExtractor.extract(text);
        T value = JSON.readValue(jsonText, type);
        if (validator != null && !validator.test(value)) {
            throw new IllegalArgumentException("业务校验未通过，实际值: " + value);
        }
        return value;
    }

    private boolean isStrictSchema(LlmRequest req) {
        return req.responseFormat() != null
                && req.responseFormat().mode() == ResponseFormat.Mode.JSON_SCHEMA;
    }

    /**
     * 重试时补两条消息：
     *   assistant: 你上次的输出（原样）
     *   user:      具体哪不符合 + 完整 schema + 只输出 JSON
     * 有上下文 + 有具体错误，修正成功率远高于「重新问一遍」。
     */
    private LlmRequest withRepairMessage(LlmRequest req, String badOutput, String error) {
        String schema = req.responseFormat() != null ? req.responseFormat().jsonSchema() : null;

        StringBuilder hint = new StringBuilder();
        hint.append("你上面的输出不符合要求。具体问题：").append(error).append("\n\n");
        if (schema != null && !schema.isBlank()) {
            hint.append("必须严格符合这个 JSON Schema：\n").append(schema).append("\n\n");
        }
        hint.append("""
                请重新输出，要求：
                1. 只输出一个合法的 JSON 对象
                2. 不要任何解释文字
                3. 不要 markdown 代码块围栏
                4. 枚举值必须用 schema 里的英文原值，不要翻译成中文
                """);

        List<Msg> msgs = new ArrayList<>(req.messages());
        msgs.add(Msg.assistant(badOutput));
        msgs.add(Msg.user(hint.toString()));
        return req.withMessages(msgs);
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "...";
    }
}
