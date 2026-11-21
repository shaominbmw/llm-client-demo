# llm-client-demo

> 一套统一的 Java LLM 客户端：接入 4 家大模型厂商，业务代码零改动即可切换。

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.9-blue)](https://maven.apache.org/)
[![Tests](https://img.shields.io/badge/tests-9%20passed-brightgreen)](#测试)

---

## 为什么做这个

接入大模型最笨的写法是：每家厂商写一个 Client，业务代码里塞满 `if (provider.equals("qwen"))`。
加第 5 家的时候，你要改 6 个文件。

这个项目想验证一件事：

> **业务代码只认识 `LlmRequest` / `LlmResponse` 两个类型，切换模型真的只改配置。**

因为国内主流厂商（DeepSeek / 通义千问 / 智谱 GLM / Kimi / 豆包）都提供了 **OpenAI 兼容接口**，
一套适配器 + 一份 YAML 就能覆盖，不需要为每家写一个实现类。

---

## 特性

| 能力 | 说明 |
|---|---|
| **配置驱动切换** | 新增厂商只改 `llm.yaml`，Java 代码零改动 |
| **单一适配器** | 一个 `OpenAiCompatibleClient` 覆盖 4 家（协议兼容的前提下） |
| **流式输出（SSE）** | `Consumer<String>` 回调，实测首字延迟 612 ms |
| **统一错误模型** | 7 种 `Kind` + `retryable()` 判定，重试逻辑只写一遍 |
| **密钥不入库** | 配置里只写 `${ENV_VAR}` 占位符，API Key 永远在环境变量里 |
| **启动即校验** | 配置缺字段、`default` 指向不存在的厂商，启动时就报错并指出具体路径 |
| **可测试** | 9 个单元测试全部跑在本地假服务上，**不需要真实 API Key，不花钱** |

---

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
DeepSeek / 通义千问 / 智谱 / Kimi
```

**分层原则**：接口只暴露业务语义（`chat` / `stream`），
厂商特有的参数和差异全部下沉到配置与适配层。

---

## 快速开始

### 1. 环境要求

- JDK 21+
- Maven 3.9+

### 2. 配置 API Key

Key 通过环境变量读取，**不写进任何配置文件**。

```powershell
# Windows PowerShell（用户级，一次即可，之后新开终端生效）
[Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY','sk-xxxxxxxx','User')
```

```bash
# macOS / Linux
export DEEPSEEK_API_KEY=sk-xxxxxxxx
```

### 3. 运行

```bash
# 非流式
mvn -q compile exec:java

# 流式（打字机效果 + TTFT 统计）
mvn -q compile exec:java -Dexec.mainClass=com.wuxi.ai.llm.DemoStream

# 指定厂商（默认取自 llm.yaml 的 llm.default）
mvn -q compile exec:java -Dexec.args=qwen
```

输出示例：

```
==== 流式输出 Demo ====
已配置 provider : [deepseek, qwen, zhipu, kimi]
默认 provider   : deepseek
本次使用        : deepseek / deepseek-chat

回答：
------------------------------------------------------------
SSE 是基于 HTTP 的单向流式推送……
------------------------------------------------------------
首字延迟(TTFT) 612 ms | 总耗时 1481 ms | tokens: 入 42 + 出 101 = 143
```

---

## 配置

全部配置集中在 `src/main/resources/llm.yaml`：

```yaml
llm:
  default: deepseek          # ← 切换厂商只改这一行

  providers:
    deepseek:
      base-url: https://api.deepseek.com/v1   # 只写到 /v1，/chat/completions 由客户端拼
      model: deepseek-chat
      api-key: ${DEEPSEEK_API_KEY}
      timeout-seconds: 120
```

### 加一家新厂商

改 **1 个文件**：

```yaml
    doubao:
      base-url: https://ark.cn-beijing.volces.com/api/v3
      model: doubao-pro-32k
      max-tokens: 2048
      api-key: ${ARK_API_KEY}
      timeout-seconds: 120
```

再配一个环境变量 `ARK_API_KEY`，Java 代码 0 改动。

> `base-url` **只写到 `/v1` 为止**。多写 `/chat/completions` 或 `/responses` 会得到 404。

---

## 测试

```bash
mvn test
```

```
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

测试用一个本地 `com.sun.net.httpserver.HttpServer` 模拟 OpenAI 兼容服务（含 SSE 分片推送），
**不消耗任何 API 额度**。

覆盖场景：

- 同一段业务代码切换 provider
- `usage` 与 `finishReason` 解析
- 请求体符合 OpenAI 协议
- 未知 provider 早失败
- 401 → `AUTH`
- 403 + quota 关键字 → `QUOTA_EXCEEDED`（而不是误判为 `AUTH`）
- 404 → `BAD_REQUEST`
- SSE 分片聚合
- 缺 API Key 时在发请求前失败

---

## 三个设计取舍

### 1. 为什么 4 家共用一个实现类？

因为它们都说 OpenAI 兼容协议，差别只有 `base-url` / `api-key` / `model`。
写 4 个类 = 4 次重复，配置化才是正解。

只有当协议不兼容时（例如 Anthropic 的 Messages API），才需要第二个实现类——
此时 `LlmClient` 接口不变，只是多一个实现，业务代码依然零改动。

### 2. 为什么错误要统一成 `Kind`，而不是直接抛原始异常？

因为**上层的处理动作不同**：

| Kind | 上层应该做什么 |
|---|---|
| `AUTH` | 改配置 |
| `QUOTA_EXCEEDED` | 去充值（重试无用） |
| `RATE_LIMIT` / `TIMEOUT` / `SERVER_ERROR` | 退避重试 |
| `CONTENT_FILTER` | 改提示词 |
| `BAD_REQUEST` | 检查 base-url / model |

有了 `Kind`，重试策略写一遍就够，不用为每家厂商写一套 try-catch。

> 注意一个坑：HTTP 状态码是**传输层语义**，不是业务语义。
> 同样是 403，有的厂商表示「无权限」，阿里云 DashScope 表示「免费额度用尽」。
> 所以映射顺序必须是：**响应体关键字 → 状态码兜底 → UNKNOWN**。

### 3. 为什么 API Key 用 `${ENV}` 占位而不是直接写 YAML？

配置文件是要提交到 Git 的。
Key 只活在环境变量里，代码仓库永远干净——这不是洁癖，是防止 `git push` 那一刻把密钥公开。

---

## 目录结构

```
src/main/java/com/wuxi/ai/llm/
├── Msg.java                    一条消息
├── LlmRequest.java             统一请求
├── LlmResponse.java            统一响应
├── LlmException.java           统一异常 + 7 种 Kind
├── LlmClient.java              接口
├── ProviderConfig.java         单厂商配置
├── OpenAiCompatibleClient.java 唯一实现
├── LlmClientRegistry.java      注册表
├── LlmConfig.java              配置加载 + 启动校验
├── Demo.java                   非流式演示
└── DemoStream.java             流式演示
src/main/resources/llm.yaml     4 家厂商配置
src/test/java/.../              9 个单元测试（含本地假服务）
```

---

## 实测数据

| 场景 | 首字延迟 | 总耗时 | tokens |
|---|---|---|---|
| deepseek-chat 非流式 | — | 936 ms | 80 |
| deepseek-chat 流式 | **612 ms** | 1481 ms | 143 |
| qwen4-max 流式 | 2129 ms | 6507 ms | 251 |

> qwen4-max 是推理模型，思考过程先于正文返回，所以首字延迟明显更高。

---

## 已知限制

这个项目是**学习性工程**，不是生产 SDK，以下能力尚未实现：

- [ ] 自动重试（目前只提供了 `retryable()` 判定，没有重试循环）
- [ ] 结构化输出（JSON Schema 约束）
- [ ] Function Calling / Tool Use
- [ ] 推理模型 `reasoning_content` 的流式展示
- [ ] 连接池与并发压测
- [ ] 多轮对话上下文管理（当前无状态，每次传完整 messages）

---

## 后续计划

- **RAG**：文档解析 → 切分 → 向量检索 → 重排 → 引用溯源
- **Agent**：工具调用、多步规划、失败兜底
- **工程化**：Eval 评测集、可观测性（Langfuse）、成本统计

---

## License

暂未选择开源协议。如需使用请先联系作者。
