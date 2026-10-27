<!--
  模块：行程详情（views/PlanView.vue）
  职责：一份行程的「主控台」，把后端能力全摆在一个页面。
  展示：按天的时间轴（景点/用餐/交通）、预算与校验问题、路线地图；
  操作：自然语言改需求并重新生成、切换历史版本与回滚、
        查看单点备选并替换、一键进入现场导游。
  对应接口：getPlan / getVersions / restoreVersion / updateFromNaturalLanguage /
           generatePlan / getAlternatives / swapPoi / createGuide。
-->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  ArrowRight,
  CalendarDays,
  CheckCircle2,
  ChevronRight,
  Clock3,
  Coffee,
  Compass,
  ExternalLink,
  Footprints,
  Gauge,
  History,
  Info,
  Layers3,
  Map as MapIcon,
  MapPin,
  Navigation,
  RefreshCw,
  RotateCcw,
  Route,
  Shuffle,
  Sparkles,
  UserRound,
  WalletCards,
  X,
} from 'lucide-vue-next'
import RouteMap from '../components/RouteMap.vue'
import SiteHeader from '../components/SiteHeader.vue'
import { api } from '../services/api'
import { useTripStore } from '../services/tripStore'
import type { DayPlan, Plan, PlanItem, Poi } from '../types'

const route = useRoute()
const router = useRouter()
const tripStore = useTripStore()
const loading = ref(true)
const error = ref('')
const plan = ref<Plan | null>(null)
const activeDayNumber = ref(1)
const versions = ref<Plan[]>([])
const versionsOpen = ref(false)
const refineText = ref('')
const refining = ref(false)
const refreshLoading = ref(false)
const swapTarget = ref<PlanItem | null>(null)
const alternatives = ref<Poi[]>([])
const swapping = ref(false)
const feedback = ref('')

const activeDay = computed<DayPlan | undefined>(() => {
  return plan.value?.dayPlans.find((day) => day.dayNumber === activeDayNumber.value)
})

const budgetRatio = computed(() => {
  if (!plan.value) return 0
  return Math.min(100, Math.round((plan.value.estimatedCost / plan.value.budget) * 100))
})

const poiCount = computed(() => {
  return plan.value?.mapPoints.length || 0
})

