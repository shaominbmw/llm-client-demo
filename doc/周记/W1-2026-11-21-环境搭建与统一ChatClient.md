# W1 周记｜2026-11-21｜环境搭建与统一 ChatClient

> 原计划 W1 开始于 2026-11-30，实际提前 9 天启动。

## 本期完成

### 一、开发环境（全部装到 D:\MySoftWare）

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | Temurin 21.0.13 LTS | `JAVA_HOME` + PATH 已配 |
| IntelliJ IDEA | Community 2024.3.5 | 官方免费版，替换掉原来的 2017 破解版 |
| Git | 2.56.1 | 后补，原本机器上没装 |
| VS Code | 1.147.0 | 看 Markdown + 轻量编辑 |
| Maven | 3.9.9（IDEA 自带） | 配了阿里云镜像，实测 3.3 MB/s |

### 二、统一 ChatClient（项目核心）

一句话：**业务代码只认识 `LlmRequest` / `LlmResponse`，换模型只改配置。**

```
Demo
 └─ LlmClientRegistry        按名字找客户端，兜底默认
      ├─ deepseek → OpenAiCompatibleClient
      ├─ qwen     → OpenAiCompatibleClient   ← 同一个类，不同配置
      ├─ zhipu    → OpenAiCompatibleClient
      └─ kimi     → OpenAiCompatibleClient
```

9 个类：
`Msg` / `LlmRequest` / `LlmResponse` / `LlmException` / `LlmClient` /
`ProviderConfig` / `OpenAiCompatibleClient` / `LlmClientRegistry` / `LlmConfig`

### 三、关键能力

- ✅ **配置驱动**：新增厂商只改 `llm.yaml`，Java 零改动
- ✅ **YAML 配置**：`${ENV_VAR}` 占位符，key 永不进代码库
- ✅ **SSE 流式**：`Consumer<String>` 回调，实测 TTFT 612 ms / 总 1481 ms
- ✅ **统一错误模型**：6 种 `Kind`（AUTH / QUOTA_EXCEEDED / RATE_LIMIT / TIMEOUT / CONTENT_FILTER / BAD_REQUEST / SERVER_ERROR）+ `retryable()` 判定
- ✅ **单元测试 9 个**，含一个本地假 SSE 服务，不花钱就能验证全链路

### 四、作品集

GitHub 公开仓库：`https://github.com/shaominbmw/llm-client-demo`
2 个 commit，18 个文件，1350+ 行。

## 卡点（已解决，详情见「02 踩坑记录」）

1. PowerShell 写 UTF-8 带 BOM → javac 报 `非法字符 '\ufeff'`
2. `base-url` 多写了 `/responses` → 404 `not support`
3. IDEA 在 `setx` 之前启动 → 读不到环境变量
4. `github.com` 的 DNS 解析到不通的 IP → push 超时
5. 403「免费额度用尽」被误判成「认证失败」

## 实测数据

| 场景 | 首字延迟 | 总耗时 | tokens |
|---|---|---|---|
| deepseek-chat 非流式 | — | 936 ms | 80 |
| deepseek-chat 流式 | **612 ms** | 1481 ms | 143 |
| qwen4-max 非流式 | — | 3062–5018 ms | 138–220 |

## 下周计划

1. **结构化输出**（JSON Schema 约束）——W2 核心，Agent 的地基
2. **Function Calling**——让模型自己决定调工具
3. 补 `top_p` / `stop` 参数，写 Prompt 四段式

## 一句话反思

> 这两天最大的收获不是「会调 API 了」，是**把一次故障变成了代码里的能力**：
> 那个 404 让我加了 base-url 提示，那个 403 让我拆出了 `QUOTA_EXCEEDED`。
> 这比跑通 10 个 demo 值钱。
