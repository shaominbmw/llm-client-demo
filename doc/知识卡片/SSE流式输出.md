# 知识卡片｜SSE 流式输出（打字机效果）

**标签**：LLM / SSE / 体验优化　**建立**：2026-11-21

## 一句话

SSE（Server-Sent Events）是**基于 HTTP 的单向推送**：服务器不关连接，持续往客户端写 `data: ...`，客户端边收边渲染——这就是「字一个个蹦出来」的原理。

## 协议格式

响应头：

```
Content-Type: text/event-stream
```

响应体（每条消息以两个换行结尾）：

```
data: {"choices":[{"delta":{"content":"你"}}]}

data: {"choices":[{"delta":{"content":"好"}}]}

data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{...}}

data: [DONE]
```

解析要点：
- **只认 `data:` 开头的行**，`event:` / `id:` / 注释行一律跳过
- `[DONE]` 是结束标记，收到就退出循环
- 每行是一个**完整的 JSON**，不要尝试拼接多行

## 为什么它比「等全部生成完」好

| 指标 | 非流式 | 流式 |
|---|---|---|
| 首字延迟 TTFT | 全部生成完（如 1481 ms） | **612 ms** |
| 用户感知 | 盯空屏，怀疑卡死 | 文字在动，知道还活着 |
| 长回答（2000 字） | 白屏 20 秒 | 0.6 秒开始出字 |

**TTFT（Time To First Token）是 AI 产品最核心的体验指标。**

## 关键坑：流式下拿不到 usage

默认情况下，流式响应里**没有 token 统计**。必须显式请求：

```json
{
  "stream": true,
  "stream_options": {"include_usage": true}
}
```

不加这一行，你就永远统计不到消耗——成本核算、限流、账单全都做不了。

## Java 实现要点

```java
HttpResponse<Stream<String>> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofLines());

try (Stream<String> lines = resp.body()) {
    for (String raw : (Iterable<String>) lines::iterator) {
        String line = raw.trim();
        if (!line.startsWith("data:")) continue;
        String data = line.substring(5).trim();
        if (data.isEmpty()) continue;
        if ("[DONE]".equals(data)) break;

        JsonNode node = JSON.readTree(data);
        String delta = node.path("choices").path(0).path("delta").path("content").asText("");
        if (!delta.isEmpty()) onDelta.accept(delta);   // 回调给 UI
    }
}
```

三个容易漏的点：

1. **要用 `try-with-resources` 关闭 Stream**，否则连接泄漏
2. **错误响应也可能不是 SSE**——状态码 ≥400 时 body 是一段 JSON，要先判断再收集
3. **回调要 `flush`**，否则控制台看不到实时效果

## 推理模型的特殊情况

`qwen4-max` 这类推理模型，流式返回的是：

```
delta.reasoning_content  ← 思考过程，先来
delta.content            ← 正文，后来
```

如果只读 `content`，用户会**白屏等好几秒**（思考那段时间）。

正确做法：收到 `reasoning_content` 时显示「思考中…」，等 `content` 来了再切换成正文。

## 和其他方案对比

| 方案 | 方向 | 复杂度 | 适用 |
|---|---|---|---|
| **SSE** | 服务端→客户端 | 低（纯 HTTP） | ✅ LLM 输出（单向） |
| WebSocket | 双向 | 中（协议升级） | 聊天室、协作编辑 |
| 轮询 | 客户端拉 | 低但浪费 | 状态查询 |

LLM 场景只需要单向推送，**SSE 足够，且比 WebSocket 简单得多**。

## 自测

> 为什么流式必须配 `stream_options.include_usage`？
> 因为默认的 stream 响应体里完全没有 `usage` 字段，不加这个参数就统计不到 token。