onMounted(async () => {
  try {
    const planId = String(route.params.planId)
    try {
      plan.value = tripStore.plan?.id === planId ? tripStore.plan : await api.getPlan(planId)
    } catch (requestError) {
      plan.value = tripStore.cachedPlan(planId)
      if (!plan.value) throw requestError
      feedback.value = '当前为本地缓存的行程，动态信息可能不是最新。'
    }
    tripStore.loadPlan(plan.value)
    versions.value = await api.getVersions(plan.value.requestId).catch(() => [plan.value as Plan])
    if (!tripStore.bootstrap) {
      api.bootstrap().then((data) => {
        tripStore.bootstrap = data
      })
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '没有找到这份行程'
  } finally {
    loading.value = false
  }
})

function formatDate(value?: string, withYear = false) {
  if (!value) return ''
  const date = new Date(`${value}T12:00:00`)
  return date.toLocaleDateString('zh-CN', {
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    ...(withYear ? { year: 'numeric' } : {}),
  })
}

function formatMoney(value: number) {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(value)
}

function itemInitial(item: PlanItem) {
  return item.name.replace(/上海|人民广场馆|步行街|古镇|滨江/, '').slice(0, 1) || '行'
}

function stopNumber(index: number) {
  return activeDay.value?.items.slice(0, index + 1).filter((item) => item.type === 'POI').length || 1
}

function applyPlan(next: Plan) {
  plan.value = next
  tripStore.rememberPlan(next)
  activeDayNumber.value = Math.min(activeDayNumber.value, next.days)
  router.replace({ name: 'plan', params: { planId: next.id } })
  versions.value = [next, ...versions.value.filter((version) => version.id !== next.id)]
}

async function openVersions() {
  if (!plan.value) return
  versionsOpen.value = true
  versions.value = await api.getVersions(plan.value.requestId).catch(() => versions.value)
}

async function restoreVersion(version: Plan) {
  if (!plan.value || refreshLoading.value) return
  refreshLoading.value = true
  try {
    const restored = await api.restoreVersion(version.id)
    applyPlan(restored)
    versionsOpen.value = false
    feedback.value = `已恢复版本 v${version.version}，并创建新的 v${restored.version}。`
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '版本恢复失败'
  } finally {
    refreshLoading.value = false
  }
}

async function refinePlan() {
  if (!plan.value || !refineText.value.trim() || refining.value) return
  refining.value = true
  error.value = ''
  try {
    const updated = await api.updateFromNaturalLanguage(plan.value.requestId, refineText.value)
    if (updated.missingFields.length) {
      error.value = '还需要补充：' + updated.missingFields.join('、')
      return
    }
    const next = await api.generatePlan(updated.requestId)
    applyPlan(next)
    refineText.value = ''
    versions.value = await api.getVersions(next.requestId)
    feedback.value = '已根据你的修改生成新版本。'
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '修改行程失败'
  } finally {
    refining.value = false
  }
}

async function openSwap(item: PlanItem) {
  if (!plan.value || !item.poiId) return
  swapTarget.value = item
  alternatives.value = []
  try {
    alternatives.value = await api.getAlternatives(plan.value.id, item.id)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '没有找到可替换景点'
  }
}

async function replacePoi(poi: Poi) {
  if (!plan.value || !swapTarget.value || swapping.value) return
  swapping.value = true
  try {
    const next = await api.swapPoi(plan.value.id, swapTarget.value.id, poi.id)
    applyPlan(next)
    swapTarget.value = null
    feedback.value = `已将当前位置替换为 ${poi.name}。`
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '替换失败'
  } finally {
    swapping.value = false
  }
}

async function startGuide(poiId?: string) {
  if (!plan.value) return
  try {
    const session = await api.createGuide(plan.value.id)
    localStorage.setItem('guide-session-id', session.id)
    await router.push({
      name: 'guide',
      params: { planId: plan.value.id },
      query: poiId ? { poi: poiId } : {},
    })
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '无法打开导游'
  }
}

function goHome() {
  router.push('/')
}
</script>

<template>
  <div class="plan-page">
    <div class="plan-page__top">
      <SiteHeader light :show-nav="false" action-label="重新规划" @action="goHome" />
    </div>

    <div v-if="loading" class="plan-loading">
      <div class="loading-orbit"><Compass :size="30" /></div>
      <p>正在整理你的城市漫游...</p>
    </div>

    <div v-else-if="error || !plan" class="plan-error page-shell">
      <span><Info :size="23" /></span>
      <h1>这份行程暂时走丢了</h1>
      <p>{{ error || '请返回首页重新生成一份行程。' }}</p>
      <button class="primary-button" type="button" @click="goHome">
        重新规划 <ArrowRight :size="17" />
      </button>
    </div>

    <template v-else>
      <section class="plan-hero">
        <div class="plan-hero__shape plan-hero__shape--one"></div>
        <div class="plan-hero__shape plan-hero__shape--two"></div>
        <div class="plan-hero__inner page-shell">
          <button class="back-link" type="button" @click="goHome">
            <ArrowLeft :size="15" /> 返回旅程
          </button>
          <div class="plan-hero__main">
            <div class="plan-hero__copy">
              <p class="hero-kicker">YOUR SHANGHAI STORY</p>
              <h1>{{ plan.title }}</h1>
              <p class="plan-hero__summary">{{ plan.subtitle }}</p>
              <div class="plan-meta">
                <span><CalendarDays :size="15" />{{ formatDate(plan.startDate, true) }} 出发</span>
                <span><Clock3 :size="15" />{{ plan.days }} 天 {{ plan.days - 1 }} 晚</span>
                <span><UserRound :size="15" />{{ plan.people }} 人同行</span>
                <span><Gauge :size="15" />{{ plan.pace === 'RELAXED' ? '轻松节奏' : '平衡节奏' }}</span>
              </div>
            </div>

            <div class="plan-hero__stamp">
              <span class="plan-hero__stamp-top">TRIP No. {{ plan.id.slice(-4).toUpperCase() }}</span>
              <div class="plan-hero__sun"></div>
              <svg viewBox="0 0 300 160" aria-hidden="true">
                <path class="skyline-far" d="M0 128V85H31V128M45 128V66H73V128M88 128V94H110V128M211 128V74H239V128M251 128V48H274V128" />
                <path class="skyline-near" d="M119 128V91H151V128M163 128V61H189V128" />
                <path class="hero-tower" d="M186 128V30H193V13H204V30H211V128" />
              </svg>
              <p>31°14′N · 121°29′E</p>
              <span>SHANGHAI FIELD NOTES</span>
            </div>
          </div>

          <div class="plan-hero__bottom">
            <div>
              <span>本次旅行</span>
              <strong>{{ poiCount }} 个目的地</strong>
            </div>
            <div>
              <span>预计花费</span>
              <strong>¥{{ formatMoney(plan.estimatedCost) }}</strong>
            </div>
            <div>
              <span>剩余预算</span>
              <strong>¥{{ formatMoney(Math.max(0, plan.budget - plan.estimatedCost)) }}</strong>
            </div>
            <div class="plan-hero__engine">
              <Sparkles :size="14" />
              {{ plan.generatedBy }}
            </div>
          </div>
        </div>
      </section>

      <main class="plan-main page-shell">
        <div v-if="plan.issues.some((issue) => issue.severity !== 'INFO')" class="issue-banner">
          <Info :size="17" />
          <span>{{ plan.issues.find((issue) => issue.severity !== 'INFO')?.message }}</span>
        </div>
        <div v-if="feedback" class="feedback-banner">
          <CheckCircle2 :size="16" />
          <span>{{ feedback }}</span>
          <button type="button" aria-label="关闭提示" @click="feedback = ''"><X :size="14" /></button>
        </div>

        <div class="plan-layout">
          <section class="itinerary">
            <div class="plan-toolbar">
              <button type="button" @click="openVersions">
                <History :size="15" /> 版本历史
                <span>v{{ plan.version }}</span>
              </button>
              <span>{{ versions.length }} 个版本保留</span>
            </div>
            <div class="day-tabs" role="tablist" aria-label="选择行程日期">
              <button
                v-for="day in plan.dayPlans"
                :key="day.dayNumber"
                type="button"
                :class="{ active: day.dayNumber === activeDayNumber }"
                @click="activeDayNumber = day.dayNumber"
              >
                <span>{{ day.label }}</span>
                <strong>{{ formatDate(day.date) }}</strong>
              </button>
            </div>

            <div v-if="activeDay" class="day-content">
              <div class="day-heading">
                <div>
                  <p class="eyebrow">Day {{ activeDay.dayNumber }} field notes</p>
                  <h2>{{ activeDay.title }}</h2>
                  <p>{{ activeDay.summary }}</p>
                </div>
                <div class="day-heading__mark">
                  <span>{{ String(activeDay.dayNumber).padStart(2, '0') }}</span>
                  <small>DAY</small>
                </div>
              </div>

              <div class="timeline">
                <template v-for="(item, index) in activeDay.items" :key="item.id">
                  <div v-if="item.transitFromPrevious" class="transit-row">
                    <span>{{ item.transitFromPrevious.minutes }} MIN</span>
                    <div class="transit-line">
                      <Footprints :size="14" />
                      <span>{{ item.transitFromPrevious.label }}</span>
                    </div>
                  </div>

                  <article v-if="item.type === 'MEAL'" class="meal-card">
                    <div class="time-column">
                      <strong>{{ item.startTime }}</strong>
                      <span>{{ item.endTime }}</span>
                    </div>
                    <span class="timeline-dot timeline-dot--meal"><Coffee :size="15" /></span>
                    <div class="meal-card__body">
                      <span>TASTE OF THE CITY</span>
                      <h3>{{ item.name }}</h3>
                      <p>{{ item.note }}</p>
                      <strong>预计 ¥{{ formatMoney(item.cost) }}</strong>
                    </div>
                  </article>

                  <article
                    v-else
                    class="place-stop"
                    :style="{ '--accent': item.accent }"
                  >
                    <div class="time-column">
                      <strong>{{ item.startTime }}</strong>
                      <span>{{ item.endTime }}</span>
                      <small>{{ item.durationMinutes }} MIN</small>
                    </div>
                    <span class="timeline-dot">
                      <small>{{ stopNumber(index) }}</small>
                    </span>
                    <div class="place-stop__content">
                      <div class="place-stop__art" aria-hidden="true">
                        <span class="art-orbit art-orbit--one"></span>
                        <span class="art-orbit art-orbit--two"></span>
                        <span class="art-route"></span>
                        <span class="art-initial">{{ itemInitial(item) }}</span>
                        <span class="art-area">{{ item.area }}</span>
                      </div>
                      <div class="place-stop__copy">
                        <div class="place-stop__top">
                          <span>{{ item.area }} · {{ item.type === 'POI' ? '精选停留' : item.type }}</span>
                          <span>¥{{ formatMoney(item.cost) }}</span>
                        </div>
                        <h3>{{ item.name }}</h3>
                        <p>{{ item.note }}</p>
                        <div class="place-stop__actions">
                          <button type="button" @click="startGuide(item.poiId)">
                            <Navigation :size="14" /> 在这里开始讲解
                          </button>
                          <button type="button" @click="openSwap(item)">
                            <Shuffle :size="14" /> 换一个
                          </button>
                          <a
                            v-if="item.sourceUrl"
                            :href="item.sourceUrl"
                            target="_blank"
                            rel="noreferrer"
                          >
                            资料来源 <ExternalLink :size="12" />
                          </a>
                        </div>
                      </div>
                    </div>
                  </article>
                </template>
              </div>
            </div>
          </section>

          <aside class="plan-sidebar">
            <section class="map-card">
              <div class="panel-heading">
                <span><MapIcon :size="16" /> 行程地图</span>
                <small>示意图</small>
              </div>
              <div class="map-card__canvas">
                <RouteMap
                  :points="plan.mapPoints"
                  :map-key="tripStore.bootstrap?.mapKey"
                  :active-poi-id="activeDay?.items.find((item) => item.type === 'POI')?.poiId"
                />
              </div>
              <button class="primary-button map-card__button" type="button" @click="startGuide()">
                <Navigation :size="16" /> 打开现场导游
              </button>
            </section>

            <section class="budget-card">
              <div class="panel-heading">
                <span><WalletCards :size="16" /> 预算感知</span>
                <small>{{ budgetRatio }}%</small>
              </div>
              <div class="budget-numbers">
                <strong>¥{{ formatMoney(plan.estimatedCost) }}</strong>
                <span>预算 ¥{{ formatMoney(plan.budget) }}</span>
              </div>
              <div class="budget-track">
                <span :style="{ width: `${budgetRatio}%` }"></span>
              </div>
              <p>已按 {{ plan.people }} 人估算门票、餐饮与市内交通。</p>
            </section>

            <section class="refine-card">
              <div class="panel-heading">
                <span><RefreshCw :size="16" /> 调整这份行程</span>
                <small>生成新版本</small>
              </div>
              <p>直接说，例如“第二天轻松一点”或“预算改成 2800”。</p>
              <form @submit.prevent="refinePlan">
                <input v-model="refineText" placeholder="写下你的调整..." maxlength="120" />
                <button type="submit" :disabled="refining || !refineText.trim()">
                  <span v-if="refining" class="spinner"></span>
                  <ArrowRight v-else :size="16" />
                </button>
              </form>
              <div class="refine-chips">
                <button type="button" @click="refineText = '第二天轻松一点'">第二天轻松点</button>
                <button type="button" @click="refineText = '预算改成 2800 元'">降低预算</button>
              </div>
            </section>

            <section class="verified-card">
              <div class="verified-card__icon">
                <CheckCircle2 :size="22" />
              </div>
              <div>
                <span>规则校验已完成</span>
                <h3>{{ plan.passed ? '这份行程可以出发' : '有需要你确认的项目' }}</h3>
                <p>
                  时间、预算、必去地点与开放信息均已通过基础校验。
                  标有“演示资料”的信息请在出发前再次确认。
                </p>
              </div>
            </section>

            <section class="tips-card">
              <span>WANDER NOTE</span>
              <p class="serif">“旅行不是完成一张清单，而是在对的时刻，停下来。” </p>
              <div>
                <Route :size="15" />
                <span>轻松模式 · 每天最多 3 个主要停留</span>
              </div>
            </section>
          </aside>
        </div>
      </main>

      <section class="next-chapter page-shell">
        <div>
          <p class="eyebrow">Ready when you are</p>
          <h2>抵达之后，<br />把故事听得更深一点。</h2>
        </div>
        <button class="primary-button" type="button" @click="startGuide()">
          开始现场导游 <ChevronRight :size="17" />
        </button>
      </section>

      <Transition name="sheet">
        <div v-if="versionsOpen" class="dialog-backdrop" @click.self="versionsOpen = false">
          <section class="versions-dialog">
            <header>
              <div>
                <p class="eyebrow">Version history</p>
                <h2>这份行程走过的版本</h2>
              </div>
              <button type="button" aria-label="关闭" @click="versionsOpen = false"><X :size="18" /></button>
            </header>
            <div class="versions-list">
              <article
                v-for="version in versions"
                :key="version.id"
                :class="{ current: version.id === plan.id }"
              >
                <span class="version-number">v{{ version.version }}</span>
                <div>
                  <strong>{{ version.generatedBy }}</strong>
                  <p>{{ version.dayPlans.length }} 天 · ¥{{ formatMoney(version.estimatedCost) }} · {{ version.passed ? '校验通过' : '有待确认项' }}</p>
                </div>
                <span v-if="version.id === plan.id" class="version-current">当前</span>
                <button v-else type="button" :disabled="refreshLoading" @click="restoreVersion(version)">
                  <RotateCcw :size="13" /> 恢复
                </button>
              </article>
            </div>
          </section>
        </div>
      </Transition>

      <Transition name="sheet">
        <div v-if="swapTarget" class="dialog-backdrop" @click.self="swapTarget = null">
          <section class="swap-dialog">
            <header>
              <div>
                <p class="eyebrow">Change the scene</p>
                <h2>换掉「{{ swapTarget.name }}」</h2>
                <p>优先推荐同区域、位置更近的景点。</p>
              </div>
              <button type="button" aria-label="关闭" @click="swapTarget = null"><X :size="18" /></button>
            </header>
            <div v-if="alternatives.length" class="swap-grid">
              <button
                v-for="poi in alternatives"
                :key="poi.id"
                type="button"
                :disabled="swapping"
                :style="{ '--swap-accent': poi.accent }"
                @click="replacePoi(poi)"
              >
                <span class="swap-art">{{ poi.name.slice(0, 1) }}</span>
                <span>
                  <small>{{ poi.area }} · {{ poi.durationMinutes }} MIN</small>
                  <strong>{{ poi.name }}</strong>
                  <em>{{ poi.summary }}</em>
                </span>
                <ArrowRight :size="15" />
              </button>
            </div>
            <div v-else class="swap-empty">
              <span class="spinner spinner--dark"></span>
              <p>正在寻找合适替代...</p>
            </div>
          </section>
        </div>
      </Transition>
    </template>
  </div>
</template>

<style scoped>
.plan-page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 95% 44%, rgba(207, 188, 153, 0.18), transparent 24%),
    var(--cream);
}

