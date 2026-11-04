---
docId: kb.route.old-town-culture
docType: ROUTE
version: 2
title: 上海老城厢人文主题路线
language: zh-CN
city: 上海
area: 上海
entityIds: [poi:yuyuan, poi:city-god, poi:tianzifang]
poiIds: [yuyuan, city-god, tianzifang]
factTypes: [ROUTE_THEME, SEQUENCE]
sourceTier: PUBLIC_EDITORIAL
sourceName: 旅行导游助手项目组自研行程编排 v1
sourceUrl: null
publishedAt: null
fetchedAt: 2026-10-29
observedAt: 2026-10-29T10:00:00+08:00
validFrom: 2026-10-29
validTo: null
reviewCycleDays: 90
accessScope: PUBLIC
provenanceStatus: INTERNAL_COMPOSITE
provenanceRef: [kb.poi.yuyuan.visit, kb.poi.city-god.visit, kb.poi.tianzifang.visit]
rawSnapshot: null
snapshotFormat: null
contentSha256: null
dataConfidence: DEMO
status: DRAFT
reviewer: null
supersedes: null
aliases: [老城厢路线, 豫园城隍庙路线, 上海老城人文]
tags: [人文, 老城厢, 园林, 街区, 半日]
---

## 主题与适合人群

围绕上海老城厢的一条人文路线：园林、庙宇、弄堂街区三种空间依次展开，正好对应"看空间、看信仰、看生活"三个层次。适合对江南园林、地方信仰与市井生活有兴趣的游客，也适合作为"非高楼上海"的补充行程。不适合推婴儿车或对拥挤敏感的人——老城厢街巷窄、客流集中。

## 站点序列

1. 豫园（poiId: yuyuan）。放在第一站，是因为它 09:00 开门、16:30 结束入场，园林本体必须在白天且越早越好；90 分钟按"门洞—漏窗—空间转折"的顺序看，避免只走主路。早到还能在人流上来之前看清园林尺度。
2. 城隍庙（poiId: city-god）。紧接着安排，是因为两处相邻、步行几分钟可达，且城隍庙 08:30 开放、16:30 结束，与豫园的时段重合，放在同一段最省交通；45 分钟看建筑并沿周边老街走一小圈。
3. 田子坊（poiId: tianzifang）。收在午后，是因为它是街区型空间、开放到 22:00，不受闭馆限制，可以自由接在庙宇之后；55 分钟走主巷与支巷，作为一天中节奏最松的一段。

## 时间预算

总时长约 4–4.5 小时，为估算值：

- 豫园 90 分钟（pois.json DEMO 基线）
- 城隍庙 45 分钟
- 田子坊 55 分钟
- 换乘：豫园至城隍庙约 5 分钟步行，城隍庙至田子坊约 25 分钟（步行或短程车行）

不含用餐；老城厢餐饮选择密集，具体店铺与营业时间资料未覆盖。豫园票务信息以官方为准。

## 交通方式

前两站步行串联，第三站需要短程移动。老城厢道路窄、单行线多，建议车行到外围后步行进入，不要在核心区寻找临时停车。田子坊巷道狭窄、出入口不同，需按现场指示单向行走。具体地铁站与出入口资料未覆盖，线路信息以官方为准。

## 雨天与闭馆替代

豫园为露天园林，降雨时体验下降，替换为上海博物馆人民广场馆（poiId: shanghai-museum，室内，09:00–17:00，150 分钟）；城隍庙为宗教场所，雨天可缩短停留，替换为中共一大纪念馆（poiId: first-congress，室内，09:00–17:00，90 分钟）；田子坊雨天巷道湿滑，可整体取消。若全天有雨，改走 kb.route.rainy-indoor。宗教场所礼仪与拍摄规定以现场为准，资料未覆盖。

## 不适合的情况

- 推婴儿车或轮椅：老城厢与田子坊巷道窄、门槛与台阶多。
- 对宗教场所礼仪不适应：城隍庙需遵守现场拍摄与行为规定。
- 下午才出发：豫园 16:30 结束入场，晚到只能看周边街区。
- 追求安静：三站均为高客流区域，安静时段资料未覆盖。

## 资料说明

来源：项目组自研编排（无外部来源，provenanceStatus=INTERNAL_COMPOSITE）｜ 派生文档：kb.poi.yuyuan.visit、kb.poi.city-god.visit、kb.poi.tianzifang.visit ｜ 观测时间：2026-10-29 ｜ 未覆盖：豫园票务与预约、城隍庙礼仪规定、街区店铺营业时间、地铁出入口；时长取自 pois.json 的 DEMO 基线，为估算值。
