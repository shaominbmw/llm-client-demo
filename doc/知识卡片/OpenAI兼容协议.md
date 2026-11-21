# 知识卡片｜OpenAI 兼容协议

**标签**：LLM / 协议 / 多模型接入　**建立**：2026-11-21

## 一句话

大多数国产大模型都提供了「长得像 OpenAI」的 HTTP 接口，所以**一套客户端 + 一份配置**就能接多家，差别只在 `base-url`、`api-key`、`model`。

## 请求

```
POST {base-url}/chat/completions
Authorization: Bearer <api-key>
Content-Type: application/json
```

```json
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "system", "content": "你是资深架构师"},
    {"role": "user",   "content": "解释一下 RAG"}
  ],
  "temperature": 0.3,
  "max_tokens": 2048,
  "stream": false
}
```

## 响应

```json
{
  "model": "deepseek-chat",
  "choices": [{
    "message": {"role": "assistant", "content": "RAG 是……"},
    "finish_reason": "stop"
  }],
  "usage": {"prompt_tokens": 36, "completion_tokens": 44, "total_tokens": 80}
}
```

关键字段：`choices[0].message.content`、`usage.prompt_tokens`、`usage.completion_tokens`、`finish_reason`。

## 各家的 base-url（只写到 /v1）

| 厂商 | base-url | 环境变量 |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | `DEEPSEEK_API_KEY` |
| 通义千问（DashScope 兼容模式） | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `DASHSCOPE_API_KEY` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `ZHIPU_API_KEY` |
| 月之暗面 Kimi | `https://api.moonshot.cn/v1` | `MOONSHOT_API_KEY` |
| 豆包（火山方舟） | `https://ark.cn-beijing.volces.com/api/v3` | `ARK_API_KEY` |

⚠️ **base-url 只写到 `/v1` 为止**，`/chat/completions` 由客户端拼。多写一段就是 404。

## 看似统一，实则不统一的 6 个地方

| 差异点 | 表现 | 应对 |
|---|---|---|
| **错误体格式** | OpenAI `{"error":{...}}`；有的返回 `{"type":"error","error":{...}}` | 解析时都兼容，状态码分类别太依赖 body |
| **错误语义** | 403 有的表示无权限，有的表示额度用尽 | 先按关键字判，再按状态码兜底 |
| **usage 字段** | 多数是 `prompt_tokens`；Anthropic 系是 `input_tokens` | 兼容读取，缺失填 0 |
| **推理模型的正文** | qwen4-max 等把思考放 `reasoning_content`，`content` 可能为空 | 正文为空时回退读 `reasoning_content` |
| **流式下的 usage** | 默认不返回 | 请求加 `stream_options: {"include_usage": true}` |
| **参数支持度** | 有的不支持 `response_format`、`top_p` | 按能力降级，别硬传 |

## 设计结论

1. **接口只暴露业务语义**（`chat` / `stream`），不暴露厂商参数——否则「统一」就破了
2. **差异全部下沉到配置和适配层**，业务代码零感知
3. **新增厂商 = 加几行 yaml**，不动 Java
4. 真要接协议不兼容的（如 Anthropic），才需要第二个实现类，但**接口不变**

## 自测

> 加第 5 家厂商要改几个文件？
> **1 个**（`llm.yaml`）+ 1 个环境变量。