.plan-page__top {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  z-index: 10;
}

.plan-loading,
.plan-error {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  flex-direction: column;
}

.loading-orbit {
  display: grid;
  width: 72px;
  height: 72px;
  place-items: center;
  border: 1px solid rgba(41, 83, 65, 0.2);
  border-radius: 50%;
  color: var(--forest);
  animation: spin 3s linear infinite;
}

.plan-loading p {
  margin-top: 20px;
  color: var(--muted);
  font-size: 12px;
  letter-spacing: 0.08em;
}

.plan-error > span {
  display: grid;
  width: 56px;
  height: 56px;
  place-items: center;
  border-radius: 50%;
  background: rgba(201, 103, 67, 0.1);
  color: var(--clay);
}

.plan-error h1 {
  margin: 20px 0 9px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 40px;
}

.plan-error p {
  margin: 0 0 22px;
  color: var(--muted);
  font-size: 12px;
}

.plan-hero {
  position: relative;
  min-height: 570px;
  padding-top: 88px;
  overflow: hidden;
  background:
    linear-gradient(120deg, rgba(13, 49, 37, 0.98), rgba(33, 77, 60, 0.96)),
    var(--forest);
  color: #fff;
}

.plan-hero::before {
  position: absolute;
  inset: 0;
  opacity: 0.22;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.08) 1px, transparent 1px);
  background-size: 74px 74px;
  mask-image: linear-gradient(90deg, #000, transparent 80%);
  content: "";
}

