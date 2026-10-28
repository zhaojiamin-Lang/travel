package com.travelguide.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.ai.AiGateway;
import com.travelguide.domain.PoiEntity;
import com.travelguide.domain.TripPlanEntity;
import com.travelguide.domain.TripRequestEntity;
import com.travelguide.repo.PoiRepository;
import com.travelguide.repo.TripPlanRepository;
import com.travelguide.repo.TripRequestRepository;
import com.travelguide.web.ApiDtos.DayPlan;
import com.travelguide.web.ApiDtos.DraftRequest;
import com.travelguide.web.ApiDtos.DraftResponse;
import com.travelguide.web.ApiDtos.MapPoint;
import com.travelguide.web.ApiDtos.HistoryItem;
import com.travelguide.web.ApiDtos.NaturalLanguageUpdateRequest;
import com.travelguide.web.ApiDtos.PlanIssue;
import com.travelguide.web.ApiDtos.PlanItem;
import com.travelguide.web.ApiDtos.PlanView;
import com.travelguide.web.ApiDtos.PoiView;
import com.travelguide.web.ApiDtos.RequirementsUpdate;
import com.travelguide.web.ApiDtos.SwapRequest;
import com.travelguide.web.ApiDtos.TransitView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行程规划核心服务。
 *
 * <p>负责从「一句话需求」到「可用行程」的全过程：写需求、补槽位、选景点、
 * 排时间轴、算预算、做校验、存版本，以及后续的改需求/换点/回滚/查历史。
 * 大模型只用来补字段和读自然语言，选点与排程的判定仍走本地规则，结果可复现。</p>
 */
