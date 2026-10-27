package com.travelguide.progress;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(
        name = "trip_execution_progress",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_progress_plan",
                columnNames = "plan_id"
        )
)
public class ExecutionProgressEntity {

    @Id
    private String id;

    private String planId;
    private String currentItemId;

    @Column(columnDefinition = "text")
    private String completedItemIdsJson;

    private String status;
    private Instant currentItemStartedAt;
    private Instant actualEndedAt;
    private Integer delayMinutes;
    private String lastEventId;
    private Instant updatedAt;

    @Version
    private Long revision;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getCurrentItemId() {
        return currentItemId;
    }

    public void setCurrentItemId(String currentItemId) {
        this.currentItemId = currentItemId;
    }

    public String getCompletedItemIdsJson() {
        return completedItemIdsJson;
    }

    public void setCompletedItemIdsJson(String completedItemIdsJson) {
        this.completedItemIdsJson = completedItemIdsJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCurrentItemStartedAt() {
        return currentItemStartedAt;
    }

    public void setCurrentItemStartedAt(Instant currentItemStartedAt) {
        this.currentItemStartedAt = currentItemStartedAt;
    }

    public Instant getActualEndedAt() {
        return actualEndedAt;
    }

    public void setActualEndedAt(Instant actualEndedAt) {
        this.actualEndedAt = actualEndedAt;
    }

    public Integer getDelayMinutes() {
        return delayMinutes;
    }

    public void setDelayMinutes(Integer delayMinutes) {
        this.delayMinutes = delayMinutes;
    }

    public String getLastEventId() {
        return lastEventId;
    }

    public void setLastEventId(String lastEventId) {
        this.lastEventId = lastEventId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getRevision() {
        return revision;
    }

    public void setRevision(Long revision) {
        this.revision = revision;
    }
}
