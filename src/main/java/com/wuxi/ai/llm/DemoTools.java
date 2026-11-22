package com.wuxi.ai.llm;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 演示：让模型自己决定「先查设备状态，再算超标多少度」。
 *
 * 运行：
 *   mvn -q exec:java -Dexec.mainClass=com.wuxi.ai.llm.DemoTools
 */
public final class DemoTools {

    // ==================== 工具 1：查设备状态 ====================

    /** 假数据，真实场景这里会去查数据库或调 MES 接口 */
    private static final Map<String, String> DEVICES = Map.of(
            "3号线-注塑机", "{\"deviceId\":\"3号线-注塑机\",\"status\":\"running\",\"temperature\":95,\"pressure\":12.4,\"lastMaintenance\":\"2026-09-12\"}",
            "2号线-冲床", "{\"deviceId\":\"2号线-冲床\",\"status\":\"idle\",\"temperature\":42,\"pressure\":0.0,\"lastMaintenance\":\"2026-11-02\"}"
    );

    static final Tool QUERY_DEVICE = new Tool() {
        @Override
        public String name() {
            return "query_device_status";
        }

        @Override
        public String description() {
            return "查询某台设备的实时状态，包括运行状态、温度、压力、上次保养日期。"
                    + "当用户询问设备当前情况时必须调用此工具，不要凭空猜测。";
        }

        @Override
        public String parametersSchema() {
            return """
                    {
                      "type": "object",
                      "properties": {
                        "deviceId": {
                          "type": "string",
                          "description": "设备标识，例如 3号线-注塑机"
                        }
                      },
                      "required": ["deviceId"]
                    }
                    """;
        }

        @Override
        public String execute(String argumentsJson) {
            Matcher m = Pattern.compile("\"deviceId\"\\s*:\\s*\"([^\"]+)\"").matcher(argumentsJson);
            if (!m.find()) {
                throw new IllegalArgumentException("缺少 deviceId 参数");
            }
            String id = m.group(1);
            String data = DEVICES.get(id);
            if (data == null) {
                return "{\"ok\":false,\"error\":\"未找到设备: " + id + "\"}";
            }
            return data;
        }
    };

    // ==================== 工具 2：四则运算 ====================

    static final Tool CALCULATE = new Tool() {
        private final Pattern EXPR = Pattern.compile(
                "^\\s*(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");

        @Override
        public String name() {
            return "calculate";
        }

        @Override
        public String description() {
            return "计算两个数的四则运算，例如 95-80。当需要做数值计算时必须调用此工具，不要自己算。";
        }

        @Override
        public String parametersSchema() {
            return """
                    {
                      "type": "object",
                      "properties": {
                        "expression": {
                          "type": "string",
                          "description": "形如 95-80 的表达式，只支持两个数和一个运算符"
                        }
                      },
                      "required": ["expression"]
                    }
                    """;
        }

        @Override
        public String execute(String argumentsJson) {
            Matcher outer = Pattern.compile("\"expression\"\\s*:\\s*\"([^\"]+)\"").matcher(argumentsJson);
            if (!outer.find()) {
                throw new IllegalArgumentException("缺少 expression 参数");
            }
            String expr = outer.group(1);
            Matcher m = EXPR.matcher(expr);
            if (!m.matches()) {
                throw new IllegalArgumentException("不支持的表达式: " + expr);
            }
            double a = Double.parseDouble(m.group(1));
            double b = Double.parseDouble(m.group(3));
            String op = m.group(2);
            double r = switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> {
                    if (b == 0) {
                        throw new IllegalArgumentException("除数不能为 0");
                    }
                    yield a / b;
                }
                default -> throw new IllegalArgumentException("不支持的运算符: " + op);
            };
            String formatted = (r == Math.rint(r)) ? String.valueOf((long) r) : String.valueOf(r);
            return "{\"expression\":\"" + expr + "\",\"result\":" + formatted + "}";
        }
    };

    // ==================== 主流程 ====================

    public static void main(String[] args) {
        String wantProvider = args.length > 0 ? args[0] : null;

        LlmConfig config = LlmConfig.load();
        LlmClientRegistry registry = LlmClientRegistry.from(config);
        LlmClient client = registry.resolve(wantProvider);

        System.out.println("==== Function Calling Demo ====");
        System.out.println("provider : " + client.provider() + " / " + client.defaultModel());

        ProviderConfig pc = config.providers().get(client.provider());
        if (pc == null || !pc.hasApiKey()) {
            System.out.println("\n⚠ 没有找到 API Key，先设置环境变量。");
            return;
        }

        ToolRegistry tools = new ToolRegistry(List.of(QUERY_DEVICE, CALCULATE));
        System.out.println("可用工具 : " + tools.definitions().stream().map(ToolDefinition::name).toList());

        LlmRequest req = LlmRequest.of(List.of(
                Msg.system("你是工厂设备助手。查设备状态必须调用工具，算数必须调用 calculate。不要自己猜。"),
                Msg.user("帮我查一下 3号线-注塑机 现在的状态。如果温度超过 80 度，算一下超标多少度。")
        )).withTemperature(0.1);

        ToolLoop loop = new ToolLoop(client, tools, 5, System.out::println);

        System.out.println("\n--- 开始 ---");
        long start = System.currentTimeMillis();
        try {
            ToolLoop.Result result = loop.run(req);
            long cost = System.currentTimeMillis() - start;

            System.out.println("-".repeat(60));
            System.out.println("最终回答：\n" + result.text());
            System.out.println("-".repeat(60));
            System.out.printf("轮次 %d | 累计 tokens %d | 总耗时 %d ms%n",
                    result.steps(), result.totalTokens(), cost);
        } catch (LlmException e) {
            System.out.println("\n❌ 失败: " + e.getMessage());
            System.out.println("类型: " + e.kind());
        }
    }

    private DemoTools() {
    }
}