.plan-hero__shape {
  position: absolute;
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: 50%;
}

.plan-hero__shape--one {
  top: 50px;
  right: 6%;
  width: 470px;
  height: 470px;
}

.plan-hero__shape--two {
  top: 132px;
  right: 11%;
  width: 300px;
  height: 300px;
}

.plan-hero__inner {
  position: relative;
  z-index: 2;
  display: flex;
  min-height: 482px;
  flex-direction: column;
}

.back-link {
  display: inline-flex;
  width: max-content;
  align-items: center;
  gap: 7px;
  padding: 0;
  border: 0;
  background: transparent;
  color: rgba(255, 255, 255, 0.58);
  font-size: 10px;
  cursor: pointer;
}

.back-link:hover {
  color: #fff;
}

.plan-hero__main {
  display: grid;
  flex: 1;
  grid-template-columns: 1.25fr 0.75fr;
  gap: 90px;
  align-items: center;
  padding: 35px 0 24px;
}

.hero-kicker {
  margin: 0 0 19px;
  color: #e5bc7d;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.21em;
}

.plan-hero h1 {
  margin: 0;
  font-family: var(--font-display);
  font-size: clamp(50px, 5.5vw, 76px);
  font-weight: 500;
  letter-spacing: -0.055em;
  line-height: 1;
}

