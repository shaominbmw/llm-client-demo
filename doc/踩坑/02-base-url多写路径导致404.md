# 踩坑 02｜base-url 多写一段路径，导致 404「not support」

**日期**：2026-11-21　**项目**：llm-client-demo　**严重度**：高（最容易踩）

## 现象

切换到 qwen 时，程序输出：

```
调用失败: [UNKNOWN][qwen] HTTP 404 ->
{"type":"error","error":{"type":"api_error","message":"not support"}}
```

但模型 `qwen4-max` 明明在账号的模型列表里，用 curl 直接打是 200 成功的。

## 原因

配置里写的是：

```yaml
base-url: https://ws-jcnyuhcot1why3to.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/responses
```

两处都错了：

1. **多了 `/responses`**。我们的客户端会在 base-url 后面自己拼 `/chat/completions`，实际请求变成：

   ```
   .../compatible-mode/v1/responses/chat/completions    ← 这个路径不存在
   ```

2. `.maas.aliyuncs.com` 是**推理服务专有端点**，跟公共 DashScope（`dashscope.aliyuncs.com`）不是一回事，模型清单也不一样。

另外，`/responses` 是 **OpenAI Responses API**，和 `/chat/completions`（Chat Completions API）是两套完全不同的接口，不能混。

**一个识别线索**：错误体是 `{"type":"error","error":{...}}` 这种**嵌套格式**，而 DashScope 标准错误是 `{"error":{"message":...}}`。格式对不上，说明请求根本没走到正常业务逻辑。

## 解决

```yaml
qwen:
  base-url: https://dashscope.aliyuncs.com/compatible-mode/v1    # 只到 /v1 为止
  model: qwen4-max
```

## 以后怎么避免

这个坑我们做了一件正事：**把它变成了代码里的保护**。

```java
if (status == 404) {
    hint = " （常见原因：base-url 多写了 /responses 或 /chat/completions，"
         + "或者 model 名在该端点上不存在）";
}
return new LlmException(kind, provider, "HTTP " + status + hint + " -> " + rawBody, null);
```

现在再出现 404，程序会**直接把最可能的原因说出来**，而不是让你自己猜。

可以再加一层：启动时校验 base-url 不能以 `/chat/completions` 或 `/responses` 结尾，直接拒绝启动。

## 一句话总结

> base-url 是「协议头」，不是「完整接口地址」。到 `/v1` 就停，剩下的交给客户端拼。
