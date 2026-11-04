package com.travelguide.harness;

/**
 * 用于控制上下文压缩策略的上下文分类。
 */
public enum ContextKind {
    SYSTEM(true),
    PLAN_IR(true),
    EVIDENCE(true),
    BUDGET_TIME(true),
    POLICY(true),
    TOOL_RESULT(true),
    NARRATIVE(false),
    SUMMARY(false);

    private final boolean preserved;

    ContextKind(boolean preserved) {
        this.preserved = preserved;
    }

    public boolean preserved() {
        return preserved;
    }
}