.plan-hero__summary {
  max-width: 510px;
  margin: 24px 0 0;
  color: rgba(255, 255, 255, 0.62);
  font-size: 13px;
  line-height: 1.8;
}

.plan-meta {
  display: flex;
  gap: 25px;
  margin-top: 31px;
  flex-wrap: wrap;
}

.plan-meta span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: rgba(255, 255, 255, 0.68);
  font-size: 10px;
}

.plan-meta svg {
  color: #e6bd7d;
}

.plan-hero__stamp {
  position: relative;
  justify-self: end;
  width: min(310px, 100%);
  min-height: 290px;
  padding: 20px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.16);
  background: rgba(244, 232, 208, 0.09);
  box-shadow: 0 30px 80px rgba(6, 24, 17, 0.18);
  backdrop-filter: blur(8px);
  transform: rotate(2deg);
}

.plan-hero__stamp-top {
  display: block;
  color: rgba(255, 255, 255, 0.54);
  font-size: 7px;
  letter-spacing: 0.18em;
}

.plan-hero__stamp svg {
  position: absolute;
  right: 14px;
  bottom: 49px;
  left: 14px;
  width: calc(100% - 28px);
  height: 130px;
}

.skyline-far {
  fill: rgba(255, 255, 255, 0.08);
}

.skyline-near {
  fill: rgba(255, 255, 255, 0.14);
}

.hero-tower {
  fill: #d8b06c;
  opacity: 0.72;
}

.plan-hero__sun {
  position: absolute;
  top: 65px;
  right: 60px;
  width: 74px;
  height: 74px;
  border-radius: 50%;
  background: rgba(221, 183, 118, 0.25);
  box-shadow: 0 0 50px rgba(234, 198, 139, 0.15);
}

.plan-hero__stamp p,
.plan-hero__stamp > span:last-child {
  position: absolute;
  right: 20px;
  bottom: 23px;
  left: 20px;
  margin: 0;
  color: rgba(255, 255, 255, 0.44);
  font-size: 7px;
  letter-spacing: 0.14em;
}

.plan-hero__stamp p {
  bottom: auto;
  top: 39px;
}

.plan-hero__stamp > span:last-child {
  left: auto;
}

.plan-hero__bottom {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr 1.5fr;
  padding: 22px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.11);
}

.plan-hero__bottom > div:not(.plan-hero__engine) {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.plan-hero__bottom span {
  color: rgba(255, 255, 255, 0.4);
  font-size: 8px;
  letter-spacing: 0.08em;
}

.plan-hero__bottom strong {
  color: #fff7e9;
  font-family: var(--font-display);
  font-size: 19px;
  font-weight: 500;
}

.plan-hero__engine {
  display: inline-flex;
  align-self: center;
  justify-self: end;
  align-items: center;
  gap: 7px;
  padding: 8px 12px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.55);
  font-size: 8px;
}

.plan-main {
  padding: 54px 0 90px;
}

.issue-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 25px;
  padding: 13px 16px;
  border: 1px solid rgba(177, 91, 57, 0.16);
  border-radius: 12px;
  background: rgba(205, 120, 82, 0.08);
  color: #89482f;
  font-size: 11px;
}

.feedback-banner {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 18px;
  padding: 11px 14px;
  border: 1px solid rgba(68, 113, 83, 0.16);
  border-radius: 10px;
  background: rgba(101, 145, 111, 0.08);
  color: #456a50;
  font-size: 10px;
}

.feedback-banner button {
  display: grid;
  margin-left: auto;
  place-items: center;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.plan-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(290px, 0.75fr);
  gap: 54px;
  align-items: start;
}

.plan-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.plan-toolbar > button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 11px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.42);
  color: var(--forest);
  font-size: 9px;
  cursor: pointer;
}

.plan-toolbar > button span {
  padding: 2px 6px;
  border-radius: 999px;
  background: rgba(37, 80, 62, 0.09);
  color: var(--muted);
  font-size: 7px;
}

.plan-toolbar > span {
  color: #9c9e96;
  font-size: 8px;
}