@Service
public class PlanningService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final PoiRepository poiRepository;
    private final TripRequestRepository requestRepository;
    private final TripPlanRepository planRepository;
    private final PoiMapper poiMapper;
    private final ObjectMapper objectMapper;
    private final AiGateway aiGateway;
    private final AmapRouteService routeService;
    private final String defaultCity;

    public PlanningService(
            PoiRepository poiRepository,
            TripRequestRepository requestRepository,
            TripPlanRepository planRepository,
            PoiMapper poiMapper,
            ObjectMapper objectMapper,
            AiGateway aiGateway,
            AmapRouteService routeService,
            @Value("${travel.default-city:上海}") String defaultCity
    ) {
        this.poiRepository = poiRepository;
        this.requestRepository = requestRepository;
        this.planRepository = planRepository;
        this.poiMapper = poiMapper;
        this.objectMapper = objectMapper;
        this.aiGateway = aiGateway;
        this.routeService = routeService;
        this.defaultCity = defaultCity;
    }

    @Transactional
    public DraftResponse createDraft(DraftRequest request) {
        return createDraft(request, null);
    }

    @Transactional
    public DraftResponse createDraft(DraftRequest request, String ownerId) {
        String prompt = request.prompt().trim();
        List<PoiEntity> allPois = poiRepository.findAll();
        TripRequestEntity entity = new TripRequestEntity();
        entity.setId("req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        entity.setSessionId(blankToDefault(request.sessionId(), "anonymous"));
        entity.setOwnerId(ownerId);
        entity.setRawPrompt(prompt);
        entity.setCity(resolveCity(prompt));
        entity.setDays(resolveDays(prompt));
        entity.setStartDate(resolveStartDate(prompt));
        entity.setPeople(resolvePeople(prompt));
        entity.setBudget(resolveBudget(prompt));
        entity.setPace(prompt.matches(".*(轻松|休闲|不想走太累|慢一点).*") ? "RELAXED" : "BALANCED");
        entity.setNoSpicy(prompt.matches(".*(不吃辣|不能吃辣|清淡).*"));
        List<PoiEntity> excludedPois = resolveExcludedPois(prompt, allPois);
        List<PoiEntity> mustSee = resolveMustSee(prompt, allPois).stream()
                .filter(poi -> excludedPois.stream().noneMatch(excluded -> excluded.getId().equals(poi.getId())))
                .toList();
        entity.setMustSee(String.join(",", mustSee.stream().map(PoiEntity::getId).toList()));
        entity.setExcludedPoiIds(String.join(",", excludedPois.stream().map(PoiEntity::getId).toList()));
        entity.setInterestTags(String.join(",", inferInterestTags(prompt, allPois)));
        entity.setCreatedAt(LocalDateTime.now(SHANGHAI));
        enrichWithAi(prompt, entity, allPois);
        if (prompt.matches(".*(带父母|和爸妈|一家三口).*") && entity.getPeople() != null && entity.getPeople() < 3) {
            entity.setPeople(3);
        }

        if (entity.getDays() != null && entity.getStartDate() != null) {
            entity.setEndDate(entity.getStartDate().plusDays(entity.getDays() - 1L));
        }
        normalizePoiPreferences(entity, allPois);
        entity.setSummary(buildSummary(entity, mustSeeEntities(entity)));

        requestRepository.save(entity);
        return toDraft(entity, allPois);
    }

    @Transactional
    public DraftResponse updateFromNaturalLanguage(String requestId, NaturalLanguageUpdateRequest request) {
        TripRequestEntity entity = requireRequest(requestId);
        String message = request.message().trim();
        Integer days = resolveDays(message);
        if (days != null) {
            entity.setDays(days);
        }
        Integer people = resolvePeopleStrict(message);
        if (people != null) {
            entity.setPeople(people);
        }
        BigDecimal budget = resolveBudget(message);
        if (budget != null) {
            entity.setBudget(budget);
        }
        LocalDate startDate = resolveStartDate(message);
        if (startDate != null) {
            entity.setStartDate(startDate);
        }
        if (message.matches(".*(轻松|慢一点|不想走太累|休闲).*")) {
            entity.setPace("RELAXED");
        } else if (message.matches(".*(紧凑|多去几个|多安排|特种兵).*")) {
            entity.setPace("BALANCED");
        }
        if (message.matches(".*(可以吃辣|吃辣没关系).*")) {
            entity.setNoSpicy(false);
        } else if (message.matches(".*(不吃辣|清淡|不要辣).*")) {
            entity.setNoSpicy(true);
        }

        List<PoiEntity> allPois = poiRepository.findAll();
        LinkedHashSet<String> mustSeeIds = new LinkedHashSet<>(mustSeeEntities(entity).stream().map(PoiEntity::getId).toList());
        LinkedHashSet<String> excludedIds = new LinkedHashSet<>(csvValues(entity.getExcludedPoiIds()));
        LinkedHashSet<String> interests = new LinkedHashSet<>(csvValues(entity.getInterestTags()));
        interests.addAll(inferInterestTags(message, allPois));
        for (PoiEntity candidate : resolveMustSee(message, allPois)) {
            if (isExcludedMention(message, candidate)) {
                mustSeeIds.remove(candidate.getId());
                excludedIds.add(candidate.getId());
            } else {
                mustSeeIds.add(candidate.getId());
            }
        }
        entity.setMustSee(String.join(",", mustSeeIds));
        entity.setExcludedPoiIds(String.join(",", excludedIds));
        entity.setInterestTags(String.join(",", interests));
        normalizePoiPreferences(entity, allPois);
        if (entity.getStartDate() != null && entity.getDays() != null) {
            entity.setEndDate(entity.getStartDate().plusDays(entity.getDays() - 1L));
        }
        entity.setRawPrompt(entity.getRawPrompt() + "\n修改：" + message);
        entity.setSummary(buildSummary(entity, mustSeeEntities(entity)));
        requestRepository.save(entity);
        return toDraft(entity, allPois);
    }

    @Transactional
    public DraftResponse updateRequirements(String requestId, RequirementsUpdate update) {
        TripRequestEntity entity = requireRequest(requestId);
        if (update.city() != null && !update.city().isBlank()) {
            if (!defaultCity.equals(update.city().trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前数据暂未覆盖该城市");
            }
            entity.setCity(defaultCity);
        }
        if (update.startDate() != null) {
            entity.setStartDate(update.startDate());
        }
        if (update.days() != null) {
            entity.setDays(update.days());
        }
        if (update.people() != null) {
            entity.setPeople(update.people());
        }
        if (update.budget() != null) {
            entity.setBudget(update.budget());
        }
        if (update.pace() != null) {
            entity.setPace(update.pace());
        }
        if (update.noSpicy() != null) {
            entity.setNoSpicy(update.noSpicy());
        }
        if (update.mustSee() != null) {
            List<PoiEntity> all = poiRepository.findAll();
            List<String> ids = update.mustSee().stream()
                    .map(value -> findPoi(value, all))
                    .filter(poi -> poi != null)
                    .map(PoiEntity::getId)
                    .distinct()
                    .toList();
            entity.setMustSee(String.join(",", ids));
        }
        if (entity.getStartDate() != null && entity.getDays() != null) {
            entity.setEndDate(entity.getStartDate().plusDays(entity.getDays() - 1L));
        }
        normalizePoiPreferences(entity, poiRepository.findAll());
        entity.setSummary(buildSummary(entity, mustSeeEntities(entity)));
        requestRepository.save(entity);
        return toDraft(entity, poiRepository.findAll());
    }

    @Transactional
    public PlanView generatePlan(String requestId) {
        TripRequestEntity request = requireRequest(requestId);
        List<String> missing = missingFields(request);
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "还需要补充：" + String.join("、", missing));
        }
        List<PoiEntity> allPois = poiRepository.findAll();
        List<DayPlan> days = new ArrayList<>();
        List<PlanIssue> issues = new ArrayList<>();
        List<PoiEntity> mustSee = mustSeeEntities(request);
        int baseMaxPoisPerDay = "RELAXED".equals(request.getPace()) ? 3 : 4;
        int mustSeePerDay = (int) Math.ceil((double) mustSee.size() / request.getDays());
        int maxPoisPerDay = Math.max(baseMaxPoisPerDay, mustSeePerDay);
        if (maxPoisPerDay > baseMaxPoisPerDay) {
            issues.add(new PlanIssue(
                    "MUST_SEE_DENSITY",
                    "WARNING",
                    "必去景点数量较多，每天的安排会高于当前节奏建议。",
                    "可以减少必去景点或增加旅行天数。"
            ));
        }

        List<List<PoiEntity>> dailyPois = selectDailyPois(request, allPois, mustSee, maxPoisPerDay);
        for (int day = 0; day < dailyPois.size(); day++) {
            LocalDate date = request.getStartDate().plusDays(day);
            days.add(buildDay(day + 1, date, request, dailyPois.get(day), issues));
        }

        validateDayPlans(days, issues);
        BigDecimal estimated = days.stream()
                .flatMap(day -> day.items().stream())
                .map(PlanItem::cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (request.getBudget().compareTo(estimated) < 0) {
            issues.add(new PlanIssue(
                    "BUDGET_EXCEEDED",
                    "ERROR",
                    "预计花费 ¥" + estimated + "，超过预算 ¥" + request.getBudget(),
                    "可以减少付费景点、缩短天数，或提高预算。"
            ));
        }
        long demoFacts = allPois.stream().filter(p -> "DEMO".equals(p.getDataConfidence())).count();
        if (demoFacts > 0) {
            issues.add(new PlanIssue(
                    "LIVE_DATA_PENDING",
                    "INFO",
                    "开放时间和票价使用演示资料，出发前请通过官方渠道确认。",
                    "MVP 暂未接入实时景区状态。"
            ));
        }

        Set<String> plannedIds = days.stream()
                .flatMap(day -> day.items().stream())
                .map(PlanItem::poiId)
                .filter(value -> value != null)
                .collect(java.util.stream.Collectors.toSet());
        for (PoiEntity must : mustSee) {
            if (!plannedIds.contains(must.getId())) {
                issues.add(new PlanIssue(
                        "MUST_SEE_MISSING",
                        "ERROR",
                        "必去景点“" + must.getName() + "”未进入行程",
                        "系统需要重新安排交通或减少景点数量。"
                ));
            }
        }

        List<MapPoint> mapPoints = new ArrayList<>();
        int order = 1;
        for (DayPlan day : days) {
            for (PlanItem item : day.items()) {
                if ("POI".equals(item.type()) && item.poiId() != null) {
                    mapPoints.add(new MapPoint(item.poiId(), item.name(), item.latitude(), item.longitude(), day.dayNumber(), order++));
                }
            }
        }

        boolean passed = issues.stream().noneMatch(issue -> "ERROR".equals(issue.severity()));
        String title = request.getCity() + " · "
                + ("RELAXED".equals(request.getPace()) ? "松弛" : "城市")
                + toChineseDays(request.getDays()) + "漫游";
        String subtitle = "根据你的兴趣、预算与节奏生成路线，把时间留给真正值得停留的地方。";
        int nextVersion = planRepository.findByRequestIdOrderByVersionDesc(requestId).stream()
                .findFirst()
                .map(existing -> existing.getVersion() + 1)
                .orElse(1);
        PlanView plan = new PlanView(
                "plan_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                request.getId(),
                nextVersion,
                title,
                subtitle,
                request.getCity(),
                request.getStartDate(),
                request.getDays(),
                request.getPeople(),
                request.getBudget(),
                estimated,
                request.getPace(),
                request.getMustSee().isBlank() ? List.of() : request.getMustSee().split(",").length == 0 ? List.of() : List.of(request.getMustSee().split(",")),
                days,
                issues,
                passed,
                mapPoints,
                aiGateway.isEnabled() ? "Spring AI + 规则校验" : "本地语义规则 + 确定性校验"
        );
        persistPlan(plan);
        return plan;
    }

    public List<PlanView> listVersions(String requestId) {
        requireRequest(requestId);
        return planRepository.findByRequestIdOrderByVersionDesc(requestId).stream()
                .map(entity -> {
                    try {
                        return objectMapper.readValue(entity.getPlanJson(), PlanView.class);
                    } catch (JsonProcessingException exception) {
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "行程数据读取失败");
                    }
                })
                .toList();
    }

    public List<HistoryItem> history(String ownerId) {
        if (ownerId == null) {
            return List.of();
        }
        return requestRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .map(request -> {
                    var plans = planRepository.findByRequestIdOrderByVersionDesc(request.getId());
                    return new HistoryItem(
                            request.getId(),
                            plans.isEmpty() ? null : plans.get(0).getId(),
                            request.getSummary(),
                            request.getStartDate(),
                            request.getCreatedAt(),
                            plans.size()
                    );
                })
                .toList();
    }

    @Transactional
    public PlanView swapPoi(String planId, SwapRequest request) {
        PlanView base = getPlan(planId);
        PoiEntity replacement = poiRepository.findById(request.replacementPoiId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "替换景点不存在"));
        if (base.mapPoints().stream().anyMatch(point -> point.poiId().equals(replacement.getId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该景点已经在行程中");
        }

        boolean found = false;
        String replacedName = null;
        List<DayPlan> newDays = new ArrayList<>();
        for (DayPlan day : base.dayPlans()) {
            List<PlanItem> newItems = new ArrayList<>();
            for (PlanItem item : day.items()) {
                if (item.id().equals(request.itemId())) {
                    if (!"POI".equals(item.type())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能替换景点项目");
                    }
                    found = true;
                    replacedName = item.name();
                    newItems.add(new PlanItem(
                            item.id(),
                            "POI",
                            replacement.getId(),
                            replacement.getName(),
                            shortName(replacement.getName()),
                            replacement.getArea(),
                            item.startTime(),
                            item.endTime(),
                            item.durationMinutes(),
                            replacement.getTicketPrice().multiply(BigDecimal.valueOf(base.people())),
                            replacement.getSummary() + "（沿用原时间窗）",
                            replacement.getSourceName(),
                            replacement.getSourceUrl(),
                            replacement.getAccent(),
                            replacement.getLatitude(),
                            replacement.getLongitude(),
                            item.transitFromPrevious()
                    ));
                } else {
                    newItems.add(item);
                }
            }
            newDays.add(new DayPlan(day.dayNumber(), day.date(), day.label(), day.title(), day.summary(), newItems));
        }
        if (!found) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "行程项目不存在");
        }

        List<PlanIssue> issues = new ArrayList<>(base.issues());
        issues.add(new PlanIssue(
                "POI_REPLACED",
                "INFO",
                "已将“" + replacedName + "”替换为“" + replacement.getName() + "”。",
                "交通时间仍沿用当前估算，请重新生成或现场确认路线。"
        ));
        PlanView swapped = rebuildPlan(base, newDays, issues, "替换景点 · " + replacement.getName());
        persistPlan(swapped);
        return swapped;
    }

    public List<PoiView> alternatives(String planId, String itemId) {
        PlanView plan = getPlan(planId);
        PlanItem target = plan.dayPlans().stream()
                .flatMap(day -> day.items().stream())
                .filter(item -> item.id().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "行程项目不存在"));
        Set<String> used = plan.mapPoints().stream().map(MapPoint::poiId).collect(java.util.stream.Collectors.toSet());
        return poiRepository.findAll().stream()
                .filter(poi -> !used.contains(poi.getId()))
                .sorted(Comparator
                        .comparing((PoiEntity poi) -> !poi.getArea().equals(target.area()))
                        .thenComparingDouble(poi -> distance(
                                target.latitude(), target.longitude(), poi.getLatitude(), poi.getLongitude())))
                .limit(5)
                .map(poiMapper::toView)
                .toList();
    }

    @Transactional
    public PlanView restoreVersion(String planId) {
        PlanView source = getPlan(planId);
        int nextVersion = planRepository.findByRequestIdOrderByVersionDesc(source.requestId()).stream()
                .findFirst()
                .map(existing -> existing.getVersion() + 1)
                .orElse(1);
        PlanView restored = new PlanView(
                "plan_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                source.requestId(),
                nextVersion,
                source.title(),
                source.subtitle(),
                source.city(),
                source.startDate(),
                source.days(),
                source.people(),
                source.budget(),
                source.estimatedCost(),
                source.pace(),
                source.mustSee(),
                source.dayPlans(),
                source.issues(),
                source.passed(),
                source.mapPoints(),
                "恢复版本 v" + source.version()
        );
        persistPlan(restored);
        return restored;
    }

    public PlanView getPlan(String planId) {
        TripPlanEntity entity = planRepository.findById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "行程不存在"));
        try {
            return objectMapper.readValue(entity.getPlanJson(), PlanView.class);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "行程数据读取失败");
        }
    }

    public TripRequestEntity requireRequest(String requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "旅行需求不存在"));
    }

    public List<PoiEntity> mustSeeEntities(TripRequestEntity request) {
        if (request.getMustSee() == null || request.getMustSee().isBlank()) {
            return List.of();
        }
        Map<String, PoiEntity> byId = new LinkedHashMap<>();
        poiRepository.findAll().forEach(poi -> byId.put(poi.getId(), poi));
        return java.util.Arrays.stream(request.getMustSee().split(","))
                .map(byId::get)
                .filter(poi -> poi != null)
                .toList();
    }

    private void enrichWithAi(String prompt, TripRequestEntity entity, List<PoiEntity> allPois) {
        if (!aiGateway.isEnabled()) {
            return;
        }
        try {
            String instructions = """
                    你是旅行需求结构化抽取器。只输出一个 JSON 对象，不要 Markdown。
                    字段：city, days, startDate, people, budget, pace, noSpicy, mustSee, interests, excluded。
                    pace 只能是 RELAXED 或 BALANCED；日期格式 YYYY-MM-DD；未知字段输出 null。
                    mustSee 只能从给定景点名称中选择。
                    interests 提取建筑、街区、博物馆、人文、艺术、自然、亲子、夜景、拍照等主题。
                    excluded 只能从给定景点名称中选择用户明确不想去的地点。
                    """;
            String knownPois = allPois.stream().map(PoiEntity::getName).collect(java.util.stream.Collectors.joining("、"));
            String response = aiGateway.complete(instructions, "景点列表：" + knownPois + "\n用户需求：" + prompt);
            com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(stripCodeFence(response));
            if (json.hasNonNull("city")) {
                String city = json.path("city").asText().trim();
                entity.setCity(defaultCity.equals(city) || city.contains(defaultCity) ? defaultCity : null);
            }
            if (json.hasNonNull("days") && json.path("days").asInt() >= 1 && json.path("days").asInt() <= 3) {
                entity.setDays(json.path("days").asInt());
            }
            if (json.hasNonNull("startDate")) {
                try {
                    entity.setStartDate(LocalDate.parse(json.path("startDate").asText()));
                } catch (RuntimeException ignored) {
                    // 模型返回非法日期时，保留规则抽取结果。
                }
            }
            if (json.hasNonNull("people") && json.path("people").asInt() >= 1 && json.path("people").asInt() <= 6) {
                entity.setPeople(json.path("people").asInt());
            }
            if (json.hasNonNull("budget") && json.path("budget").decimalValue().signum() > 0) {
                entity.setBudget(json.path("budget").decimalValue());
            }
            if (json.hasNonNull("pace") && Set.of("RELAXED", "BALANCED").contains(json.path("pace").asText())) {
                entity.setPace(json.path("pace").asText());
            }
            if (json.hasNonNull("noSpicy")) {
                entity.setNoSpicy(json.path("noSpicy").asBoolean());
            }
            if (json.has("mustSee") && json.path("mustSee").isArray()) {
                List<String> names = new ArrayList<>();
                json.path("mustSee").forEach(node -> names.add(node.asText()));
                List<PoiEntity> aiMustSee = names.stream()
                        .map(name -> findPoi(name, allPois))
                        .filter(poi -> poi != null && mentionsPoi(prompt, poi))
                        .toList();
                if (!aiMustSee.isEmpty()) {
                    entity.setMustSee(aiMustSee.stream().map(PoiEntity::getId).distinct().collect(java.util.stream.Collectors.joining(",")));
                }
            }
            if (json.has("interests") && json.path("interests").isArray()) {
                List<String> interests = new ArrayList<>();
                json.path("interests").forEach(node -> interests.add(node.asText()));
                entity.setInterestTags(String.join(",", interests));
            }
            if (json.has("excluded") && json.path("excluded").isArray()) {
                List<PoiEntity> excluded = new ArrayList<>();
                json.path("excluded").forEach(node -> {
                    PoiEntity poi = findPoi(node.asText(), allPois);
                    if (poi != null) {
                        excluded.add(poi);
                    }
                });
                entity.setExcludedPoiIds(excluded.stream()
                        .map(PoiEntity::getId)
                        .distinct()
                        .collect(java.util.stream.Collectors.joining(",")));
            }
        } catch (RuntimeException | JsonProcessingException ignored) {
            // 模型调用失败时，使用确定性抽取器兜底。
        }
    }

    private PlanView rebuildPlan(
            PlanView base,
            List<DayPlan> newDays,
            List<PlanIssue> issues,
            String generatedBy
    ) {
        BigDecimal estimated = newDays.stream()
                .flatMap(day -> day.items().stream())
                .map(PlanItem::cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<MapPoint> points = new ArrayList<>();
        int order = 1;
        for (DayPlan day : newDays) {
            for (PlanItem item : day.items()) {
                if ("POI".equals(item.type()) && item.poiId() != null) {
                    points.add(new MapPoint(
                            item.poiId(), item.name(), item.latitude(), item.longitude(), day.dayNumber(), order++
                    ));
                }
            }
        }
        boolean passed = issues.stream().noneMatch(issue -> "ERROR".equals(issue.severity()));
        int nextVersion = planRepository.findByRequestIdOrderByVersionDesc(base.requestId()).stream()
                .findFirst()
                .map(existing -> existing.getVersion() + 1)
                .orElse(1);
        return new PlanView(
                "plan_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                base.requestId(),
                nextVersion,
                base.title(),
                base.subtitle(),
                base.city(),
                base.startDate(),
                base.days(),
                base.people(),
                base.budget(),
                estimated,
                base.pace(),
                base.mustSee(),
                newDays,
                issues,
                passed,
                points,
                generatedBy
        );
    }

    private void persistPlan(PlanView plan) {
        try {
            TripPlanEntity entity = new TripPlanEntity();
            entity.setId(plan.id());
            entity.setRequestId(plan.requestId());
            entity.setVersion(plan.version());
            entity.setPassed(plan.passed());
            entity.setPlanJson(objectMapper.writeValueAsString(plan));
            entity.setIssuesJson(objectMapper.writeValueAsString(plan.issues()));
            entity.setCreatedAt(LocalDateTime.now(SHANGHAI));
            planRepository.save(entity);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "行程保存失败");
        }
    }

    private DayPlan buildDay(
            int dayNumber,
            LocalDate date,
            TripRequestEntity request,
            List<PoiEntity> pois,
            List<PlanIssue> issues
    ) {
        List<PlanItem> items = new ArrayList<>();
        LocalTime cursor = LocalTime.of(9, 0);
        boolean lunchAdded = false;
        int sequence = 1;
        PlanItem previous = null;
        PoiEntity previousPoi = null;

        for (PoiEntity poi : pois) {
            int duration = poi.getDurationMinutes() == null ? 60 : poi.getDurationMinutes();
            AmapRouteService.RouteEstimate route = previousPoi == null ? null : routeService.between(previousPoi, poi);
            int plannedTransit = route == null ? 0 : route.minutes();
            boolean crossesLunch =
                    !lunchAdded
                    && cursor.isBefore(LocalTime.of(13, 30))
                    && cursor.plusMinutes(plannedTransit + duration).isAfter(LocalTime.NOON);

            if (crossesLunch) {
                LocalTime lunchStart = cursor.isBefore(LocalTime.of(11, 40)) ? LocalTime.of(11, 45) : cursor.plusMinutes(15);
                PlanItem lunch = mealItem(
                        dayNumber,
                        sequence++,
                        request,
                        "当地风味午餐",
                        "避开重辣，优先选择可现场排队的餐馆",
                        lunchStart
                );
                items.add(lunch);
                cursor = lunchStart.plusMinutes(60);
                lunchAdded = true;
                previous = lunch;
            }
            int transitMinutes = route == null ? 0 : route.minutes();
            cursor = cursor.plusMinutes(transitMinutes);
            LocalTime end = cursor.plusMinutes(duration);
            if (end.isAfter(LocalTime.of(17, 30)) && pois.size() > 2) {
                issues.add(new PlanIssue(
                        "PACE_DENSITY",
                        "WARNING",
                        "第" + dayNumber + "天景点偏多，结束时间较晚。",
                        "轻松模式建议减少一个主要景点。"
                ));
            }
            BigDecimal cost = poi.getTicketPrice() == null ? BigDecimal.ZERO : poi.getTicketPrice()
                    .multiply(BigDecimal.valueOf(request.getPeople()));
            PlanItem item = new PlanItem(
                    "item_" + dayNumber + "_" + sequence++,
                    "POI",
                    poi.getId(),
                    poi.getName(),
                    shortName(poi.getName()),
                    poi.getArea(),
                    cursor.format(DateTimeFormatter.ofPattern("HH:mm")),
                    end.format(DateTimeFormatter.ofPattern("HH:mm")),
                    duration,
                    cost,
                    poi.getSummary(),
                    poi.getSourceName(),
                    poi.getSourceUrl(),
                    poi.getAccent(),
                    poi.getLatitude(),
                    poi.getLongitude(),
                    route == null ? null : new TransitView(route.mode(), route.label(), transitMinutes)
            );
            items.add(item);
            previous = item;
            previousPoi = poi;
            cursor = end;
        }

        if (!lunchAdded) {
            LocalTime lunchStart = cursor.isBefore(LocalTime.of(12, 10)) ? LocalTime.of(12, 10) : cursor.plusMinutes(15);
            items.add(mealItem(dayNumber, sequence, request, "当地风味午餐", "按饮食偏好就近选择，留出 1 小时用餐", lunchStart));
        }

        String theme = switch (dayNumber % 3) {
            case 1 -> "初见目的地 · 从代表性地点开始";
            case 2 -> "在地文化与日常街区";
            default -> "兴趣延伸 · 留一点自由时间";
        };
        return new DayPlan(
                dayNumber,
                date.toString(),
                "DAY " + dayNumber,
                theme,
                pois.stream().map(PoiEntity::getName).limit(3).collect(java.util.stream.Collectors.joining(" · ")),
                items
        );
    }

    private PlanItem mealItem(
            int dayNumber,
            int sequence,
            TripRequestEntity request,
            String name,
            String note,
            LocalTime start
    ) {
        return new PlanItem(
                "meal_" + dayNumber + "_" + sequence,
                "MEAL",
                null,
                name,
                "午餐",
                "就近",
                start.format(DateTimeFormatter.ofPattern("HH:mm")),
                start.plusMinutes(60).format(DateTimeFormatter.ofPattern("HH:mm")),
                60,
                BigDecimal.valueOf(90L * request.getPeople()),
                note,
                "MVP 示例建议",
                null,
                "#D4A373",
                0,
                0,
                null
        );
    }

    private void validateDayPlans(List<DayPlan> days, List<PlanIssue> issues) {
        Map<String, PoiEntity> poiById = new LinkedHashMap<>();
        poiRepository.findAll().forEach(poi -> poiById.put(poi.getId(), poi));

        for (DayPlan day : days) {
            LocalTime previousEnd = LocalTime.MIN;
            for (PlanItem item : day.items()) {
                LocalTime start = LocalTime.parse(item.startTime());
                LocalTime end = LocalTime.parse(item.endTime());
                if (start.isBefore(previousEnd)) {
                    issues.add(new PlanIssue(
                            "TIME_OVERLAP",
                            "ERROR",
                            "第" + day.dayNumber() + "天存在时间重叠：" + item.name(),
                            "需要移动景点或缩短前一站停留时间。"
                    ));
                }
                if (end.isBefore(start)) {
                    issues.add(new PlanIssue(
                            "INVALID_TIME_RANGE",
                            "ERROR",
                            "第" + day.dayNumber() + "天存在无效时间：" + item.name(),
                            "需要重新生成该天行程。"
                    ));
                }
                PoiEntity poi = item.poiId() == null ? null : poiById.get(item.poiId());
                if (poi != null && poi.getCloseTime() != null && !poi.getCloseTime().isBlank()
                        && poi.getCloseTime().matches("\\d{1,2}:\\d{2}")) {
                    LocalTime close = LocalTime.parse(poi.getCloseTime());
                    if (end.isAfter(close)) {
                        issues.add(new PlanIssue(
                                "OPENING_HOURS",
                                "ERROR",
                                item.name() + " 超过了记录的关闭时间 " + poi.getCloseTime(),
                                "需要提前该景点或改到另一天。"
                        ));
                    }
                }
                previousEnd = end;
            }
        }
    }

    private List<String> missingFields(TripRequestEntity request) {
        List<String> missing = new ArrayList<>();
        if (request.getCity() == null || request.getCity().isBlank()) {
            missing.add("city");
        }
        if (request.getStartDate() == null) {
            missing.add("startDate");
        }
        if (request.getBudget() == null) {
            missing.add("budget");
        }
        if (request.getDays() == null) {
            missing.add("days");
        }
        return missing;
    }

    private DraftResponse toDraft(TripRequestEntity entity, List<PoiEntity> allPois) {
        List<PoiView> suggestions = allPois.stream()
                .filter(poi -> fitsDailyLimit(List.of(), poi, entity))
                .sorted(Comparator.comparingInt((PoiEntity poi) -> -preferenceScore(
                        poi,
                        new HashSet<>(csvValues(entity.getInterestTags())),
                        entity
                )).thenComparing(PoiEntity::getName))
                .limit(5)
                .map(poiMapper::toView)
                .toList();
        return new DraftResponse(
                entity.getId(),
                entity.getCity(),
                entity.getDays(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.getPeople(),
                entity.getBudget(),
                entity.getPace(),
                entity.getNoSpicy(),
                entity.getMustSee().isBlank() ? List.of() : List.of(entity.getMustSee().split(",")),
                csvValues(entity.getInterestTags()),
                csvValues(entity.getExcludedPoiIds()),
                missingFields(entity),
                entity.getSummary(),
                suggestions
        );
    }

    private String buildSummary(TripRequestEntity entity, List<PoiEntity> mustSee) {
        String names = mustSee.isEmpty() ? "城市代表景点" : mustSee.stream().map(PoiEntity::getName).collect(java.util.stream.Collectors.joining("、"));
        String pace = "RELAXED".equals(entity.getPace()) ? "轻松节奏" : "平衡节奏";
        String interests = csvValues(entity.getInterestTags()).isEmpty()
                ? ""
                : " · 关注 " + String.join("、", csvValues(entity.getInterestTags()));
        String city = entity.getCity() == null || entity.getCity().isBlank() ? "目的地待定" : entity.getCity();
        return city + " " + (entity.getDays() == null ? "待定" : entity.getDays()) + " 天 · " + pace + interests + " · " + names;
    }

    /**
     * 按用户偏好生成每天候选景点，再按距离聚类排序。
     * 不再依赖固定城市路线，景点来自数据库标签、区域、距离和明确必去项。
     */
    private List<List<PoiEntity>> selectDailyPois(
            TripRequestEntity request,
            List<PoiEntity> allPois,
            List<PoiEntity> mustSee,
            int targetPerDay
    ) {
        Set<String> excluded = new HashSet<>(csvValues(request.getExcludedPoiIds()));
        Set<String> interests = new HashSet<>(csvValues(request.getInterestTags()));
        if (interests.isEmpty()) {
            interests.addAll(inferInterestTags(request.getRawPrompt(), allPois));
        }

        List<List<PoiEntity>> groups = new ArrayList<>();
        for (int day = 0; day < request.getDays(); day++) {
            groups.add(new ArrayList<>());
        }

        // 必去景点优先均分到不同日期，避免固定景点组决定路线。
        for (int index = 0; index < mustSee.size(); index++) {
            PoiEntity poi = mustSee.get(index);
            if (!excluded.contains(poi.getId())) {
                groups.get(index % groups.size()).add(poi);
            }
        }

        Set<String> used = groups.stream()
                .flatMap(List::stream)
                .map(PoiEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<PoiEntity> ranked = allPois.stream()
                .filter(poi -> !excluded.contains(poi.getId()))
                .sorted(Comparator
                        .comparingInt((PoiEntity poi) -> -preferenceScore(poi, interests, request))
                        .thenComparing(PoiEntity::getName))
                .toList();

        for (List<PoiEntity> group : groups) {
            while (group.size() < targetPerDay) {
                PoiEntity next = nextBestPoi(group, ranked, used, interests, request);
                if (next == null) {
                    break;
                }
                group.add(next);
                used.add(next.getId());
            }
        }
        groups.forEach(this::orderByNearestNeighbor);
        return groups;
    }

    private PoiEntity nextBestPoi(
            List<PoiEntity> currentDay,
            List<PoiEntity> ranked,
            Set<String> used,
            Set<String> interests,
            TripRequestEntity request
    ) {
        PoiEntity anchor = currentDay.isEmpty() ? null : currentDay.get(currentDay.size() - 1);
        Set<String> usedTypes = currentDay.stream().map(PoiEntity::getType).collect(java.util.stream.Collectors.toSet());
        return ranked.stream()
                .filter(poi -> !used.contains(poi.getId()))
                .filter(poi -> fitsDailyLimit(currentDay, poi, request))
                .max(Comparator.comparingDouble(poi -> {
                    double score = preferenceScore(poi, interests, request);
                    if (anchor != null) {
                        score -= distanceKm(anchor, poi) * 12;
                    }
                    if (usedTypes.contains(poi.getType())) {
                        score -= 12;
                    }
                    return score;
                }))
                .orElse(null);
    }

    private boolean fitsDailyLimit(List<PoiEntity> currentDay, PoiEntity candidate, TripRequestEntity request) {
        List<PoiEntity> proposed = new ArrayList<>(currentDay);
        proposed.add(candidate);
        proposed.sort(Comparator.comparingInt(this::closeMinute).thenComparing(PoiEntity::getName));

        LocalTime cursor = LocalTime.of(9, 0);
        PoiEntity previous = null;
        boolean lunchAdded = false;
        for (PoiEntity poi : proposed) {
            int travel = previous == null ? 0 : approximateRouteMinutes(previous, poi);
            LocalTime arrival = cursor.plusMinutes(travel);
            Integer open = openMinute(poi);
            if (open != null && arrival.isBefore(LocalTime.of(open / 60, open % 60))) {
                arrival = LocalTime.of(open / 60, open % 60);
            }
            int duration = poi.getDurationMinutes() == null ? 60 : poi.getDurationMinutes();
            if (!lunchAdded && arrival.plusMinutes(duration).isAfter(LocalTime.NOON)
                    && arrival.isBefore(LocalTime.of(13, 30))) {
                arrival = arrival.isAfter(LocalTime.of(11, 45)) ? arrival.plusMinutes(15) : LocalTime.of(11, 45);
                arrival = arrival.plusMinutes(60);
                lunchAdded = true;
            }
            LocalTime end = arrival.plusMinutes(duration);
            Integer close = closeMinuteOrNull(poi);
            if (close != null && end.isAfter(LocalTime.of(close / 60, close % 60))) {
                return false;
            }
            cursor = end;
            previous = poi;
        }
        LocalTime latest = LocalTime.of("RELAXED".equals(request.getPace()) ? 18 : 19, 30);
        return !cursor.isAfter(latest);
    }

    private void orderByNearestNeighbor(List<PoiEntity> group) {
        if (group.size() < 3) {
            return;
        }
        List<PoiEntity> remaining = new ArrayList<>(group);
        List<PoiEntity> ordered = new ArrayList<>();
        PoiEntity current = remaining.stream()
                .min(Comparator
                        .comparingInt(this::closeMinute)
                        .thenComparing(PoiEntity::getName))
                .orElseThrow();
        remaining.remove(current);
        ordered.add(current);
        while (!remaining.isEmpty()) {
            PoiEntity origin = current;
            PoiEntity nearest = remaining.stream()
                    .min(Comparator
                            .comparingInt(this::closeMinute)
                            .thenComparingDouble(poi -> distanceKm(origin, poi)))
                    .orElseThrow();
            remaining.remove(nearest);
            ordered.add(nearest);
            current = nearest;
        }
        group.clear();
        group.addAll(ordered);
    }

    private int closeMinute(PoiEntity poi) {
        Integer close = closeMinuteOrNull(poi);
        return close == null ? 23 * 60 : close;
    }

    private Integer closeMinuteOrNull(PoiEntity poi) {
        String close = poi.getCloseTime();
        if (close == null || !close.matches("\\d{1,2}:\\d{2}")) {
            return null;
        }
        LocalTime time = LocalTime.parse(close);
        return time.getHour() * 60 + time.getMinute();
    }

    private Integer openMinute(PoiEntity poi) {
        String open = poi.getOpenTime();
        if (open == null || !open.matches("\\d{1,2}:\\d{2}")) {
            return null;
        }
        LocalTime time = LocalTime.parse(open);
        return time.getHour() * 60 + time.getMinute();
    }

    private int approximateRouteMinutes(PoiEntity from, PoiEntity to) {
        double distance = distanceKm(from, to);
        if (distance <= 1.5) {
            return Math.max(6, (int) Math.ceil(distance / 4.5 * 60 + 4));
        }
        return Math.max(15, (int) Math.ceil(distance / 18 * 60 + 8));
    }

    private int preferenceScore(PoiEntity poi, Set<String> interests, TripRequestEntity request) {
        int matches = (int) csvValues(poi.getTags()).stream().filter(interests::contains).count();
        int score = matches * 120;
        if (request.getRawPrompt().contains(poi.getArea())) {
            score += 35;
        }
        if ("RELAXED".equals(request.getPace()) && poi.getDurationMinutes() != null && poi.getDurationMinutes() > 120) {
            score -= 25;
        }
        if (request.getBudget() != null && request.getBudget().compareTo(BigDecimal.valueOf(1500)) < 0
                && poi.getTicketPrice() != null && poi.getTicketPrice().compareTo(BigDecimal.valueOf(100)) > 0) {
            score -= 35;
        }
        return score;
    }

    private Set<String> inferInterestTags(String prompt, List<PoiEntity> allPois) {
        Set<String> tags = new LinkedHashSet<>();
        allPois.stream()
                .flatMap(poi -> csvValues(poi.getTags()).stream())
                .distinct()
                .filter(prompt::contains)
                .forEach(tags::add);

        Map<String, List<String>> synonyms = Map.ofEntries(
                Map.entry("建筑", List.of("建筑", "历史建筑", "城市更新")),
                Map.entry("街区", List.of("街区", "城市漫步", "建筑")),
                Map.entry("梧桐", List.of("街区", "建筑", "历史建筑")),
                Map.entry("博物馆", List.of("博物馆", "人文", "历史")),
                Map.entry("人文", List.of("人文", "历史", "历史建筑", "阅读")),
                Map.entry("历史", List.of("历史", "人文", "历史建筑")),
                Map.entry("艺术", List.of("艺术", "展览")),
                Map.entry("展览", List.of("展览", "艺术")),
                Map.entry("自然", List.of("公园", "园林", "科普")),
                Map.entry("公园", List.of("公园", "休息")),
                Map.entry("园林", List.of("园林", "建筑")),
                Map.entry("亲子", List.of("亲子", "科普", "室内")),
                Map.entry("孩子", List.of("亲子", "科普", "室内")),
                Map.entry("夜景", List.of("夜景", "江景", "城市景观")),
                Map.entry("江景", List.of("江景", "城市景观")),
                Map.entry("美食", List.of("餐饮", "小吃")),
                Map.entry("小吃", List.of("小吃", "餐饮")),
                Map.entry("拍照", List.of("建筑", "街区", "园林", "城市景观")),
                Map.entry("摄影", List.of("建筑", "街区", "园林", "城市景观")),
                Map.entry("安静", List.of("公园", "阅读", "街区")),
                Map.entry("人少", List.of("公园", "街区", "艺术")),
                Map.entry("购物", List.of("购物", "街区"))
        );
        synonyms.forEach((keyword, relatedTags) -> {
            if (prompt.contains(keyword)) {
                tags.addAll(relatedTags);
            }
        });
        return tags;
    }

    private List<PoiEntity> resolveExcludedPois(String prompt, List<PoiEntity> allPois) {
        return allPois.stream()
                .filter(poi -> mentionsPoi(prompt, poi) && isExcludedMention(prompt, poi))
                .toList();
    }

    private boolean isExcludedMention(String message, PoiEntity poi) {
        int nameIndex = Math.max(
                message.indexOf(poi.getName()),
                poi.getAliasName() == null ? -1 : message.indexOf(poi.getAliasName())
        );
        if (nameIndex < 0) {
            return false;
        }
        int start = Math.max(0, nameIndex - 10);
        String before = message.substring(start, nameIndex);
        int nameEnd = Math.min(message.length(), nameIndex + poi.getName().length());
        String after = message.substring(nameEnd).replaceFirst("^[，,。\\s]+", "");
        return before.matches(".*(不去|不想去|排除|取消).*")
                || after.matches("^(不去了|取消|不安排).*");
    }

    private void normalizePoiPreferences(TripRequestEntity entity, List<PoiEntity> allPois) {
        Set<String> excluded = new LinkedHashSet<>(csvValues(entity.getExcludedPoiIds()));
        List<String> cleanedMustSee = csvValues(entity.getMustSee()).stream()
                .filter(id -> !excluded.contains(id))
                .toList();
        entity.setMustSee(String.join(",", cleanedMustSee));

        Set<String> availableTags = allPois.stream()
                .flatMap(poi -> csvValues(poi.getTags()).stream())
                .collect(java.util.stream.Collectors.toSet());
        List<String> cleanedInterests = csvValues(entity.getInterestTags()).stream()
                .filter(availableTags::contains)
                .distinct()
                .limit(12)
                .toList();
        entity.setInterestTags(String.join(",", cleanedInterests));
    }

    private List<String> csvValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private double distanceKm(PoiEntity from, PoiEntity to) {
        return distance(from.getLatitude(), from.getLongitude(), to.getLatitude(), to.getLongitude()) / 1000.0;
    }

    private String resolveCity(String prompt) {
        return prompt.contains(defaultCity) ? defaultCity : null;
    }

    private Integer resolveDays(String prompt) {
        Pattern pattern = Pattern.compile("([1-9]|[一二两三四五六])\\s*天");
        Matcher matcher = pattern.matcher(prompt);
        if (!matcher.find()) {
            return null;
        }
        return chineseNumber(matcher.group(1));
    }

    private Integer resolvePeople(String prompt) {
        if (prompt.matches(".*(一个人|独自|自己一个人).*")) {
            return 1;
        }
        if (prompt.matches(".*(情侣|两个人|二人).*")) {
            return 2;
        }
        if (prompt.matches(".*(带父母|和爸妈|一家三口).*")) {
            return 3;
        }
        Pattern pattern = Pattern.compile("([1-9]|[一两二三四五六])\\s*(?:人|个人)");
        Matcher matcher = pattern.matcher(prompt);
        return matcher.find() ? chineseNumber(matcher.group(1)) : 2;
    }

    private Integer resolvePeopleStrict(String prompt) {
        Pattern pattern = Pattern.compile("([1-9]|[一两二三四五六])\\s*(?:人|个人)");
        Matcher matcher = pattern.matcher(prompt);
        return matcher.find() ? chineseNumber(matcher.group(1)) : null;
    }

    private BigDecimal resolveBudget(String prompt) {
        Pattern pattern = Pattern.compile("(?:预算|大概|约|一共)?\\s*(\\d{3,6})\\s*(?:元|块)");
        Matcher matcher = pattern.matcher(prompt);
        return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
    }

    private LocalDate resolveStartDate(String prompt) {
        LocalDate today = LocalDate.now(SHANGHAI);
        if (prompt.contains("后天")) {
            return today.plusDays(2);
        }
        if (prompt.contains("明天")) {
            return today.plusDays(1);
        }
        if (prompt.contains("下周末")) {
            int daysUntilSaturday = (6 - today.getDayOfWeek().getValue() + 7) % 7;
            return today.plusDays(daysUntilSaturday == 0 ? 7 : daysUntilSaturday);
        }
        if (prompt.contains("周末")) {
            int daysUntilSaturday = (6 - today.getDayOfWeek().getValue() + 7) % 7;
            return today.plusDays(daysUntilSaturday);
        }
        if (prompt.contains("下周")) {
            int daysUntilMonday = (8 - today.getDayOfWeek().getValue()) % 7;
            return today.plusDays(daysUntilMonday == 0 ? 7 : daysUntilMonday);
        }
        Pattern pattern = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日");
        Matcher matcher = pattern.matcher(prompt);
        if (!matcher.find()) {
            return null;
        }
        int year = matcher.group(1) == null ? today.getYear() : Integer.parseInt(matcher.group(1));
        LocalDate date = LocalDate.of(year, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
        if (matcher.group(1) == null && date.isBefore(today.minusDays(1))) {
            date = date.plusYears(1);
        }
        return date;
    }

    private List<PoiEntity> resolveMustSee(String prompt, List<PoiEntity> allPois) {
        List<PoiEntity> resolved = allPois.stream()
                .filter(poi -> prompt.contains(poi.getName())
                        || (poi.getAliasName() != null && !poi.getAliasName().isBlank() && prompt.contains(poi.getAliasName())))
                .sorted(Comparator.comparingInt(poi -> -Math.max(poi.getName().length(), poi.getAliasName() == null ? 0 : poi.getAliasName().length())))
                .toList();
        Set<String> ids = new HashSet<>();
        List<PoiEntity> unique = new ArrayList<>();
        for (PoiEntity poi : resolved) {
            if (ids.add(poi.getId())) {
                unique.add(poi);
            }
        }
        return unique;
    }

    private PoiEntity findPoi(String value, List<PoiEntity> allPois) {
        return allPois.stream()
                .filter(poi -> poi.getId().equals(value) || poi.getName().equals(value)
                        || (poi.getAliasName() != null && poi.getAliasName().equals(value)))
                .findFirst()
                .orElse(null);
    }

    private boolean mentionsPoi(String prompt, PoiEntity poi) {
        return prompt.contains(poi.getName())
                || (poi.getAliasName() != null && !poi.getAliasName().isBlank() && prompt.contains(poi.getAliasName()));
    }

    private String stripCodeFence(String value) {
        return value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
    }

    private double distance(double lat1, double lng1, double lat2, double lng2) {
        double earth = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earth * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private String buildTitle(String generated) {
        return generated;
    }

    private String shortName(String name) {
        return name.length() > 8 ? name.substring(0, 8) : name;
    }

    private String toChineseDays(int days) {
        return switch (days) {
            case 1 -> "一日";
            case 2 -> "两日";
            case 3 -> "三日";
            default -> days + "日";
        };
    }

    private int chineseNumber(String value) {
        return switch (value) {
            case "一" -> 1;
            case "两", "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            default -> Integer.parseInt(value);
        };
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
