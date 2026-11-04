---
docId: kb.route.city-walk
docType: ROUTE
version: 2
title: 上海梧桐街区城市漫步路线
language: zh-CN
city: 上海
area: 上海
entityIds: [poi:wukang-road, poi:suzhou-creek, poi:sinan-mansions]
poiIds: [wukang-road, suzhou-creek, sinan-mansions]
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
provenanceRef: [kb.poi.wukang-road.visit, kb.poi.suzhou-creek.visit, kb.poi.sinan-mansions.visit]
rawSnapshot: null
snapshotFormat: null
contentSha256: null
dataConfidence: DEMO
status: DRAFT
reviewer: null
supersedes: null
aliases: [上海城市漫步, 梧桐区散步, 街区漫步路线]
tags: [城市漫步, 街区, 建筑, 半日, 滨水]
---

## 主题与适合人群

一条以"走路看城市"为目的的路线：从西区梧桐街区走到苏州河滨水，再收在历史建筑街区。适合愿意用半天慢慢走、对街巷尺度与城市更新有兴趣的游客。不适合追求景点密度的人，也不适合需要连续遮阴避雨的人——三站几乎都是露天空间。

## 站点序列

1. 武康路（poiId: wukang-road）。放在上午，是因为街区以居住功能为主，早间安静、行人少，适合从武康路北端起步再向街区内走；80 分钟刚好，不必走完整条路。
2. 苏州河畔（poiId: suzhou-creek）。接在中午前后，是因为滨水步道全天开放、不需要卡时间点，可作为两个街区之间的一段移动；70 分钟只走一段连续步道，不追求走完整条河。
3. 思南公馆（poiId: sinan-mansions）。收在下午，是因为这一带适合慢节奏停留，pois.json 的定位也是"下午安排一段慢节奏停留"；60 分钟看建筑与树荫，最后以咖啡或休息结束，符合全天由动到静的节奏。

## 时间预算

总时长约 4.5–5 小时，为估算值：

- 武康路 80 分钟（pois.json DEMO 基线）
- 苏州河畔 70 分钟
- 思南公馆 60 分钟
- 换乘：武康路至苏州河约 25 分钟，苏州河至思南公馆约 25 分钟（步行或短程车行）

不含用餐；若在思南公馆坐下休息，实际结束时间会后移。街区停留时长与当天人流直接相关，节假日需加缓冲。

## 交通方式

以步行为主，跨区段用地铁或出租车，线路信息以官方为准；三段都适合"下车即走"，不需要固定入口。街区内部巷道多，路线选择按现场人流调整。具体地铁站与出口资料未覆盖，建议出发前按实时导航确认下车点。

## 雨天与闭馆替代

三站均为露天街区或滨水空间，降雨时体验下降明显，建议整体改走 kb.route.rainy-indoor。若只是短时小雨，可把武康路替换为宋庆龄故居（poiId: songqingling-residence，室内，09:00–16:30，60 分钟），把思南公馆替换为中共一大纪念馆（poiId: first-congress，室内，09:00–17:00，90 分钟）。街区与场馆的当日开放范围资料未覆盖。

## 不适合的情况

- 雨天或夏季正午暴晒：全程缺少连续的室内段落。
- 携带大件行李：街区人行道与老路面不适合拖行。
- 想购物或看展：本路线没有商业综合体与场馆站点。
- 时间少于 3.5 小时：三站之间的移动会占掉大半。

## 资料说明

来源：项目组自研编排（无外部来源，provenanceStatus=INTERNAL_COMPOSITE）｜ 派生文档：kb.poi.wukang-road.visit、kb.poi.suzhou-creek.visit、kb.poi.sinan-mansions.visit ｜ 观测时间：2026-10-29 ｜ 未覆盖：三个街区的当日开放范围、地铁站与出口、休息与寄存点；时长取自 pois.json 的 DEMO 基线，为估算值。