.day-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 45px;
}

.day-tabs button {
  display: flex;
  min-width: 136px;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
  padding: 12px 16px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: rgba(255, 255, 255, 0.35);
  color: var(--muted);
  cursor: pointer;
  transition: background 180ms ease, color 180ms ease;
}

.day-tabs button span {
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

.day-tabs button strong {
  color: var(--muted-strong);
  font-size: 11px;
  font-weight: 500;
}

.day-tabs button.active {
  border-color: var(--forest);
  background: var(--forest);
  color: #e1bd83;
}

.day-tabs button.active strong {
  color: #fff;
}

.day-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 36px;
}

.day-heading h2 {
  margin: 14px 0 9px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 38px;
  font-weight: 500;
  letter-spacing: -0.035em;
}

.day-heading > div > p:last-child {
  margin: 0;
  color: var(--muted);
  font-size: 10px;
}

.day-heading__mark {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  color: rgba(39, 79, 62, 0.22);
}

.day-heading__mark span {
  font-family: var(--font-display);
  font-size: 58px;
  line-height: 0.8;
}

.day-heading__mark small {
  margin-top: 8px;
  font-size: 7px;
  letter-spacing: 0.18em;
}

.timeline {
  position: relative;
}

.timeline::before {
  position: absolute;
  top: 55px;
  bottom: 60px;
  left: 87px;
  width: 1px;
  background: linear-gradient(var(--line), rgba(45, 86, 68, 0.04));
  content: "";
}

.place-stop {
  --accent: #b56c48;
  position: relative;
  display: grid;
  grid-template-columns: 77px 20px minmax(0, 1fr);
  gap: 15px;
  align-items: flex-start;
  margin-bottom: 18px;
}

.time-column {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  padding-top: 18px;
}

.time-column strong {
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 500;
}

.time-column span {
  margin-top: 2px;
  color: #a2a49d;
  font-size: 8px;
}

.time-column small {
  margin-top: 7px;
  color: #9ba29b;
  font-size: 7px;
  letter-spacing: 0.08em;
}

.timeline-dot {
  position: relative;
  z-index: 2;
  display: grid;
  width: 20px;
  height: 20px;
  margin-top: 23px;
  place-items: center;
  border: 5px solid var(--cream);
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
}

.timeline-dot small {
  position: relative;
  z-index: 3;
  color: #fff;
  font-size: 8px;
  line-height: 1;
}

.timeline-dot--meal {
  width: 20px;
  height: 20px;
  border-width: 4px;
  background: #c59b62;
}

.place-stop__content {
  display: grid;
  grid-template-columns: 155px minmax(0, 1fr);
  gap: 19px;
  padding: 11px;
  border: 1px solid rgba(34, 67, 52, 0.09);
  border-radius: 15px;
  background: rgba(255, 253, 247, 0.78);
  box-shadow: 0 9px 30px rgba(39, 60, 47, 0.05);
  transition: transform 200ms ease, box-shadow 200ms ease;
}

.place-stop__content:hover {
  transform: translateY(-3px);
  box-shadow: 0 16px 38px rgba(39, 60, 47, 0.1);
}

