# P1｜llm-client-demo（统一多模型 ChatClient）

**状态**：已完成（第 1 层）　**技术栈**：Java 21 / Maven / Jackson / SnakeYAML / JUnit 5
**仓库**：https://github.com/shaominbmw/llm-client-demo

## 解决什么问题

调 4 家大模型，最笨的做法是写 4 个 Client、在业务里 `if (provider == "qwen")`。
本项目要证明：**同一段业务代码，切换模型只改配置。**

## 架构

```
业务代码（Demo / DemoStream）
   │  只依赖 LlmRequest / LlmResponse
   ▼
LlmClientRegistry            按名字找客户端，兜底默认
   │
   ▼
LlmClient（接口）             chat() / stream()
   │
   ▼
OpenAiCompatibleClient       唯一实现：拼 JSON、发 HTTP、解析 SSE、映射错误
   │
   ▼
DeepSeek / 通义 / 智谱 / Kimi
```

## 类职责

| 类 | 职责 |
|---|---|
| `Msg` | 一条消息（system / user / assistant） |
| `LlmRequest` | 统一请求：provider、model、messages、temperature、maxTokens |
| `LlmResponse` | 统一响应：text、usage、finishReason |
| `LlmException` | 统一异常，7 种 `Kind` + `retryable()` |
| `LlmClient` | 接口：`chat` / `stream` / `provider` / `defaultModel` |
| `ProviderConfig` | 单厂商配置（baseUrl、apiKey、model、maxTokens、timeout） |
| `OpenAiCompatibleClient` | 唯一实现，负责所有协议细节 |
| `LlmClientRegistry` | 持有 N 个客户端，按名字分发 |
| `LlmConfig` | 读 `llm.yaml`，解析 `${ENV}` 占位符，启动即校验 |

## 配置（llm.yaml）

```yaml
llm:
  default: deepseek
  providers:
    deepseek:
      base-url: https://api.deepseek.com/v1
      model: deepseek-chat
      api-key: ${DEEPSEEK_API_KEY}
      timeout-seconds: 120
```

新增厂商 = 加一段配置，Java 零改动。

## 30 秒跑起来

```powershell
# 1. 配 key（用户级环境变量，一次即可）
[Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY','sk-xxx','User')

# 2. 新开终端，跑
cd "D:\AI学习\Demo\AiTest"
mvn -q exec:java                                              # 非流式
mvn -q exec:java -Dexec.mainClass=com.wuxi.ai.llm.DemoStream  # 流式
mvn -q exec:java -Dexec.args=qwen                             # 指定厂商
```

## 测试

`mvn test` → **9 个测试全绿**，全部用本地假服务，**不花钱**。

覆盖：切换厂商 / usage 解析 / 请求体协议 / 未知 provider / 401 映射 / 403 额度 / 404 路径 / SSE 聚合 / 缺 key 早失败。

## 三个设计取舍（面试重点）

### 1. 为什么 4 家共用一个实现类？

因为它们都说 OpenAI 兼容协议，差别只有 `base-url` / `key` / `model`。
**四个类 = 四次重复，配置化才是正解。**

只有当协议不兼容时（如 Anthropic），才需要第二个实现类——此时接口不变，只是多一个实现。

### 2. 为什么错误要统一成 Kind，而不是直接抛原始异常？

因为**上层的处理动作不同**：

| Kind | 上层动作 |
|---|---|
| `AUTH` | 改配置 |
| `QUOTA_EXCEEDED` | 去充值 |
| `RATE_LIMIT` / `TIMEOUT` / `SERVER_ERROR` | 退避重试 |
| `CONTENT_FILTER` | 改提示词 |
| `BAD_REQUEST` | 检查 base-url / model |

有了 Kind，重试逻辑只写一遍，不用为每家写一套 try-catch。

### 3. 为什么 key 用 `${ENV}` 占位而不是直接写 yaml？

配置文件是要提交到 Git 的。**key 只活在环境变量里，代码库永远干净。**
这不是洁癖——是防止 `git push` 那一刻把 key 公开。

## 实测数据

| 场景 | TTFT | 总耗时 | tokens |
|---|---|---|---|
| deepseek 非流式 | — | 936 ms | 80 |
| deepseek 流式 | 612 ms | 1481 ms | 143 |
| qwen4-max 流式 | 2129 ms | 6507 ms | 251 |

## 待做

- [ ] 结构化输出（JSON Schema 约束）
- [ ] Function Calling / Tool Use
- [ ] 自动重试（目前只标了 `retryable()`，还没实现重试循环）
- [ ] 推理模型的 `reasoning_content` 流式展示
