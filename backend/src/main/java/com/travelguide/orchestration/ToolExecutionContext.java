package com.travelguide.orchestration;

import java.util.function.Supplier;

/**
 * 保存由服务端认证结果派生的工具执行身份，禁止模型通过参数指定用户。
 */
public final class ToolExecutionContext {
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> OPERATION_KEY = new ThreadLocal<>();

    private ToolExecutionContext() {
    }

    public static <T> T runAs(String userId, Supplier<T> action) {
        String previous = USER_ID.get();
        USER_ID.set(requireText(userId));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                USER_ID.remove();
            } else {
                USER_ID.set(previous);
            }
        }
    }

    public static <T> T runAs(
            String userId,
            String operationKey,
            Supplier<T> action
    ) {
        String previousKey = OPERATION_KEY.get();
        OPERATION_KEY.set(requireText(operationKey));
        try {
            return runAs(userId, action);
        } finally {
            if (previousKey == null) {
                OPERATION_KEY.remove();
            } else {
                OPERATION_KEY.set(previousKey);
            }
        }
    }

    public static <T> T callAs(String userId, CheckedAction<T> action)
            throws Exception {
        String previous = USER_ID.get();
        USER_ID.set(requireText(userId));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                USER_ID.remove();
            } else {
                USER_ID.set(previous);
            }
        }
    }

    public static <T> T callAs(
            String userId,
            String operationKey,
            CheckedAction<T> action
    ) throws Exception {
        String previousKey = OPERATION_KEY.get();
        OPERATION_KEY.set(requireText(operationKey));
        try {
            return callAs(userId, action);
        } finally {
            if (previousKey == null) {
                OPERATION_KEY.remove();
            } else {
                OPERATION_KEY.set(previousKey);
            }
        }
    }

    public static String requireUserId() {
        String userId = USER_ID.get();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("缺少服务端可信用户身份");
        }
        return userId;
    }

    public static String currentUserId() {
        return USER_ID.get();
    }

    public static String requireOperationKey() {
        String key = OPERATION_KEY.get();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("写操作缺少幂等键");
        }
        return key;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("工具执行身份不能为空");
        }
        return value;
    }

    @FunctionalInterface
    public interface CheckedAction<T> {
        T get() throws Exception;
    }
}