.place-stop__art {
  position: relative;
  min-height: 142px;
  overflow: hidden;
  border-radius: 10px;
  background:
    radial-gradient(circle at 65% 25%, rgba(255, 246, 214, 0.7), transparent 18%),
    linear-gradient(150deg, color-mix(in srgb, var(--accent) 77%, #d6c8ab), color-mix(in srgb, var(--accent) 37%, #efe5d2));
}

.art-orbit {
  position: absolute;
  border: 1px solid rgba(255, 255, 255, 0.27);
  border-radius: 50%;
}

.art-orbit--one {
  right: -50px;
  bottom: -60px;
  width: 180px;
  height: 180px;
}

.art-orbit--two {
  right: -13px;
  bottom: -23px;
  width: 100px;
  height: 100px;
}

.art-route {
  position: absolute;
  top: 23%;
  left: -15%;
  width: 130%;
  height: 55%;
  border-top: 1px dashed rgba(255, 255, 255, 0.34);
  border-radius: 50%;
  transform: rotate(-12deg);
}

.art-initial {
  position: absolute;
  top: 18px;
  left: 17px;
  color: rgba(255, 255, 255, 0.18);
  font-family: var(--font-display);
  font-size: 79px;
  line-height: 1;
}

.art-area {
  position: absolute;
  right: 13px;
  bottom: 12px;
  padding: 5px 8px;
  border-radius: 999px;
  background: rgba(30, 54, 43, 0.34);
  color: rgba(255, 255, 255, 0.82);
  font-size: 7px;
  letter-spacing: 0.1em;
  backdrop-filter: blur(5px);
}

.place-stop__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding: 9px 8px 5px 0;
}

.place-stop__top {
  display: flex;
  justify-content: space-between;
  color: color-mix(in srgb, var(--accent) 55%, #5d655e);
  font-size: 8px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.place-stop h3 {
  margin: 10px 0 7px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.03em;
}

.place-stop__copy > p {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: var(--muted);
  font-size: 10px;
  line-height: 1.7;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.place-stop__actions {
  display: flex;
  align-items: center;
  gap: 17px;
  margin-top: auto;
  padding-top: 12px;
}

.place-stop__actions button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--forest);
  font-size: 9px;
  font-weight: 600;
  cursor: pointer;
}

.place-stop__actions a {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #93978f;
  font-size: 8px;
  text-decoration: none;
}

.transit-row {
  display: grid;
  grid-template-columns: 77px 20px 1fr;
  gap: 15px;
  height: 41px;
  align-items: center;
}

.transit-row > span {
  color: #aaa99f;
  font-size: 7px;
  letter-spacing: 0.08em;
  text-align: right;
}

.transit-line {
  grid-column: 2 / 4;
  display: flex;
  align-items: center;
  gap: 8px;
  color: #989b94;
  font-size: 8px;
}

.transit-line::after {
  height: 1px;
  flex: 1;
  background: linear-gradient(90deg, rgba(39, 80, 62, 0.13), transparent);
  content: "";
}

.meal-card {
  display: grid;
  grid-template-columns: 77px 20px minmax(0, 1fr);
  gap: 15px;
  margin-bottom: 18px;
}

.meal-card__body {
  padding: 16px 18px;
  border: 1px dashed rgba(126, 101, 60, 0.24);
  border-radius: 12px;
  background: rgba(234, 218, 188, 0.2);
}

.meal-card__body > span {
  color: #9d7742;
  font-size: 7px;
  letter-spacing: 0.13em;
}

.meal-card h3 {
  margin: 7px 0 5px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 19px;
}

.meal-card p {
  margin: 0;
  color: var(--muted);
  font-size: 9px;
}

.meal-card__body > strong {
  display: inline-block;
  margin-top: 8px;
  color: #806b4a;
  font-size: 9px;
}

.plan-sidebar {
  position: sticky;
  top: 28px;
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.map-card,
.budget-card,
.refine-card,
.verified-card,
.tips-card {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: rgba(255, 253, 247, 0.75);
}

.refine-card {
  padding: 17px;
}

.refine-card > p {
  margin: 1px 0 12px;
  color: var(--muted);
  font-size: 8px;
  line-height: 1.6;
}

.refine-card form {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 5px 5px 5px 11px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fffdf8;
}

.refine-card input {
  min-width: 0;
  height: 32px;
  flex: 1;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-size: 9px;
}

.refine-card form > button {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background: var(--forest);
  color: #fff;
  cursor: pointer;
}

.refine-card form > button:disabled {
  opacity: 0.4;
}

.refine-chips {
  display: flex;
  gap: 6px;
  margin-top: 9px;
}

.refine-chips button {
  padding: 5px 8px;
  border: 0;
  border-radius: 999px;
  background: var(--cream-deep);
  color: var(--muted-strong);
  font-size: 7px;
  cursor: pointer;
}

.map-card {
  padding: 16px;
}

.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 2px 13px;
}

.panel-heading > span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--forest);
  font-size: 10px;
  font-weight: 600;
}

.panel-heading > small {
  color: var(--muted);
  font-size: 8px;
}

.map-card__canvas {
  height: 285px;
  overflow: hidden;
  border-radius: 10px;
}

.map-card__button {
  width: 100%;
  min-height: 43px;
  margin-top: 14px;
  font-size: 11px;
}

.budget-card {
  padding: 17px;
}

.budget-numbers {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
}

.budget-numbers strong {
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 27px;
  font-weight: 500;
}

.budget-numbers span {
  color: var(--muted);
  font-size: 8px;
}

.budget-track {
  height: 5px;
  margin: 14px 0 11px;
  overflow: hidden;
  border-radius: 99px;
  background: rgba(46, 85, 68, 0.09);
}

.budget-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #78977e, var(--clay));
}

.budget-card p {
  margin: 0;
  color: var(--muted);
  font-size: 8px;
  line-height: 1.65;
}

.verified-card {
  display: flex;
  gap: 12px;
  padding: 17px;
  border-color: rgba(61, 105, 81, 0.14);
  background: rgba(199, 216, 198, 0.24);
}

.verified-card__icon {
  display: grid;
  flex: 0 0 auto;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 50%;
  background: rgba(60, 111, 82, 0.1);
  color: #50795f;
}

.verified-card span {
  color: #68806e;
  font-size: 7px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.verified-card h3 {
  margin: 5px 0 6px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 16px;
}

.verified-card p {
  margin: 0;
  color: #758378;
  font-size: 8px;
  line-height: 1.7;
}

.tips-card {
  padding: 19px;
  border: 0;
  background: var(--forest);
  color: #fff;
}

.tips-card > span {
  color: #d7b575;
  font-size: 7px;
  letter-spacing: 0.16em;
}

.tips-card > p {
  margin: 15px 0 21px;
  color: #f7eedf;
  font-size: 17px;
  line-height: 1.45;
}

.tips-card > div {
  display: flex;
  align-items: center;
  gap: 7px;
  color: rgba(255, 255, 255, 0.46);
  font-size: 8px;
}

.next-chapter {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 70px;
  padding: 60px 0;
  border-top: 1px solid var(--line);
}

.next-chapter h2 {
  margin: 15px 0 0;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: clamp(36px, 4vw, 53px);
  font-weight: 500;
  letter-spacing: -0.04em;
  line-height: 1.08;
}

.dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(17, 38, 29, 0.52);
  backdrop-filter: blur(8px);
}

