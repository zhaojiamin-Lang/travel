package com.travelguide.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class ApiDtos {

    private ApiDtos() {
    }

    public record DraftRequest(
            String sessionId,
            @NotBlank(message = "请先描述你的旅行想法") String prompt
    ) {
    }

    public record NaturalLanguageUpdateRequest(
            @NotBlank(message = "请输入要调整的内容") String message
    ) {
    }

    public record SwapRequest(
            @NotBlank(message = "请选择被替换的景点") String itemId,
            @NotBlank(message = "请选择替换景点") String replacementPoiId
    ) {
    }

    public record RequirementsUpdate(
            LocalDate startDate,
            @Positive Integer days,
            @Positive Integer people,
            BigDecimal budget,
            String pace,
            Boolean noSpicy,
            List<String> mustSee
    ) {
    }

    public record PoiView(
            String id,
            String name,
            String type,
            String area,
            double latitude,
            double longitude,
            int durationMinutes,
            BigDecimal ticketPrice,
            String openTime,
            String closeTime,
            List<String> tags,
            String accent,
            String summary,
            String history,
            String sourceName,
            String sourceUrl,
            String dataConfidence
    ) {
    }

    public record DraftResponse(
            String requestId,
            String city,
            Integer days,
            LocalDate startDate,
            LocalDate endDate,
            Integer people,
            BigDecimal budget,
            String pace,
            Boolean noSpicy,
            List<String> mustSee,
            List<String> missingFields,
            String summary,
            List<PoiView> suggestedPois
    ) {
    }

    public record TransitView(
            String mode,
            String label,
            int minutes
    ) {
    }

    public record PlanItem(
            String id,
            String type,
            String poiId,
            String name,
            String shortName,
            String area,
            String startTime,
            String endTime,
            int durationMinutes,
            BigDecimal cost,
            String note,
            String sourceName,
            String sourceUrl,
            String accent,
            double latitude,
            double longitude,
            TransitView transitFromPrevious
    ) {
    }

    public record DayPlan(
            int dayNumber,
            String date,
            String label,
            String title,
            String summary,
            List<PlanItem> items
    ) {
    }

    public record PlanIssue(
            String code,
            String severity,
            String message,
            String suggestion
    ) {
    }

    public record MapPoint(
            String poiId,
            String name,
            double latitude,
            double longitude,
            int dayNumber,
            int order
    ) {
    }

    public record PlanView(
            String id,
            String requestId,
            int version,
            String title,
            String subtitle,
            String city,
            LocalDate startDate,
            int days,
            int people,
            BigDecimal budget,
            BigDecimal estimatedCost,
            String pace,
            List<String> mustSee,
            List<DayPlan> dayPlans,
            List<PlanIssue> issues,
            boolean passed,
            List<MapPoint> mapPoints,
            String generatedBy
    ) {
    }

    public record GuideCreateRequest(@NotBlank String planId) {
    }

    public record LocationRequest(double latitude, double longitude, Double accuracy) {
    }

    public record SelectPoiRequest(@NotBlank String poiId) {
    }

    public record LocationCandidate(
            PoiView poi,
            double distanceMeters,
            int confidence
    ) {
    }

    public record LocationResponse(
            String status,
            String message,
            List<LocationCandidate> candidates
    ) {
    }

    public record GuideContent(
            String poiId,
            String name,
            String area,
            String eyebrow,
            String narrative,
            String history,
            String opening,
            String ticket,
            String sourceName,
            String sourceUrl,
            String confidence,
            double latitude,
            double longitude,
            List<PoiView> nearby
    ) {
    }

    public record GuideSessionView(
            String id,
            String planId,
            String status,
            GuideContent currentPoi
    ) {
    }

    public record AskRequest(@NotBlank String question) {
    }

    public record AskResponse(String answer, List<String> followUps) {
    }

    public record BootstrapResponse(
            String city,
            String seasonLabel,
            int curatedPoiCount,
            List<PoiView> featuredPois,
            List<String> suggestedPrompts,
            String mapKey,
            String aiMode
    ) {
    }

    public record RegisterRequest(
            @NotBlank(message = "请输入邮箱") @Email(message = "邮箱格式不正确") String email,
            @NotBlank(message = "请输入密码") @Size(min = 6, max = 72, message = "密码长度需要 6–72 位") String password,
            @NotBlank(message = "请输入昵称") @Size(min = 1, max = 30, message = "昵称最多 30 个字符") String displayName
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "请输入邮箱") @Email(message = "邮箱格式不正确") String email,
            @NotBlank(message = "请输入密码") String password
    ) {
    }

    public record UserView(
            String id,
            String email,
            String displayName,
            boolean guest
    ) {
    }

    public record HistoryItem(
            String requestId,
            String latestPlanId,
            String summary,
            LocalDate startDate,
            LocalDateTime createdAt,
            int planCount
    ) {
    }
}
