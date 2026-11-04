package com.travelguide.service;

import com.travelguide.domain.GuideSessionEntity;
import com.travelguide.domain.PoiEntity;
import com.travelguide.repo.GuideSessionRepository;
import com.travelguide.repo.PoiRepository;
import com.travelguide.web.ApiDtos.AskResponse;
import com.travelguide.web.ApiDtos.GuideContent;
import com.travelguide.web.ApiDtos.GuideCreateRequest;
import com.travelguide.web.ApiDtos.GuideSessionView;
import com.travelguide.web.ApiDtos.LocationCandidate;
import com.travelguide.web.ApiDtos.LocationRequest;
import com.travelguide.web.ApiDtos.LocationResponse;
import com.travelguide.web.ApiDtos.PlanView;
import com.travelguide.web.ApiDtos.PoiView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 现场导游服务。
 *
 * <p>管理一次导游会话（当前景点、定位、问答）：上报定位后按球面距离找最近的行程景点
 * 并给出置信度，低置信度让用户手选；讲解内容按景点模板生成，
 * 追问优先交给 {@code AiGateway}，模型不可用时退回确定性回答，保证现场可用。</p>
 */
@Service
public class GuideService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final GuideSessionRepository guideRepository;
    private final PoiRepository poiRepository;
    private final PlanningService planningService;
    private final PoiMapper poiMapper;
    private final GroundedAnswerService groundedAnswerService;
    private final GuideNarrativeComposer guideNarrativeComposer;

    public GuideService(
            GuideSessionRepository guideRepository,
            PoiRepository poiRepository,
            PlanningService planningService,
            PoiMapper poiMapper,
            GroundedAnswerService groundedAnswerService,
            GuideNarrativeComposer guideNarrativeComposer
    ) {
        this.guideRepository = guideRepository;
        this.poiRepository = poiRepository;
        this.planningService = planningService;
        this.poiMapper = poiMapper;
        this.groundedAnswerService = groundedAnswerService;
        this.guideNarrativeComposer = guideNarrativeComposer;
    }

    @Transactional
    public GuideSessionView create(GuideCreateRequest request) {
        return create(request, null);
    }

    @Transactional
    public GuideSessionView create(GuideCreateRequest request, String userId) {
        PlanView plan = userId == null
                ? planningService.getPlan(request.planId())
                : planningService.getPlan(request.planId(), userId);
        GuideSessionEntity entity = new GuideSessionEntity();
        entity.setId("guide_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        entity.setUserId(userId);
        entity.setPlanId(plan.id());
        entity.setStatus("READY");
        entity.setCreatedAt(LocalDateTime.now(SHANGHAI));
        entity.setUpdatedAt(entity.getCreatedAt());
        guideRepository.save(entity);
        return new GuideSessionView(entity.getId(), entity.getPlanId(), entity.getStatus(), null);
    }

    public GuideSessionView get(String sessionId) {
        return get(sessionId, null);
    }

    public GuideSessionView get(String sessionId, String userId) {
        GuideSessionEntity session = requireSession(sessionId, userId);
        GuideContent content = session.getCurrentPoiId() == null ? null : buildContent(session);
        return new GuideSessionView(session.getId(), session.getPlanId(), session.getStatus(), content);
    }

    @Transactional
    public LocationResponse locate(String sessionId, LocationRequest request) {
        return locate(sessionId, request, null);
    }

    @Transactional
    public LocationResponse locate(
            String sessionId,
            LocationRequest request,
            String userId
    ) {
        GuideSessionEntity session = requireSession(sessionId, userId);
        PlanView plan = planningService.getPlan(session.getPlanId());
        Map<String, PoiEntity> allById = new LinkedHashMap<>();
        poiRepository.findAll().forEach(poi -> allById.put(poi.getId(), poi));
        List<PoiEntity> planPois = plan.mapPoints().stream()
                .map(point -> allById.get(point.poiId()))
                .filter(poi -> poi != null)
                .toList();
        List<LocationCandidate> candidates = planPois.stream()
                .map(poi -> {
                    double distance = distanceMeters(request.latitude(), request.longitude(), poi.getLatitude(), poi.getLongitude());
                    int confidence = confidence(distance, request.accuracy());
                    return new LocationCandidate(poiMapper.toView(poi), Math.round(distance), confidence);
                })
                .filter(candidate -> candidate.distanceMeters() <= 800)
                .sorted(Comparator.comparingDouble(LocationCandidate::distanceMeters))
                .limit(3)
                .toList();

        if (candidates.isEmpty()) {
            return new LocationResponse("NOT_FOUND", "附近没有找到本次行程中的景点，你可以从列表手动选择。", List.of());
        }
        LocationCandidate first = candidates.get(0);
        if (first.distanceMeters() <= 180 && first.confidence() >= 60) {
            return new LocationResponse("FOUND", "已识别到你在 " + first.poi().name() + " 附近。", candidates);
        }
        return new LocationResponse("NEARBY", "你在计划景点附近，请确认具体位置。", candidates);
    }

    @Transactional
    public GuideSessionView select(String sessionId, String poiId) {
        return select(sessionId, poiId, null);
    }

    @Transactional
    public GuideSessionView select(String sessionId, String poiId, String userId) {
        GuideSessionEntity session = requireSession(sessionId, userId);
        PoiEntity poi = poiRepository.findById(poiId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "景点不存在"));
        session.setCurrentPoiId(poi.getId());
        session.setStatus("ACTIVE");
        session.setUpdatedAt(LocalDateTime.now(SHANGHAI));
        guideRepository.save(session);
        return new GuideSessionView(session.getId(), session.getPlanId(), session.getStatus(), buildContent(session));
    }

    public AskResponse ask(String sessionId, String question) {
        return ask(sessionId, question, null);
    }

    public AskResponse ask(String sessionId, String question, String userId) {
        GuideSessionEntity session = requireSession(sessionId, userId);
        if (session.getCurrentPoiId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先选择一个景点");
        }
        GuideContent content = buildContent(session);
        String deterministic = deterministicAnswer(content, question);
        // 长短和语气控制属于当前讲解的展示操作，不应再进入无关知识检索。
        if (isNarrativeStyleRequest(question)) {
            return new AskResponse(
                    deterministic,
                    List.of("继续讲这个景点", "附近还能看什么")
            );
        }
        return groundedAnswerService.answer(
                userId,
                sessionId,
                content,
                question,
                deterministic
        );
    }

    public GuideContent buildContent(GuideSessionEntity session) {
        PoiEntity poi = poiRepository.findById(session.getCurrentPoiId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "当前景点不存在"));
        PlanView plan = planningService.getPlan(session.getPlanId());
        Map<String, PoiEntity> allById = new LinkedHashMap<>();
        poiRepository.findAll().forEach(item -> allById.put(item.getId(), item));
        List<PoiView> nearby = plan.mapPoints().stream()
                .filter(point -> !point.poiId().equals(poi.getId()))
                .map(point -> allById.get(point.poiId()))
                .filter(item -> item != null)
                .sorted(Comparator.comparingDouble(item -> distanceMeters(poi.getLatitude(), poi.getLongitude(), item.getLatitude(), item.getLongitude())))
                .limit(3)
                .map(poiMapper::toView)
                .toList();
        String opening = (poi.getOpenTime() == null || poi.getOpenTime().isBlank())
                ? "开放时间待确认"
                : poi.getOpenTime() + (poi.getCloseTime() == null || poi.getCloseTime().isBlank() ? "" : " — " + poi.getCloseTime());
        String ticket = poi.getTicketPrice() == null || poi.getTicketPrice().signum() == 0
                ? "免费或现场确认"
                : "参考票价 ¥" + poi.getTicketPrice().stripTrailingZeros().toPlainString();
        return new GuideContent(
                poi.getId(),
                poi.getName(),
                poi.getArea(),
                "此刻 · " + poi.getArea() + " · 现场漫游",
                guideNarrativeComposer.compose(poi),
                poi.getHistory(),
                opening + "（演示资料）",
                ticket + "（出发前确认）",
                poi.getSourceName(),
                poi.getSourceUrl(),
                poi.getDataConfidence(),
                poi.getLatitude(),
                poi.getLongitude(),
                nearby
        );
    }

    private String deterministicAnswer(GuideContent content, String question) {
        if (question.matches(".*(附近|还能看什么|下一站|旁边).*")) {
            String names = content.nearby().stream().map(PoiView::name).limit(2).collect(java.util.stream.Collectors.joining("、"));
            return names.isBlank()
                    ? "当前行程里没有更近的下一站，可以从手动景点列表重新选择。"
                    : "可以顺路看看 " + names + "。按你想要的轻松节奏，建议只选一个，不要把下一段排得太满。";
        }
        if (question.matches(".*(多久|时间|停留).*")) {
            return "建议在这里留出 60–90 分钟。先看最有代表性的空间，再根据体力和人流决定是否继续深入。";
        }
        if (question.matches(".*(门票|票价|多少钱|收费).*")) {
            return "当前记录是：" + content.ticket() + "。这是演示资料，出发前请再查看官方渠道。";
        }
        if (question.matches(".*(开放|营业|闭馆).*")) {
            return "当前记录是：" + content.opening() + "。MVP 暂未接入实时闭馆数据。";
        }
        if (question.matches(".*(历史|为什么|重要|故事).*")) {
            return content.history();
        }
        if (question.matches(".*(短|简洁|一句话).*")) {
            return shortNarrative(content);
        }
        return content.narrative();
    }

    private boolean isNarrativeStyleRequest(String question) {
        return question.matches(".*(更短|简短|简洁|短一点|讲短|一句话).*");
    }

    private String shortNarrative(GuideContent content) {
        if ("shanghai-museum".equals(content.poiId())) {
            return "人民广场馆当前只展“世界树之巅：美洲古代文明大展”，其他常设陈列在东馆。"
                    + "建议预留1.5到2.5小时，先完整走一遍展线，再回头细看最打动你的三五件展品。";
        }
        String[] sentences = content.narrative().split("(?<=[。！？])");
        StringBuilder result = new StringBuilder();
        for (String sentence : sentences) {
            String cleaned = sentence.strip();
            if (cleaned.isBlank() || cleaned.matches(".*(各位朋友|欢迎来到|我是今天的).*")) {
                continue;
            }
            result.append(cleaned);
            if (result.length() >= 60) {
                break;
            }
        }
        return result.isEmpty() ? content.narrative() : result.toString();
    }

    private GuideSessionEntity requireSession(String sessionId) {
        return requireSession(sessionId, null);
    }

    private GuideSessionEntity requireSession(String sessionId, String userId) {
        GuideSessionEntity session = guideRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "导游会话不存在"));
        if (userId != null && !userId.equals(session.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该导游会话");
        }
        return session;
    }

    private int confidence(double distance, Double accuracy) {
        double base = distance <= 80 ? 94 : distance <= 180 ? 84 : distance <= 350 ? 70 : 52;
        double penalty = accuracy == null ? 0 : Math.max(0, accuracy - 40) * 0.12;
        return (int) Math.max(20, Math.min(98, Math.round(base - penalty)));
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double earth = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earth * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