.versions-dialog,
.swap-dialog {
  width: min(690px, 100%);
  max-height: min(720px, calc(100vh - 48px));
  padding: 27px;
  overflow-y: auto;
  border: 1px solid rgba(255, 255, 255, 0.76);
  border-radius: 22px;
  background: #faf7ee;
  box-shadow: 0 30px 90px rgba(13, 33, 24, 0.26);
}

.versions-dialog header,
.swap-dialog header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.versions-dialog h2,
.swap-dialog h2 {
  margin: 11px 0 0;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 31px;
  font-weight: 500;
  letter-spacing: -0.035em;
}

.versions-dialog header > button,
.swap-dialog header > button {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: transparent;
  color: var(--forest);
  cursor: pointer;
}

.versions-list {
  display: flex;
  gap: 8px;
  margin-top: 22px;
  flex-direction: column;
}

.versions-list article {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: rgba(255, 253, 247, 0.7);
}

.versions-list article.current {
  border-color: rgba(44, 91, 69, 0.26);
  background: rgba(191, 211, 190, 0.22);
}

.version-number {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 50%;
  background: var(--forest);
  color: #fff1da;
  font-family: var(--font-display);
  font-size: 13px;
}

.versions-list article > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.versions-list strong {
  color: var(--forest);
  font-size: 10px;
}

.versions-list p {
  margin: 0;
  color: var(--muted);
  font-size: 8px;
}

.versions-list article > button,
.version-current {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: transparent;
  color: var(--forest);
  font-size: 8px;
}

.versions-list article > button {
  cursor: pointer;
}

.version-current {
  color: #5b8269;
  border-color: rgba(83, 124, 94, 0.18);
}

.swap-dialog header p:last-child {
  margin: 7px 0 0;
  color: var(--muted);
  font-size: 9px;
}

.swap-grid {
  display: grid;
  gap: 8px;
  margin-top: 22px;
}

.swap-grid > button {
  --swap-accent: #b36d4b;
  display: grid;
  grid-template-columns: 62px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 9px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fffdf8;
  text-align: left;
  cursor: pointer;
}

.swap-grid > button:hover {
  border-color: color-mix(in srgb, var(--swap-accent) 50%, transparent);
}

.swap-art {
  display: grid;
  width: 62px;
  height: 64px;
  place-items: center;
  border-radius: 8px;
  background:
    radial-gradient(circle at 72% 25%, rgba(255, 255, 255, 0.35), transparent 25%),
    var(--swap-accent);
  color: rgba(255, 255, 255, 0.5);
  font-family: var(--font-display);
  font-size: 35px;
}

.swap-grid > button > span:nth-child(2) {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.swap-grid small {
  color: var(--muted);
  font-size: 7px;
}

.swap-grid strong {
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 17px;
}

.swap-grid em {
  overflow: hidden;
  color: var(--muted);
  font-size: 8px;
  font-style: normal;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.swap-grid > button > svg {
  color: var(--muted);
}

.swap-empty {
  display: flex;
  min-height: 170px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 12px;
  color: var(--muted);
  font-size: 9px;
}

@media (max-width: 980px) {
  .plan-hero__main {
    grid-template-columns: 1fr;
  }

  .plan-hero__stamp {
    display: none;
  }

  .plan-layout {
    grid-template-columns: 1fr;
    gap: 44px;
  }

  .plan-sidebar {
    position: static;
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .map-card {
    grid-row: span 2;
  }
}

@media (max-width: 720px) {
  .plan-hero {
    min-height: auto;
    padding-top: 73px;
  }

  .plan-hero__inner {
    min-height: auto;
  }

  .plan-hero__main {
    padding: 46px 0 36px;
  }

  .plan-hero h1 {
    font-size: 50px;
  }

  .plan-meta {
    gap: 14px;
  }

  .plan-hero__bottom {
    grid-template-columns: 1fr 1fr;
    gap: 17px;
  }

  .plan-hero__engine {
    justify-self: start;
  }

  .plan-main {
    padding-top: 37px;
  }

  .day-tabs {
    overflow-x: auto;
    padding-bottom: 4px;
  }

  .day-tabs button {
    flex: 0 0 auto;
  }

  .day-heading h2 {
    font-size: 30px;
  }

  .timeline::before {
    display: none;
  }

  .place-stop,
  .meal-card {
    grid-template-columns: 44px minmax(0, 1fr);
    gap: 10px;
  }

  .timeline-dot {
    display: none;
  }

  .time-column {
    padding-top: 18px;
  }

  .time-column strong {
    font-size: 14px;
  }

  .place-stop__content {
    grid-template-columns: 1fr;
  }

  .place-stop__art {
    min-height: 115px;
  }

  .art-initial {
    font-size: 64px;
  }

  .place-stop h3 {
    font-size: 23px;
  }

  .transit-row {
    grid-template-columns: 44px 1fr;
    gap: 10px;
  }

  .transit-line {
    grid-column: 2;
  }

  .meal-card__body {
    grid-column: 2;
  }

  .plan-sidebar {
    display: flex;
  }

  .next-chapter {
    align-items: flex-start;
    gap: 28px;
    flex-direction: column;
  }

  .dialog-backdrop {
    align-items: flex-end;
    padding: 12px;
  }

  .versions-dialog,
  .swap-dialog {
    max-height: 82vh;
    padding: 21px;
    border-radius: 20px;
  }
}
</style>
