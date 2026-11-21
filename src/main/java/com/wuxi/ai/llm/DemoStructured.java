package com.wuxi.ai.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 演示：从一段口语化的设备故障描述里，抽取结构化数据。
 *
 * 运行：
 *   mvn -q exec:java -Dexec.mainClass=com.wuxi.ai.llm.DemoStructured
 */
public final class DemoStructured {

    /** 严重等级——注意是枚举，模型必须输出这三个值之一 */
    public enum Severity {
        LOW, MEDIUM, HIGH
    }

    /** 目标结构。@JsonIgnoreProperties 让模型多返回字段时不至于崩 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FaultReport(
            String equipment,
            String symptom,
            Severity severity,
            List<String> possibleCauses,
            boolean needShutdown
    ) {
    }

    /** JSON Schema：告诉服务端「我要什么样的对象」 */
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "equipment":       {"type": "string",  "description": "设备名称或编号"},
                "symptom":         {"type": "string",  "description": "故障现象，一句话"},
                "severity":        {"type": "string",  "enum": ["LOW","MEDIUM","HIGH"]},
                "possibleCauses":  {"type": "array",   "items": {"type": "string"}},
                "needShutdown":    {"type": "boolean", "description": "是否需要停机处理"}
              },
              "required": ["equipment","symptom","severity","possibleCauses","needShutdown"],
              "additionalProperties": false
            }
            """;

    /** 一段真实感的故障描述（口语化，带无关信息） */
    private static final String INPUT = """
            今天早上 7 点多，三号车间那台 2 号注塑机又开始闹了。
            操作工说合模的时候声音特别大，还带一点抖动，产品上有时候会有飞边。
            上周也这样，当时调了下压力就好了，这回调了没用。
            班长说先别停机，等这批订单做完再说，但我担心再这样下去模具会坏。
            """;

    public static void main(String[] args) {
        String wantProvider = args.length > 0 ? args[0] : null;

        LlmConfig config = LlmConfig.load();
        LlmClientRegistry registry = LlmClientRegistry.from(config);
        LlmClient client = registry.resolve(wantProvider);

        System.out.println("==== 结构化输出 Demo ====");
        System.out.println("provider : " + client.provider() + " / " + client.defaultModel());

        ProviderConfig pc = config.providers().get(client.provider());
        if (pc == null || !pc.hasApiKey()) {
            System.out.println("\n⚠ 没有找到 API Key，先设置环境变量。");
            return;
        }

        LlmRequest req = new LlmRequest(
                wantProvider,
                null,
                List.of(
                        Msg.system("你是工厂设备管理系统的数据抽取模块。只输出 JSON，不要解释。"),
                        Msg.user("把下面的故障描述抽取成结构化数据：\n\n" + INPUT)
                ),
                0.1,
                null,
                ResponseFormat.jsonSchema("fault_report", SCHEMA)
        );

        StructuredOutput structured = new StructuredOutput(client);

        System.out.println("\n--- 开始抽取 ---");
        long start = System.currentTimeMillis();
        try {
            // 业务校验：设备名不能空，原因至少一条
            FaultReport report = structured.parse(req, FaultReport.class,
                    r -> r.equipment() != null && !r.equipment().isBlank()
                            && r.possibleCauses() != null && !r.possibleCauses().isEmpty());

            long cost = System.currentTimeMillis() - start;
            System.out.println("✅ 抽取成功（耗时 " + cost + " ms）\n");
            System.out.println("设备名称   : " + report.equipment());
            System.out.println("故障现象   : " + report.symptom());
            System.out.println("严重等级   : " + report.severity());
            System.out.println("是否需要停机: " + (report.needShutdown() ? "是" : "否"));
            System.out.println("可能原因   :");
            report.possibleCauses().forEach(c -> System.out.println("   - " + c));
        } catch (LlmException e) {
            System.out.println("\n❌ 抽取失败: " + e.getMessage());
            System.out.println("类型: " + e.kind() + "  可重试: " + e.retryable());
        }
    }

    private DemoStructured() {
    }
}
