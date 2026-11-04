package com.travelguide.service;

import com.travelguide.domain.PoiEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 将结构化景点资料编译为统一的讲解员口播稿。
 *
 * <p>每个景点都遵循固定结构：欢迎与看点、背景、现场观察、实用信息、礼仪和收束。
 * 只组合数据库中已有的内容，不为了文案完整而补造年代、藏品或票务事实。</p>
 */
@Component
public class GuideNarrativeComposer {

    public String compose(PoiEntity poi) {
        String guideText = text(poi.getGuideText());
        // 已有人工精修稿直接复用，避免二次拼接破坏叙事节奏。
        if (guideText.startsWith("各位朋友")) {
            return guideText;
        }

        List<String> paragraphs = new ArrayList<>();
        paragraphs.add("各位朋友，大家好！欢迎来到" + poi.getName() + "，我是今天的讲解员。"
                + "先别急着拍照，我们花一点时间，读懂眼前这个空间真正值得看的地方。");

        String background = joinSentences(poi.getSummary(), poi.getHistory());
        if (!background.isBlank()) {
            paragraphs.add(background);
        }
        if (!guideText.isBlank()) {
            paragraphs.add("走到现场，请大家留意：" + ensureSentence(guideText));
        }

        paragraphs.add(practicalInformation(poi));
        paragraphs.add(etiquette(poi));
        paragraphs.add("今天的讲解先告一段落。接下来的时间，不妨少赶一个点，多留几分钟给眼前的空间。"
                + "愿这次在" + poi.getName() + "的停留，成为你理解上海的一段清晰记忆。");
        return String.join("\n\n", paragraphs);
    }

    private String practicalInformation(PoiEntity poi) {
        List<String> details = new ArrayList<>();
        if (poi.getDurationMinutes() != null && poi.getDurationMinutes() > 0) {
            details.add("建议预留" + poi.getDurationMinutes() + "分钟");
        }
        details.add(opening(poi));
        details.add(ticket(poi));
        return "参观前先记住几个实用信息：" + String.join("；", details)
                + "。开放安排和临时活动可能调整，出发前请以官方当天信息为准。";
    }

    private String opening(PoiEntity poi) {
        String open = text(poi.getOpenTime());
        String close = text(poi.getCloseTime());
        if (open.isBlank()) {
            return "开放时间待确认";
        }
        if ("全天".equals(open) || "街区全天".equals(open) || "古镇全天".equals(open)) {
            return "这里全天可进入，内部场馆或体验项目以现场公告为准";
        }
        return close.isBlank()
                ? "参考开放时间" + open
                : "参考开放时间" + open + "至" + close;
    }

    private String ticket(PoiEntity poi) {
        BigDecimal price = poi.getTicketPrice();
        if (price == null || price.signum() == 0) {
            return "公共区域通常免费，临时展览或体验项目请以现场为准";
        }
        return "参考门票" + price.stripTrailingZeros().toPlainString()
                + "元，票种和临时展览价格以官方渠道为准";
    }

    private String etiquette(PoiEntity poi) {
        String name = text(poi.getName());
        String type = text(poi.getType());
        if (name.contains("寺") || name.contains("庙")) {
            return "进入宗教活动区域时请保持安静，遵守现场参拜、拍摄和着装提示，不打扰正在礼佛或参观的人。";
        }
        if ("MUSEUM".equals(type)) {
            return "参观时请勿触摸展品，留意闪光灯、三脚架和直播限制；遇到人流密集的展项，先让出通道再停下来细看。";
        }
        if ("GARDEN".equals(type) || "PARK".equals(type)) {
            return "请沿开放步道游览，不攀折花木、不进入封闭养护区域；带小朋友时提前约定集合点。";
        }
        if ("STREET".equals(type) || "HISTORY".equals(type)) {
            return "街区中仍有居民和商户日常使用，请控制音量、不堵住出入口，也不要把镜头长时间对准私人空间。";
        }
        if ("WATER_TOWN".equals(type) || "SCENIC".equals(type)) {
            return "临水和临江区域请留意脚下与现场围栏，不翻越护栏；旺季拍照时先让出主通道。";
        }
        return "请遵守现场指引，给其他游客和工作人员留出空间，让这段体验从容而有序。";
    }

    private String joinSentences(String first, String second) {
        List<String> values = new ArrayList<>();
        if (!text(first).isBlank()) {
            values.add(ensureSentence(first));
        }
        if (!text(second).isBlank() && !text(second).equals(text(first))) {
            values.add(ensureSentence(second));
        }
        return String.join("", values);
    }

    private String ensureSentence(String value) {
        String cleaned = text(value);
        if (cleaned.isEmpty()) {
            return "";
        }
        return cleaned.matches(".*[。！？!?]$") ? cleaned : cleaned + "。";
    }

    private String text(String value) {
        return value == null ? "" : value.strip();
    }
}
