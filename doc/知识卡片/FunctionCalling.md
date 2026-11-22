# 知识卡片｜Function Calling（让模型自己调工具）

**标签**：LLM / Agent / 工具调用　**建立**：2026-11-22

## 一句话

Function Calling 让模型不只是「说话」，而是**说「我要调哪个工具、传什么参数」**，
由你的代码去执行，再把结果喂回去。这是 Agent 的地基。

**模型不会执行任何东西**——它只输出一个"调用意图"，执行永远在你的代码里。

## 一次完整循环

```
① 我发给模型：问题 + tools 声明（有哪些工具、参数长什么样）
② 模型回：      "我要调 query_device_status，参数 {"deviceId":"3号线-注塑机"}"
③ 我执行：      真的去查数据库
④ 我把结果发回：role=tool, tool_call_id=call_1, content={...}
⑤ 模型再决定：  还要不要再调？还是可以回答了？
⑥ 直到模型不再要求调用 → 拿到最终文本
```

第 ⑤ 步会和 ①-④ 循环，所以**必须有步数上限**。

## 请求长什么样

```json
{
  "model": "deepseek-chat",
  "messages": [{"role": "user", "content": "查一下 3号线-注塑机 状态"}],
  "tools": [{
    "type": "function",
    "function": {
      "name": "query_device_status",
      "description": "查询设备实时状态……",
      "parameters": {
        "type": "object",
        "properties": {"deviceId": {"type": "string"}},
        "required": ["deviceId"]
      }
    }
  }],
  "tool_choice": "auto"
}
```

## 响应长什么样（要求调工具）

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": "",
      "tool_calls": [{
        "id": "call_1",
        "type": "function",
        "function": {"name": "query_device_status", "arguments": "{\"deviceId\":\"3号线-注塑机\"}"}
      }]
    },
    "finish_reason": "tool_calls"
  }]
}
```

注意 `arguments` 是**字符串**，不是对象——要自己 `readTree` 解析。

## 回填结果长什么样

```json
[
  {"role": "assistant", "content": "", "tool_calls": [ ...原样回传... ]},
  {"role": "tool", "tool_call_id": "call_1", "content": "{\"temperature\":95}"}
]
```

**`tool_call_id` 必须原样带回**，否则模型对不上是哪个调用的结果。
`assistant` 那条也必须回传（含 tool_calls），否则协议不完整。

## 四个必须做的兜底

| 情况 | 不处理的后果 | 正确做法 |
|---|---|---|
| 调了不存在的工具 | 抛异常，对话中断 | 返回 `{"ok":false,"error":"未找到工具..."}` |
| 参数非法（缺字段/类型错） | 崩 | 返回参数错误，让模型重试 |
| 工具执行抛异常 | 崩 | 捕获，转成错误 JSON |
| 模型反复调工具 | **无限循环，烧钱** | `maxSteps`（建议 3–5） |

**核心原则：工具层永远不抛异常，一切失败都变成"结果"喂回给模型。**

模型看到"数据库连不上"，通常会自己换个说法或如实告诉用户——比直接崩溃好得多。

## 工具描述怎么写，模型才选得对

这是最容易忽视、影响最大的一环。

```
❌ "查询设备"
✅ "查询某台设备的实时状态，包括运行状态、温度、压力、上次保养日期。
    当用户询问设备当前情况时必须调用此工具，不要凭空猜测。"
```

写清楚的三个要素：
1. **做什么**（返回什么字段）
2. **什么时候用**（触发条件）
3. **什么时候不用**（避免误触发）

## 和结构化输出的关系

| | 结构化输出 | Function Calling |
|---|---|---|
| 谁在约束 | 我要求模型输出什么格式 | 模型要求我做什么事 |
| 方向 | 我 → 模型 | 模型 → 我 |
| 用途 | 抽字段、填表单 | 查数据、调接口 |

两者经常一起用：**工具返回结构化数据，模型再基于它生成回答**。

## 自测

1. 模型会自己执行工具吗？（不会，它只输出调用意图）
2. `tool_call_id` 为什么必须原样回传？
3. 工具执行失败时，应该抛异常还是返回错误 JSON？为什么？
4. 没有 `maxSteps` 会发生什么？
5. 工具描述写得好不好，影响的是什么？
