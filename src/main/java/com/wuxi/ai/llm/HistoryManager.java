package com.wuxi.ai.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史管理：当消息太多、超出上下文预算时，裁掉最老的部分。
 *
 * 裁剪不是「随便删几条」——有两个硬约束：
 *
 *   1. system 消息必须保留（那是角色和规则，丢了模型就变傻）
 *   2. assistant(tool_calls) 和它对应的 tool 结果必须**成对保留**
 *      —— 只留 tool 结果，请求会直接非法
 *
 * 所以按「单元」裁剪：一条普通消息是一个单元，
 * 一组 [assistant(tool_calls) + 若干 tool 结果] 也是一个单元。
 */
public final class HistoryManager {

    private final int maxTokens;

    public HistoryManager(int maxTokens) {
        this.maxTokens = Math.max(200, maxTokens);
    }

    /**
     * @param messages 完整历史（含 system）
     * @return 裁剪后的历史，保证 system 在、工具调用成对
     */
    public List<Msg> trim(List<Msg> messages) {
        int total = estimateAll(messages);
        if (total <= maxTokens) {
            return List.copyOf(messages);
        }

        List<Msg> systems = new ArrayList<>();
        List<Msg> body = new ArrayList<>();
        for (Msg m : messages) {
            if ("system".equals(m.role())) {
                systems.add(m);
            } else {
                body.add(m);
            }
        }

        int budget = maxTokens - estimateAll(systems);
        List<List<Msg>> units = toUnits(body);

        List<List<Msg>> kept = new ArrayList<>();
        int used = 0;
        for (int i = units.size() - 1; i >= 0; i--) {
            int cost = estimateAll(units.get(i));
            if (used + cost > budget) {
                break;
            }
            kept.add(0, units.get(i));
            used += cost;
        }

        List<Msg> bodyKept = new ArrayList<>();
        for (List<Msg> u : kept) {
            bodyKept.addAll(u);
        }

        // 只裁业务消息开头孤立的 assistant：没有对应提问的"回答"会让模型困惑。
        // 注意不能连 system 一起裁掉。
        int start = 0;
        while (start < bodyKept.size() && "assistant".equals(bodyKept.get(start).role())) {
            start++;
        }

        List<Msg> out = new ArrayList<>(systems);
        out.addAll(bodyKept.subList(start, bodyKept.size()));
        return List.copyOf(out);
    }

    /** 把消息切成「不可分割的单元」：普通消息单独一组，工具调用和结果绑在一起 */
    private List<List<Msg>> toUnits(List<Msg> body) {
        List<List<Msg>> units = new ArrayList<>();
        List<Msg> current = new ArrayList<>();
        for (Msg m : body) {
            if (m.isToolResult()) {
                current.add(m);          // tool 结果跟随前面的 assistant(tool_calls)
            } else {
                if (!current.isEmpty()) {
                    units.add(current);
                }
                current = new ArrayList<>();
                current.add(m);
            }
        }
        if (!current.isEmpty()) {
            units.add(current);
        }
        return units;
    }

    private int estimateAll(List<Msg> msgs) {
        int sum = 0;
        for (Msg m : msgs) {
            sum += estimate(m.content()) + 8;   // 8 是 role/包装的粗略开销
            for (ToolCall tc : m.toolCalls()) {
                sum += estimate(tc.arguments()) + 8;
            }
        }
        return sum;
    }

    /**
     * 粗略估算 token 数。
     *
     * 规则很粗糙但够用：
     *   - 中日韩字符：1 字 ≈ 1 token
     *   - 其他字符：4 字符 ≈ 1 token
     *
     * 真实项目应该用 tiktoken 之类的精确分词器，但那个要额外依赖。
     * 这里的目的是「不要超出上下文窗口」，留 20% 余量就够了。
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    public int maxTokens() {
        return maxTokens;
    }
}
