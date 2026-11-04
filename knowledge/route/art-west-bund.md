---
docId: kb.route.art-west-bund
docType: ROUTE
version: 2
title: 上海西岸艺术主题路线
language: zh-CN
city: 上海
area: 上海
entityIds: [poi:west-bund-museum, poi:long-museum, poi:power-station-art]
poiIds: [west-bund-museum, long-museum, power-station-art]
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
provenanceRef: [kb.poi.west-bund-museum.visit, kb.poi.long-museum.visit, kb.poi.power-station-art.visit]
rawSnapshot: null
snapshotFormat: null
contentSha256: null
dataConfidence: DEMO
status: DRAFT
reviewer: null
supersedes: null
aliases: [西岸艺术路线, 上海艺术路线, 美术馆路线]
tags: [艺术, 展览, 室内, 半日, 建筑]
---

## 主题与适合人群

西岸两馆加南岸一站，是一条按"艺术机构密度"而不是按地理最短路径编排的路线。适合对当代艺术、工业建筑改造有兴趣的游客，也适合已经看过传统景点、想换一种城市体验的复访者。不适合把"看展"理解为"看名画常设展"的人——三站以当代艺术与空间为主，具体展品随档期变化。

## 站点序列

1. 西岸美术馆（poiId: west-bund-museum）。放在第一站，是因为它与下一站同在徐汇滨江、步行可达，先把同区域的馆集中看完，减少跨区移动；100 分钟，10:00 开馆即入。
2. 龙美术馆西岸馆（poiId: long-museum）。紧接着安排，是因为两馆距离近，且龙美术馆以工业遗迹结构与当代艺术的对照为特色，在刚看完前一个馆的空间经验之后立刻对比，观看效率更高；90 分钟。
3. 上海当代艺术博物馆（poiId: power-station-art）。放在最后，是因为它 11:00 开馆、19:00 闭馆，是全线闭门最晚的一站，即使前两站超时也不影响；100 分钟。

## 时间预算

总时长约 5.5–6 小时，为估算值：

- 西岸美术馆 100 分钟（pois.json DEMO 基线）
- 龙美术馆西岸馆 90 分钟
- 上海当代艺术博物馆 100 分钟
- 换乘：西岸两馆之间约 10 分钟步行，龙美术馆至当代艺术博物馆约 30 分钟（车行或地铁）

不含用餐；若每馆都看特展，需各加 20–30 分钟。三馆的票价与特展收费资料未覆盖，需按当期公告确认。

## 交通方式

西岸段以滨江步行为主，两馆之间步行即可；从西岸到南岸的当代艺术博物馆需要一次过江方向的车行或地铁，线路信息以官方为准，建议避开傍晚高峰。滨江段的遮阴与休息点资料未覆盖，夏季需自行控制步行时间。

## 雨天与闭馆替代

三站均为室内场馆，小雨不影响，只需要缩短滨江步行段。若某馆闭馆或当期没有开放展览，可替换为上海博物馆东馆（poiId: shanghai-museum-east，室内）或中共一大纪念馆（poiId: first-congress，室内）。各馆闭馆日、特展档期与单独收费资料未覆盖，出发前需确认。

## 不适合的情况

- 对当代艺术没有兴趣：三站内容方向一致，不适合作为"第一次看上海"的主线。
- 带幼儿：展厅以静观为主，缺少互动内容。
- 想看历史文物或书画常设展：应改走博物馆路线 kb.route.museum-deep。
- 只有 3 小时：西岸两馆之外再加第三站会变成赶场。

## 资料说明

来源：项目组自研编排（无外部来源，provenanceStatus=INTERNAL_COMPOSITE）｜ 派生文档：kb.poi.west-bund-museum.visit、kb.poi.long-museum.visit、kb.poi.power-station-art.visit ｜ 观测时间：2026-10-29 ｜ 未覆盖：三馆闭馆日、当期展览与特展收费、预约方式、滨江休息点；时长取自 pois.json 的 DEMO 基线，为估算值。
