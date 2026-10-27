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
import com.travelguide.web.ApiDtos.PlanIssue;
import com.travelguide.web.ApiDtos.PlanItem;
import com.travelguide.web.ApiDtos.PlanView;
import com.travelguide.web.ApiDtos.PoiView;
import com.travelguide.web.ApiDtos.RequirementsUpdate;
import com.travelguide.web.ApiDtos.TransitView;
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

@Service
public class PlanningService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final List<List<String>> DAY_ROUTES = List.of(
            List.of("bund", "nanjing-road", "yuyuan", "lujiazui"),
            List.of("shanghai-museum", "sinan-mansions", "wukang-road", "xintiandi"),
            List.of("natural-history", "jingan-temple", "suzhou-creek", "zhangyuan")
    );

    private final PoiRepository poiRepository;
    private final TripRequestRepository requestRepository;
    private final TripPlanRepository planRepository;
    private final PoiMapper poiMapper;
    private final ObjectMapper objectMapper;
    private final AiGateway aiGateway;

    public PlanningService(
            PoiRepository poiRepository,
            TripRequestRepository requestRepository,
            TripPlanRepository planRepository,
            PoiMapper poiMapper,
            ObjectMapper objectMapper,
            AiGateway aiGateway
    ) {
        this.poiRepository = poiRepository;
        this.requestRepository = requestRepository;
        this.planRepository = planRepository;
        this.poiMapper = poiMapper;
        this.objectMapper = objectMapper;
        this.aiGateway = aiGateway;
    }

    @Transactional
    public DraftResponse createDraft(DraftRequest request) {
        String prompt = request.prompt().trim();
        List<PoiEntity> allPois = poiRepository.findAll();
        TripRequestEntity entity = new TripRequestEntity();
        entity.setId("req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        entity.setSessionId(blankToDefault(request.sessionId(), "anonymous"));
        entity.setRawPrompt(prompt);
        entity.setCity(resolveCity(prompt));
        entity.setDays(resolveDays(prompt));
        entity.setStartDate(resolveStartDate(prompt));
        entity.setPeople(resolvePeople(prompt));
        entity.setBudget(resolveBudget(prompt));
        entity.setPace(prompt.matches(".*(轻松|休闲|不想走太累|慢一点).*") ? "RELAXED" : "BALANCED");
        entity.setNoSpicy(prompt.matches(".*(不吃辣|不能吃辣|清淡).*"));
        List<PoiEntity> mustSee = resolveMustSee(prompt, allPois);
        entity.setMustSee(String.join(",", mustSee.stream().map(PoiEntity::getId).toList()));
        entity.setSummary(buildSummary(entity, mustSee));
        entity.setCreatedAt(LocalDateTime.now(SHANGHAI));

        if (entity.getDays() != null && entity.getStartDate() != null) {
            entity.setEndDate(entity.getStartDate().plusDays(entity.getDays() - 1L));
        }

        requestRepository.save(entity);
        return toDraft(entity, allPois);
    }

    @Transactional
    public DraftResponse updateRequirements(String requestId, RequirementsUpdate update) {
        TripRequestEntity entity = requireRequest(requestId);
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
        Map<String, PoiEntity> poiById = new LinkedHashMap<>();
        allPois.forEach(poi -> poiById.put(poi.getId(), poi));
        Set<String> used = new LinkedHashSet<>();
        List<DayPlan> days = new ArrayList<>();
        List<PlanIssue> issues = new ArrayList<>();
        int maxPoisPerDay = "RELAXED".equals(request.getPace()) ? 3 : 4;
        List<PoiEntity> mustSee = mustSeeEntities(request);
        int mustIndex = 0;

        for (int day = 0; day < request.getDays(); day++) {
            List<PoiEntity> dayPois = new ArrayList<>();
            while (mustIndex < mustSee.size() && dayPois.size() < Math.min(2, maxPoisPerDay)) {
                PoiEntity poi = mustSee.get(mustIndex++);
                if (used.add(poi.getId())) {
                    dayPois.add(poi);
                }
            }
            List<String> preferred = DAY_ROUTES.get(day % DAY_ROUTES.size());
            for (String id : preferred) {
                if (dayPois.size() >= maxPoisPerDay) {
                    break;
                }
                PoiEntity poi = poiById.get(id);
                if (poi != null && used.add(poi.getId())) {
                    dayPois.add(poi);
                }
            }
            for (PoiEntity poi : allPois) {
                if (dayPois.size() >= maxPoisPerDay) {
                    break;
                }
                if (used.add(poi.getId())) {
                    dayPois.add(poi);
                }
            }
            LocalDate date = request.getStartDate().plusDays(day);
            days.add(buildDay(day + 1, date, request, dayPois, issues));
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
        String title = "上海 · " + ("RELAXED".equals(request.getPace()) ? "松弛" : "城市") + toChineseDays(request.getDays()) + "漫游";
        String subtitle = "从江岸天际线走到梧桐街区，把时间留给真正值得停留的地方。";
        PlanView plan = new PlanView(
                "plan_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                request.getId(),
                1,
                title,
                subtitle,
                "上海",
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

        for (PoiEntity poi : pois) {
            int duration = poi.getDurationMinutes() == null ? 60 : poi.getDurationMinutes();
            int plannedTransit = previous == null ? 0 : 24;
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
                        "本地本帮菜午餐",
                        "避开重辣，优先选择可现场排队的餐馆",
                        lunchStart
                );
                items.add(lunch);
                cursor = lunchStart.plusMinutes(60);
                lunchAdded = true;
                previous = lunch;
            }
            int transitMinutes = previous == null ? 0 : (("MEAL".equals(previous.type())) ? 15 : 24);
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
                    previous == null ? null : new TransitView("WALK", "步行 / 地铁衔接", transitMinutes)
            );
            items.add(item);
            previous = item;
            cursor = end;
        }

        if (!lunchAdded) {
            LocalTime lunchStart = cursor.isBefore(LocalTime.of(12, 10)) ? LocalTime.of(12, 10) : cursor.plusMinutes(15);
            items.add(mealItem(dayNumber, sequence, request, "本帮风味午餐", "避开重辣，留出 1 小时用餐", lunchStart));
        }

        String theme = switch (dayNumber % 3) {
            case 1 -> "江岸与老城 · 第一眼上海";
            case 2 -> "人文与街区 · 城市慢行";
            default -> "建筑与生活 · 向外延伸";
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
                .filter(poi -> Set.of("bund", "shanghai-museum", "wukang-road", "yuyuan", "natural-history").contains(poi.getId()))
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
                missingFields(entity),
                entity.getSummary(),
                suggestions
        );
    }

    private String buildSummary(TripRequestEntity entity, List<PoiEntity> mustSee) {
        String names = mustSee.isEmpty() ? "城市代表景点" : mustSee.stream().map(PoiEntity::getName).collect(java.util.stream.Collectors.joining("、"));
        String pace = "RELAXED".equals(entity.getPace()) ? "轻松节奏" : "平衡节奏";
        return "上海 " + (entity.getDays() == null ? "待定" : entity.getDays()) + " 天 · " + pace + " · " + names;
    }

    private String resolveCity(String prompt) {
        return prompt.contains("上海") ? "上海" : "上海";
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
        Pattern pattern = Pattern.compile("([1-9]|[一两二三四五六])\\s*(?:人|个人)");
        Matcher matcher = pattern.matcher(prompt);
        return matcher.find() ? chineseNumber(matcher.group(1)) : 2;
    }

    private BigDecimal resolveBudget(String prompt) {
        Pattern pattern = Pattern.compile("(?:预算|大概|约|一共)?\\s*(\\d{3,6})\\s*(?:元|块)");
        Matcher matcher = pattern.matcher(prompt);
        return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
    }

    private LocalDate resolveStartDate(String prompt) {
        Pattern pattern = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日");
        Matcher matcher = pattern.matcher(prompt);
        if (!matcher.find()) {
            return null;
        }
        LocalDate today = LocalDate.now(SHANGHAI);
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
