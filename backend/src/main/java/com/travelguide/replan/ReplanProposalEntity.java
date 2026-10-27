package com.travelguide.replan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "replan_proposal")
public class ReplanProposalEntity {

    @Id
    private String id;
    private String eventId;
    private String planId;
    private String eventType;
    private Integer baseVersion;
    private String status;
    private String summary;

    @Column(columnDefinition = "text")
    private String proposedPlanJson;

    @Column(columnDefinition = "text")
    private String changesJson;

    private Instant createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Integer getBaseVersion() {
        return baseVersion;
    }

    public void setBaseVersion(Integer baseVersion) {
        this.baseVersion = baseVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getProposedPlanJson() {
        return proposedPlanJson;
    }

    public void setProposedPlanJson(String proposedPlanJson) {
        this.proposedPlanJson = proposedPlanJson;
    }

    public String getChangesJson() {
        return changesJson;
    }

    public void setChangesJson(String changesJson) {
        this.changesJson = changesJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
