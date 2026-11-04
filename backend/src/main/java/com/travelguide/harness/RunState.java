package com.travelguide.harness;

/**
 * 工作流状态机使用的显式状态。
 */
public enum RunState {
    CREATED,
    RESTORING,
    READY,
    COMPACTING,
    GUARDING,
    EXECUTING,
    BACKOFF,
    COMMITTING,
    CHECKPOINTED,
    SUSPENDED,
    SUCCEEDED,
    FAILED
}
