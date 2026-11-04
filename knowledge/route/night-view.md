---
docId: kb.route.night-view
docType: ROUTE
version: 2
title: 上海两岸夜景主题路线
language: zh-CN
city: 上海
area: 上海
entityIds: [poi:lujiazui, poi:oriental-pearl, poi:north-bund, poi:bund]
poiIds: [lujiazui, oriental-pearl, north-bund, bund]
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
provenanceRef: [kb.poi.lujiazui.visit, kb.poi.oriental-pearl.visit, kb.poi.north-bund.visit, kb.poi.bund.visit]
rawSnapshot: null
snapshotFormat: null
contentSha256: null
dataConfidence: DEMO
status: DRAFT
reviewer: null
supersedes: null
aliases: [上海夜景路线, 外滩夜景路线, 两岸夜景]
tags: [夜景, 江景, 观景, 半日, 初访]
---

## 主题与适合人群

一条从傍晚延续到夜间的两岸观景路线，顺序是"先在浦东看对岸、再上高处、再换到北岸、最后回到外滩"。适合体力尚可、能接受一次跨江移动、对城市灯光与天际线有兴趣的游客，也适合作为初访上海的"第一晚"。不适合带作息固定的幼儿，或把夜景理解为灯光表演的人——灯光开放时间资料未覆盖。

## 站点序列

1. 陆家嘴滨江（poiId: lujiazui）。放在天色未全暗时，先在浦东一侧看外滩历史立面，此时立面细节还能辨认；70 分钟足够走完一段滨江。
2. 东方明珠（poiId: oriental-pearl）。紧接着上塔，是因为它是本路线中唯一收费且需要排队的高空站，安排在日落前后可以同时看到白天与亮灯两种状态；120 分钟含排队缓冲（DEMO 基线 09:00–21:00）。
3. 北外滩滨江（poiId: north-bund）。过江后安排在北岸，是因为这里人流比外滩核心段松，能同时看到两岸灯光；60 分钟用于等天色完全变暗。
4. 外滩（poiId: bund）。最后回到外滩，是因为它全天开放、没有闭门风险，适合作为收尾；90 分钟走近距离看建筑立面与江面反光。

## 时间预算

总时长约 7–8 小时，为估算值（含换乘与排队缓冲）：

- 陆家嘴滨江 70 分钟
- 东方明珠 120 分钟
- 北外滩滨江 60 分钟
- 外滩 90 分钟
- 换乘：陆家嘴至东方明珠约 15 分钟步行，东方明珠至北外滩约 30 分钟（轮渡或车行），北外滩至外滩约 20 分钟

若只看夜景核心段（从东方明珠开始），可压缩到 4.5–5 小时。

## 交通方式

跨江是这条路线的关键：陆家嘴—北外滩—外滩之间需要过一次江再回到南岸，建议用轮渡或出租车，地铁换乘会额外消耗时间，线路信息以官方为准。两岸滨江段以步行为主。轮渡班次与末班时间资料未覆盖。

## 雨天与闭馆替代

小雨时把陆家嘴滨江改为朵云书院上海中心店（poiId: duoyun-bookstore，室内，10:30–20:00），把"隔江看外滩"换成"从高处看城市"。中雨以上时北外滩滨江与东方明珠户外平台体验明显下降，建议整体改为"朵云书院 + 外滩短段"，或直接改走雨天室内路线 kb.route.rainy-indoor。东方明珠与朵云书院的当日运营与限流安排资料未覆盖。

## 不适合的情况

- 带 6 岁以下儿童且作息固定：全程结束时间偏晚，跨江段没有休息点。
- 雨天或能见度低：高空与江边两段的核心体验都依赖视野。
- 恐高：东方明珠与朵云书院都涉及高处空间。
- 期待固定时间的灯光表演：本路线只写观景位，灯光开放与表演时间资料未覆盖。

## 资料说明

来源：项目组自研编排（无外部来源，provenanceStatus=INTERNAL_COMPOSITE）｜ 派生文档：kb.poi.lujiazui.visit、kb.poi.oriental-pearl.visit、kb.poi.north-bund.visit、kb.poi.bund.visit ｜ 观测时间：2026-10-29 ｜ 未覆盖：轮渡班次与末班时间、灯光开放时间、观景台限流与预约、各站夜间入场规则；时长取自 pois.json 的 DEMO 基线，为估算值。
