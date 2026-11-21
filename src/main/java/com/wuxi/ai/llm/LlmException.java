package com.wuxi.ai.llm;

/**
 * 统一的异常模型——这是「统一客户端」最容易被忽略、但最关键的一层。
 *
 * 有了 Kind，重试逻辑只写一遍：
 *   RATE_LIMIT / SERVER_ERROR / TIMEOUT -> 可以退避重试
 *   AUTH / QUOTA_EXCEEDED / BAD_REQUEST / CONTENT_FILTER -> 重试没用，直接失败或告警
 */
public class LlmException extends RuntimeException {

    public enum Kind {
        /** key 无效、没权限 */
        AUTH,
        /** 余额不足、免费额度用尽 —— 要去充值，不是重试能解决的 */
        QUOTA_EXCEEDED,
        /** 触发限流，可重试 */
        RATE_LIMIT,
        /** 网络超时，可重试 */
        TIMEOUT,
        /** 被内容安全策略拦截，重试没用 */
        CONTENT_FILTER,
        /** 请求本身有问题（路径错、模型名错、参数不支持） */
        BAD_REQUEST,
        /** 服务端 5xx，可重试 */
        SERVER_ERROR,
        UNKNOWN
    }

    private final Kind kind;
    private final String provider;

    public LlmException(Kind kind, String provider, String message, Throwable cause) {
        super("[" + kind + "][" + provider + "] " + message, cause);
        this.kind = kind;
        this.provider = provider;
    }

    public Kind kind() {
        return kind;
    }

    public String provider() {
        return provider;
    }

    /** 上层据此决定要不要重试 */
    public boolean retryable() {
        return kind == Kind.RATE_LIMIT || kind == Kind.SERVER_ERROR || kind == Kind.TIMEOUT;
    }
}
