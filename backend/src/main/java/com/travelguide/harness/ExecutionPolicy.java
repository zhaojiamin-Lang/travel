package com.travelguide.harness;

/**
 * 只读调用默认允许重试。写操作只有在支持传入幂等键并保证去重时，
 * 才应显式启用重试。
 */
public record ExecutionPolicy(boolean retryEnabled) {
    public static ExecutionPolicy retryable() {
        return new ExecutionPolicy(true);
    }

    public static ExecutionPolicy once() {
        return new ExecutionPolicy(false);
    }
}
