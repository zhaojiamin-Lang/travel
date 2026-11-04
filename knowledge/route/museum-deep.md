---
docId: kb.route.museum-deep
docType: ROUTE
version: 2
title: 上海博物馆两馆深度一日路线
language: zh-CN
city: 上海
area: 上海
entityIds: [poi:shanghai-museum, poi:shanghai-museum-east]
poiIds: [shanghai-museum, shanghai-museum-east]
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
provenanceRef: [kb.poi.shanghai-museum.visit, kb.poi.shanghai-museum-east.visit]
rawSnapshot: null
snapshotFormat: null
contentSha256: null
dataConfidence: DEMO
status: DRAFT
reviewer: null
supersedes: null
aliases: [博物馆深度路线, 上博两馆一日, 上海博物馆一日]
tags: [博物馆, 人文, 室内, 一日]
---

## 主题与适合人群

一天看两座上海博物馆场馆：人民广场馆与东馆。适合对文博有明确兴趣、愿意在一日内接受较高信息密度与体力消耗的游客。不适合带幼儿，也不适合把博物馆当作"雨天备选"的人——两馆都建议各留半天，本路线是满负荷安排。

## 站点序列

1. 上海博物馆人民广场馆（poiId: shanghai-museum）。放在第一站，是因为它位于市中心、体量相对可控，09:00 开馆即入可以用完整上午建立展陈脉络；150 分钟，选两个主题展厅深入看。
2. 上海博物馆东馆（poiId: shanghai-museum-east）。放在下午，是因为东馆空间更大、参观时间更长，需要一段完整且不被打断的时间；同时它 17:00 闭馆，倒推 180 分钟参观时长，必须在 14:00 前入场，所以不能排在最后才去——这是两站顺序不能对调的原因。

## 时间预算

总时长约 6.5–7 小时，为估算值：

- 人民广场馆 150 分钟（pois.json DEMO 基线）
- 东馆 180 分钟
- 换乘：人民广场至浦东约 40–50 分钟（地铁），两馆进出与安检步行约 15 分钟

按 09:00 入场计算，人民广场馆约 11:30 结束，用餐后 13:00 出发，东馆可参观到 17:00 闭馆。若上午晚到 30 分钟，东馆段就要压缩。

## 交通方式

两馆分处黄浦与浦东，需要一次过江，建议地铁，线路信息以官方为准；东馆体量大，入场与安检排队时间资料未覆盖，建议预留 15 分钟缓冲。高峰时段出租车在过江通道上不占优势，不建议作为主方案。

## 雨天与闭馆替代

两馆均为室内，雨天不影响，本路线也可直接作为雨天全天方案。若其中一馆闭馆或预约不到，可替换为上海自然博物馆（poiId: natural-history，09:00–17:00，150 分钟）或中共一大纪念馆（poiId: first-congress，09:00–17:00，90 分钟）。两馆的闭馆日、预约名额与特展收费资料未覆盖。

## 不适合的情况

- 体力一般或带 10 岁以下儿童：两馆合计 330 分钟纯参观，中间只有一次用餐休息。
- 只想看某一件名品：两馆展厅多、动线长，不适合快速打卡。
- 出发时间晚于 11:00：东馆 17:00 闭馆，实际只能保留一馆。
- 当天要赶飞机或火车：结束时间贴近闭馆，没有缓冲余量。

## 资料说明

来源：项目组自研编排（无外部来源，provenanceStatus=INTERNAL_COMPOSITE）｜ 派生文档：kb.poi.shanghai-museum.visit、kb.poi.shanghai-museum-east.visit ｜ 观测时间：2026-10-29 ｜ 未覆盖：两馆闭馆日、预约名额与入馆规则、特展收费、地铁出口；时长取自 pois.json 的 DEMO 基线，为估算值。
