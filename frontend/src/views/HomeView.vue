<!--
  模块：首页 / 需求入口（views/HomeView.vue）
  职责：整条链路的起点。
  1) 首屏展示品牌、能力说明与精选景点（读 api.bootstrap 与 POI）；
  2) 用户用一句话描述旅行想法 -> api.createDraft 起稿；
  3) 缺字段时在页面补 Slot（日期、天数、人数、预算、节奏）-> api.updateRequirements；
  4) api.generatePlan 生成行程后跳转 /plan/:planId。
  本页只管表单与提示，生成逻辑全在后端。
-->
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  CalendarDays,
  Check,
  ChevronRight,
  Compass,
  MapPin,
  Route,
  Sparkles,
  Users,
} from 'lucide-vue-next'
import SiteHeader from '../components/SiteHeader.vue'
import { api } from '../services/api'
import { useTripStore } from '../services/tripStore'
import type { Bootstrap, Draft, Poi } from '../types'

const router = useRouter()
const tripStore = useTripStore()
const prompt = ref('下周末去上海玩两天，预算 3000 元，想去外滩和上海博物馆，不吃辣，不想走太累。')
const promptInput = ref<HTMLTextAreaElement | null>(null)
const loading = ref(false)
const slotOpen = ref(false)
const error = ref('')
const draft = ref<Draft | null>(null)

const preferences = reactive({
  startDate: nextSaturday(),
  days: 2,
  people: 2,
  budget: 3000,
  pace: 'RELAXED',
  noSpicy: true,
})

const fallbackBootstrap: Bootstrap = {
  city: '上海',
  seasonLabel: '秋日 · 适合慢慢走',
  curatedPoiCount: 30,
  aiMode: '本地规则演示',
  suggestedPrompts: [],
  featuredPois: [
    {
      id: 'bund',
      name: '外滩',
      type: 'SCENIC',
      area: '黄浦',
      latitude: 31.2397,
      longitude: 121.4905,
      durationMinutes: 90,
      ticketPrice: 0,
      openTime: '全天',
      closeTime: '',
      tags: ['城市景观', '建筑', '夜景'],
      accent: '#C96B45',
      summary: '沿黄浦江西岸展开的城市天际线，是把上海历史与当代放在同一幅画面里的地方。',
      history: '',
      sourceName: '演示资料',
      dataConfidence: 'DEMO',
    },
    {
      id: 'wukang-road',
      name: '武康路',
      type: 'STREET',
      area: '徐汇',
      latitude: 31.205,
      longitude: 121.438,
      durationMinutes: 80,
      ticketPrice: 0,
      openTime: '全天',
      closeTime: '',
      tags: ['城市漫步', '建筑', '街区'],
      accent: '#806B55',
      summary: '梧桐树与历史建筑共同形成的街区，适合从地标转向街巷观察。',
      history: '',
      sourceName: '演示资料',
      dataConfidence: 'DEMO',
    },
    {
      id: 'yuyuan',
      name: '豫园',
      type: 'GARDEN',
      area: '黄浦',
      latitude: 31.227,
      longitude: 121.492,
      durationMinutes: 90,
      ticketPrice: 30,
      openTime: '09:00',
      closeTime: '16:30',
      tags: ['园林', '人文', '建筑'],
      accent: '#5F7651',
      summary: '在密集市井中展开的江南园林，适合观察空间转折、漏窗和园林尺度。',
      history: '',
      sourceName: '演示资料',
      dataConfidence: 'DEMO',
    },
  ],
}

const bootstrap = computed(() => tripStore.bootstrap || fallbackBootstrap)
const featured = computed(() => bootstrap.value.featuredPois.slice(0, 3))

onMounted(async () => {
  try {
    tripStore.bootstrap = await api.bootstrap()
  } catch {
    tripStore.bootstrap = fallbackBootstrap
  }
})

function nextSaturday() {
  const date = new Date()
  const day = date.getDay()
  const offset = (6 - day + 7) % 7 || 7
  date.setDate(date.getDate() + offset)
  return date.toISOString().slice(0, 10)
}

function choosePrompt(value: string) {
  prompt.value = value
  promptInput.value?.focus()
}

async function beginPlanning() {
  if (!prompt.value.trim() || loading.value) return
  error.value = ''
  loading.value = true
  try {
    const result = await api.createDraft(prompt.value)
    draft.value = result
    preferences.days = result.days || 2
    preferences.people = result.people || 2
    preferences.budget = result.budget || 3000
    preferences.pace = result.pace || 'RELAXED'
    preferences.noSpicy = result.noSpicy ?? true
    preferences.startDate = result.startDate || preferences.startDate

    if (result.missingFields.length > 0) {
      slotOpen.value = true
    } else {
      await generatePlan(result.requestId)
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '生成需求时出了点问题'
  } finally {
    loading.value = false
  }
}

async function fillAndGenerate() {
  if (!draft.value || loading.value) return
  error.value = ''
  loading.value = true
  try {
    await api.updateRequirements(draft.value.requestId, {
      startDate: preferences.startDate,
      days: preferences.days,
      people: preferences.people,
      budget: preferences.budget,
      pace: preferences.pace,
      noSpicy: preferences.noSpicy,
    })
    await generatePlan(draft.value.requestId)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '生成行程时出了点问题'
  } finally {
    loading.value = false
  }
}

async function generatePlan(requestId: string) {
  const plan = await api.generatePlan(requestId)
  tripStore.rememberPlan(plan)
  slotOpen.value = false
  await router.push({ name: 'plan', params: { planId: plan.id } })
}

function cardStyle(poi: Poi) {
  return {
    '--accent': poi.accent,
  }
}

function scrollToPlanner() {
  document.querySelector('#planner')?.scrollIntoView({ behavior: 'smooth' })
  window.setTimeout(() => promptInput.value?.focus(), 350)
}
</script>

<template>
  <div class="home">
    <div class="home__grain" aria-hidden="true"></div>
    <SiteHeader action-label="规划我的旅程" @action="scrollToPlanner" />

    <main>
      <section class="hero page-shell">
        <div class="hero__copy">
          <p class="eyebrow">AI travel companion</p>
          <h1>
            把一座城，<br />
            走成一段<span>故事</span>。
          </h1>
          <p class="hero__lead">
            告诉漫游你想去的方向。我们替你整理时间、路线与预算，
            也在你真正抵达时，讲出眼前风景背后的故事。
          </p>
          <div class="hero__proof">
            <div class="avatar-stack" aria-hidden="true">
              <span>沪</span>
              <span>行</span>
              <span>漫</span>
            </div>
            <p><strong>30+</strong> 个上海精选目的地<br /><span>为轻松的旅行方式而整理</span></p>
          </div>
        </div>

        <div class="hero__visual" aria-label="上海城市漫游路线插画">
          <div class="postcard postcard--back">
            <span>31°14′ N</span>
            <span>SHANGHAI</span>
          </div>
          <div class="postcard">
            <div class="postcard__top">
              <span>FIELD NOTES / 01</span>
              <Compass :size="19" :stroke-width="1.5" />
            </div>
            <div class="postcard__sky">
              <span class="sun"></span>
              <svg viewBox="0 0 520 240" role="img" aria-label="抽象上海天际线">
                <path class="river" d="M0 185C80 159 119 210 203 178C290 144 348 176 520 130" />
                <g class="far-buildings">
                  <path d="M30 160V109H53V160M70 160V87H103V160M118 160V122H144V160" />
                  <path d="M337 160V108H358V160M373 160V91H404V160M417 160V126H444V160" />
                </g>
                <g class="near-buildings">
                  <path d="M54 177V143H109V177M121 177V126H158V177M171 177V149H209V177" />
                  <path d="M306 177V139H351V177M365 177V110H397V177M409 177V146H462V177" />
                </g>
                <path class="tower" d="M235 169V79H246V54H255V22H264V54H275V79H286V169" />
                <circle class="tower-dot" cx="255" cy="66" r="7" />
                <circle class="tower-dot" cx="255" cy="103" r="5" />
              </svg>
            </div>
            <div class="postcard__caption">
              <span>从黄浦江出发</span>
              <span>EST. 2026</span>
            </div>
          </div>
          <div class="route-note">
            <span class="route-note__dot"></span>
            <div>
              <small>NEXT STOP</small>
              <strong>武康路 · 15:30</strong>
            </div>
            <ArrowRight :size="18" :stroke-width="1.6" />
          </div>
        </div>
      </section>

      <section id="planner" class="planner-wrap page-shell">
        <div class="planner-card">
          <div class="planner-card__head">
            <div>
              <span class="planner-card__icon"><Sparkles :size="17" /></span>
              <div>
                <strong>想去哪里，怎么玩？</strong>
                <small>用自然语言说说你的想法</small>
              </div>
            </div>
            <span class="ai-mode"><i></i>{{ bootstrap.aiMode }}</span>
          </div>

          <div class="prompt-box">
            <textarea
              ref="promptInput"
              v-model="prompt"
              rows="3"
              aria-label="描述你的旅行计划"
              placeholder="例如：下周末去上海，想慢慢逛，不要太多人……"
              @keydown.meta.enter="beginPlanning"
              @keydown.ctrl.enter="beginPlanning"
            ></textarea>
            <button class="prompt-submit" type="button" :disabled="loading" @click="beginPlanning">
              <span v-if="loading" class="spinner"></span>
              <span v-else>生成行程</span>
              <ArrowRight v-if="!loading" :size="18" :stroke-width="1.8" />
            </button>
          </div>

          <div class="prompt-footer">
            <div class="quick-prompts">
              <button type="button" @click="choosePrompt('周末在上海慢游一天，喜欢建筑和街区，预算 800 元。')">
                <Route :size="14" /> 城市慢游
              </button>
              <button type="button" @click="choosePrompt('下个月去上海三天，预算 5000 元，想多看博物馆，每天轻松一点。')">
                <Compass :size="14" /> 人文深度
              </button>
              <button type="button" @click="choosePrompt('带家人去上海两天，预算 3500 元，想少走路，吃清淡一点。')">
                <Users :size="14" /> 家庭出游
              </button>
            </div>
            <span class="prompt-hint">⌘ ↵ 快速生成</span>
          </div>

          <p v-if="error" class="planner-error">{{ error }}</p>
        </div>
      </section>

      <section id="inspiration" class="inspiration page-shell">
        <div class="section-heading">
          <div>
            <p class="eyebrow">Curated for now</p>
            <h2>本季，值得停留的地方</h2>
          </div>
          <p>不是更多景点，是更适合此刻的节奏。<br />{{ bootstrap.seasonLabel }}</p>
        </div>

        <div class="inspiration-grid">
          <article
            v-for="(poi, index) in featured"
            :key="poi.id"
            class="place-card"
            :class="{ 'place-card--wide': index === 0 }"
            :style="cardStyle(poi)"
            @click="choosePrompt(`我想去上海${poi.name}，帮我安排一天的轻松行程，预算 1000 元。`)"
          >
            <div class="place-card__wash"></div>
            <span class="place-card__index">0{{ index + 1 }}</span>
            <div class="place-card__topline">
              <span>{{ poi.area }} · {{ poi.tags[0] }}</span>
              <span>{{ poi.durationMinutes }} MIN</span>
            </div>
            <div class="place-card__art" aria-hidden="true">
              <span class="place-card__ring"></span>
              <span class="place-card__line"></span>
              <span class="place-card__pin"><MapPin :size="15" /></span>
            </div>
            <div class="place-card__body">
              <span class="place-card__tag">{{ poi.tags.slice(0, 2).join(' / ') }}</span>
              <h3>{{ poi.name }}</h3>
              <p>{{ poi.summary }}</p>
              <span class="place-card__link">
                从这里开始 <ChevronRight :size="16" />
              </span>
            </div>
          </article>

          <article class="place-card place-card--prompt" @click="scrollToPlanner">
            <span class="place-card__index">04</span>
            <div>
              <span class="place-card__tag">MAKE IT YOURS</span>
              <h3>或者，告诉我们<br />你的旅行关键词。</h3>
            </div>
            <span class="place-card__round">
              <ArrowRight :size="22" :stroke-width="1.6" />
            </span>
          </article>
        </div>
      </section>

      <section id="how-it-works" class="how-it-works page-shell">
        <div class="how-it-works__copy">
          <p class="eyebrow">A better way to wander</p>
          <h2>做减法，<br />把旅行还给旅行。</h2>
        </div>
        <div class="steps">
          <article>
            <span>01</span>
            <Check :size="19" />
            <h3>说清偏好</h3>
            <p>预算、日期、体力与必去地点，被整理成可以执行的旅行条件。</p>
          </article>
          <article>
            <span>02</span>
            <CalendarDays :size="19" />
            <h3>得到可执行行程</h3>
            <p>时间冲突、预算和开放信息经过规则校验，而不是一段漂亮但空泛的文案。</p>
          </article>
          <article id="guide">
            <span>03</span>
            <MapPin :size="19" />
            <h3>抵达后再听故事</h3>
            <p>打开导游页，定位或手动选点，让眼前风景与它的来处重新相遇。</p>
          </article>
        </div>
      </section>
    </main>

    <footer class="footer page-shell">
      <span class="serif">漫游 HERE</span>
      <p>慢一点，才能看见更多。</p>
      <span>Shanghai · 2026</span>
    </footer>

    <Transition name="sheet">
      <div v-if="slotOpen && draft" class="slot-backdrop" @click.self="slotOpen = false">
        <section class="slot-sheet" aria-labelledby="slot-title">
          <div class="slot-sheet__head">
            <div>
              <p class="eyebrow">One last detail</p>
              <h2 id="slot-title">再确认几件小事</h2>
              <p>{{ draft.summary }}</p>
            </div>
            <span class="slot-sheet__number">01—04</span>
          </div>

          <div class="slot-grid">
            <label>
              <span>出发日期</span>
              <input v-model="preferences.startDate" type="date" />
            </label>
            <label>
              <span>旅行天数</span>
              <select v-model.number="preferences.days">
                <option :value="1">1 天</option>
                <option :value="2">2 天</option>
                <option :value="3">3 天</option>
              </select>
            </label>
            <label>
              <span>同行人数</span>
              <select v-model.number="preferences.people">
                <option :value="1">1 人</option>
                <option :value="2">2 人</option>
              </select>
            </label>
            <label>
              <span>总预算</span>
              <div class="budget-input">
                <span>¥</span>
                <input v-model.number="preferences.budget" type="number" min="100" step="100" />
              </div>
            </label>
          </div>

          <div class="slot-options">
            <button
              type="button"
              :class="{ active: preferences.pace === 'RELAXED' }"
              @click="preferences.pace = 'RELAXED'"
            >
              轻松节奏
            </button>
            <button
              type="button"
              :class="{ active: preferences.pace === 'BALANCED' }"
              @click="preferences.pace = 'BALANCED'"
            >
              平衡节奏
            </button>
            <button
              type="button"
              :class="{ active: preferences.noSpicy }"
              @click="preferences.noSpicy = !preferences.noSpicy"
            >
              {{ preferences.noSpicy ? '不吃辣' : '饮食不限' }}
            </button>
          </div>

          <div class="slot-sheet__footer">
            <p><Check :size="15" /> 所有行程都会经过时间与预算校验</p>
            <button class="primary-button" type="button" :disabled="loading" @click="fillAndGenerate">
              <span v-if="loading" class="spinner"></span>
              <span v-else>开始安排</span>
              <ArrowRight v-if="!loading" :size="17" />
            </button>
          </div>
          <p v-if="error" class="planner-error">{{ error }}</p>
        </section>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.home {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  background:
    radial-gradient(circle at 93% 8%, rgba(207, 187, 152, 0.34), transparent 28%),
    radial-gradient(circle at 4% 60%, rgba(168, 188, 162, 0.2), transparent 26%),
    var(--cream);
}

.home__grain {
  position: fixed;
  inset: 0;
  z-index: 0;
  opacity: 0.25;
  pointer-events: none;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 180 180' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.8' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='.08'/%3E%3C/svg%3E");
}

main,
.footer {
  position: relative;
  z-index: 1;
}

.hero {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr;
  gap: 80px;
  align-items: center;
  padding: 58px 0 30px;
}

.hero__copy {
  padding-bottom: 24px;
}

.hero h1 {
  max-width: 620px;
  margin: 22px 0 24px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: clamp(58px, 6.3vw, 91px);
  font-weight: 500;
  letter-spacing: -0.065em;
  line-height: 0.96;
}

.hero h1 span {
  position: relative;
  color: var(--clay);
  font-style: italic;
}

.hero h1 span::after {
  position: absolute;
  right: 3px;
  bottom: -8px;
  width: 74%;
  height: 9px;
  border-radius: 50%;
  background: rgba(198, 99, 63, 0.16);
  content: "";
  transform: rotate(-2deg);
}

.hero__lead {
  max-width: 490px;
  margin: 0;
  color: var(--muted-strong);
  font-size: 15px;
  line-height: 1.9;
}

.hero__proof {
  display: flex;
  align-items: center;
  gap: 15px;
  margin-top: 38px;
}

.avatar-stack {
  display: flex;
}

.avatar-stack span {
  display: grid;
  width: 34px;
  height: 34px;
  margin-left: -7px;
  place-items: center;
  border: 2px solid var(--cream);
  border-radius: 50%;
  background: var(--sand);
  color: #fff;
  font-family: var(--font-display);
  font-size: 13px;
}

.avatar-stack span:first-child {
  margin-left: 0;
  background: var(--forest);
}

.avatar-stack span:last-child {
  background: var(--clay);
}

.hero__proof p {
  margin: 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.5;
}

.hero__proof strong {
  color: var(--forest);
  font-size: 13px;
}

.hero__proof span {
  opacity: 0.8;
}

.hero__visual {
  position: relative;
  min-height: 560px;
}

.postcard {
  position: absolute;
  top: 15px;
  left: 20px;
  width: min(435px, 84%);
  min-height: 480px;
  padding: 23px;
  border: 1px solid rgba(38, 55, 46, 0.08);
  border-radius: 4px;
  background: #fbf6e9;
  box-shadow: 0 35px 70px rgba(34, 54, 43, 0.17);
  transform: rotate(2.5deg);
}

.postcard--back {
  top: 34px;
  left: 56px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 30px;
  background: #d7c7aa;
  color: rgba(60, 65, 52, 0.55);
  font-family: var(--font-display);
  font-size: 14px;
  letter-spacing: 0.12em;
  transform: rotate(-5deg);
}

.postcard__top,
.postcard__caption {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #7a7467;
  font-size: 9px;
  font-weight: 600;
  letter-spacing: 0.16em;
}

.postcard__sky {
  position: relative;
  height: 320px;
  margin: 30px -8px 24px;
  overflow: hidden;
  background:
    linear-gradient(180deg, #edd7b8 0%, #e9cba9 62%, #b6bca3 63%, #768c7a 100%);
}

.postcard__sky::after {
  position: absolute;
  inset: 0;
  opacity: 0.18;
  background-image: linear-gradient(rgba(255, 255, 255, 0.3) 1px, transparent 1px);
  background-size: 100% 5px;
  content: "";
}

.sun {
  position: absolute;
  top: 56px;
  right: 48px;
  width: 62px;
  height: 62px;
  border-radius: 50%;
  background: rgba(255, 243, 207, 0.75);
  box-shadow: 0 0 45px rgba(255, 242, 204, 0.7);
}

.postcard__sky svg {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 1;
  width: 100%;
}

.far-buildings {
  fill: none;
  stroke: rgba(73, 82, 67, 0.44);
  stroke-width: 1.5;
}

.near-buildings {
  fill: #556b5d;
  stroke: #4b5d50;
  stroke-width: 1;
}

.tower {
  fill: #6f4c3f;
}

.tower-dot {
  fill: #f4c975;
}

.river {
  fill: none;
  stroke: #d5b78a;
  stroke-width: 22;
  opacity: 0.68;
}

.postcard__caption {
  padding-top: 8px;
  border-top: 1px solid rgba(76, 76, 60, 0.16);
}

.route-note {
  position: absolute;
  right: 0;
  bottom: 42px;
  display: flex;
  align-items: center;
  gap: 13px;
  min-width: 245px;
  padding: 17px 19px;
  border: 1px solid rgba(255, 255, 255, 0.65);
  border-radius: 13px;
  background: rgba(255, 253, 246, 0.85);
  box-shadow: 0 18px 44px rgba(44, 62, 49, 0.14);
  backdrop-filter: blur(15px);
  animation: drift 5s ease-in-out infinite;
}

.route-note__dot {
  width: 10px;
  height: 10px;
  border: 3px solid #e8b16c;
  border-radius: 50%;
  box-shadow: 0 0 0 4px rgba(232, 177, 108, 0.15);
}

.route-note div {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.route-note small {
  color: var(--muted);
  font-size: 8px;
  letter-spacing: 0.14em;
}

.route-note strong {
  color: var(--forest);
  font-size: 12px;
  font-weight: 600;
}

.planner-wrap {
  position: relative;
  z-index: 4;
  margin-top: 26px;
}

.planner-card {
  padding: 21px 24px 18px;
  border: 1px solid rgba(255, 255, 255, 0.8);
  border-radius: 22px;
  background: rgba(255, 253, 247, 0.78);
  box-shadow: 0 30px 70px rgba(43, 61, 49, 0.12);
  backdrop-filter: blur(24px);
}

.planner-card__head,
.planner-card__head > div {
  display: flex;
  align-items: center;
}

.planner-card__head {
  justify-content: space-between;
  padding: 0 4px 13px;
}

.planner-card__head > div {
  gap: 11px;
}

.planner-card__icon {
  display: grid;
  width: 35px;
  height: 35px;
  place-items: center;
  border-radius: 50%;
  background: rgba(201, 103, 67, 0.1);
  color: var(--clay);
}

.planner-card__head div div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.planner-card__head strong {
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 16px;
}

.planner-card__head small {
  color: var(--muted);
  font-size: 10px;
}

.ai-mode {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--muted);
  font-size: 9px;
  letter-spacing: 0.1em;
}

.ai-mode i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #6c9b78;
  box-shadow: 0 0 0 4px rgba(108, 155, 120, 0.13);
}

.prompt-box {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 12px 12px 12px 19px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #fff;
  transition: border 180ms ease, box-shadow 180ms ease;
}

.prompt-box:focus-within {
  border-color: rgba(37, 85, 67, 0.38);
  box-shadow: 0 0 0 4px rgba(37, 85, 67, 0.06);
}

.prompt-box textarea {
  flex: 1;
  min-height: 60px;
  padding: 6px 0;
  resize: none;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-size: 14px;
  line-height: 1.7;
}

.prompt-box textarea::placeholder {
  color: #a4a79e;
}

.prompt-submit {
  display: inline-flex;
  min-width: 124px;
  height: 46px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border: 0;
  border-radius: 12px;
  background: var(--forest);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.prompt-submit:hover {
  background: var(--forest-soft);
}

.prompt-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 4px 0;
}

.quick-prompts {
  display: flex;
  gap: 8px;
}

.quick-prompts button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 11px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: transparent;
  color: var(--muted-strong);
  font-size: 10px;
  cursor: pointer;
  transition: background 160ms ease, color 160ms ease;
}

.quick-prompts button:hover {
  background: var(--cream-deep);
  color: var(--forest);
}

.prompt-hint {
  color: #9da198;
  font-size: 9px;
  letter-spacing: 0.06em;
}

.planner-error {
  margin: 10px 4px 0;
  color: #a64532;
  font-size: 12px;
}

.inspiration {
  padding: 115px 0 105px;
}

.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 36px;
}

.section-heading h2,
.how-it-works h2 {
  margin: 14px 0 0;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: clamp(36px, 4vw, 52px);
  font-weight: 500;
  letter-spacing: -0.035em;
  line-height: 1.05;
}

.section-heading > p {
  margin: 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.8;
  text-align: right;
}

.inspiration-grid {
  display: grid;
  grid-template-columns: 1.4fr 0.85fr 0.85fr;
  grid-template-rows: 370px;
  gap: 17px;
}

.place-card {
  --accent: #b66d48;
  position: relative;
  display: flex;
  overflow: hidden;
  flex-direction: column;
  justify-content: space-between;
  padding: 22px;
  border-radius: 19px;
  background:
    linear-gradient(155deg, color-mix(in srgb, var(--accent) 26%, #f7f1e7), #f6efe3 70%);
  cursor: pointer;
  isolation: isolate;
  transition: transform 260ms ease, box-shadow 260ms ease;
}

.place-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 24px 55px rgba(41, 58, 46, 0.13);
}

.place-card__wash {
  position: absolute;
  right: -60px;
  bottom: -80px;
  z-index: -1;
  width: 260px;
  height: 260px;
  border-radius: 50%;
  background: color-mix(in srgb, var(--accent) 25%, transparent);
  filter: blur(4px);
}

.place-card__index {
  position: absolute;
  top: 15px;
  right: 18px;
  color: color-mix(in srgb, var(--accent) 66%, #34463d);
  font-family: var(--font-display);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.place-card__topline {
  display: flex;
  justify-content: space-between;
  color: color-mix(in srgb, var(--accent) 54%, #454e46);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.13em;
}

.place-card__art {
  position: absolute;
  inset: 50px 18px 104px;
  overflow: hidden;
  border-radius: 14px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.34), transparent),
    radial-gradient(circle at 72% 30%, rgba(255, 245, 217, 0.72), transparent 18%);
}

.place-card__ring {
  position: absolute;
  right: -20px;
  bottom: -78px;
  width: 220px;
  height: 220px;
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: 50%;
}

.place-card__ring::after,
.place-card__ring::before {
  position: absolute;
  border: 1px solid color-mix(in srgb, var(--accent) 25%, transparent);
  border-radius: 50%;
  content: "";
}

.place-card__ring::before {
  inset: 25px;
}

.place-card__ring::after {
  inset: 50px;
}

.place-card__line {
  position: absolute;
  top: 25%;
  left: -10%;
  width: 120%;
  height: 70%;
  border-top: 1px dashed color-mix(in srgb, var(--accent) 48%, transparent);
  border-radius: 50%;
  transform: rotate(-8deg);
}

.place-card__pin {
  position: absolute;
  top: 36%;
  left: 35%;
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 4px solid rgba(255, 255, 255, 0.68);
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  box-shadow: 0 9px 20px color-mix(in srgb, var(--accent) 30%, transparent);
}

.place-card__body {
  position: relative;
  z-index: 1;
  margin-top: auto;
  padding-top: 175px;
}

.place-card__tag {
  color: color-mix(in srgb, var(--accent) 70%, #3c453e);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.13em;
}

.place-card h3 {
  margin: 8px 0 9px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 29px;
  font-weight: 600;
  letter-spacing: -0.03em;
}

.place-card p {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: var(--muted-strong);
  font-size: 10px;
  line-height: 1.65;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.place-card__link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-top: 12px;
  color: var(--forest);
  font-size: 10px;
  font-weight: 600;
}

.place-card--wide .place-card__body {
  max-width: 340px;
}

.place-card--wide h3 {
  font-size: 37px;
}

.place-card--prompt {
  justify-content: flex-end;
  background: var(--forest);
  color: #fff;
}

.place-card--prompt h3 {
  margin-top: 12px;
  color: #fff7eb;
  font-size: 28px;
  line-height: 1.15;
}

.place-card--prompt .place-card__tag {
  color: #d6b984;
}

.place-card__round {
  position: absolute;
  top: 22px;
  left: 22px;
  display: grid;
  width: 45px;
  height: 45px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 50%;
}

.how-it-works {
  display: grid;
  grid-template-columns: 0.8fr 1.6fr;
  gap: 70px;
  padding: 90px 0 104px;
  border-top: 1px solid var(--line);
}

.how-it-works__copy p:last-child {
  max-width: 300px;
  color: var(--muted);
  line-height: 1.8;
}

.steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 26px;
}

.steps article {
  position: relative;
  padding: 21px 4px 0 21px;
  border-left: 1px solid var(--line);
}

.steps article > span {
  position: absolute;
  top: 0;
  left: 16px;
  color: #b8b7ad;
  font-family: var(--font-display);
  font-size: 11px;
  transform: translateY(-100%);
}

.steps article > svg {
  margin-bottom: 35px;
  color: var(--clay);
}

.steps h3 {
  margin: 0 0 12px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 20px;
}

.steps p {
  margin: 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.85;
}

.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 30px 0 38px;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: 10px;
}

.footer .serif {
  color: var(--forest);
  font-size: 17px;
}

.footer p {
  margin: 0;
  letter-spacing: 0.08em;
}

.slot-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding: 24px;
  background: rgba(18, 36, 29, 0.44);
  backdrop-filter: blur(7px);
}

.slot-sheet {
  width: min(760px, 100%);
  padding: 31px;
  border: 1px solid rgba(255, 255, 255, 0.72);
  border-radius: 26px;
  background: #fbf8ef;
  box-shadow: 0 35px 90px rgba(14, 32, 24, 0.25);
}

.slot-sheet__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.slot-sheet h2 {
  margin: 11px 0 7px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 37px;
  font-weight: 500;
}

.slot-sheet__head p:last-child {
  margin: 0;
  color: var(--muted);
  font-size: 11px;
}

.slot-sheet__number {
  color: #b1ad9f;
  font-family: var(--font-display);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.slot-grid {
  display: grid;
  grid-template-columns: 1.2fr 1fr 1fr 1fr;
  gap: 12px;
  margin-top: 27px;
}

.slot-grid label {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.slot-grid label > span {
  color: var(--muted);
  font-size: 9px;
  letter-spacing: 0.06em;
}

.slot-grid input,
.slot-grid select {
  width: 100%;
  height: 45px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  outline: 0;
  background: #fffdf8;
  color: var(--ink);
  font-size: 12px;
}

.slot-grid input:focus,
.slot-grid select:focus {
  border-color: rgba(36, 82, 65, 0.45);
}

.budget-input {
  display: flex;
  align-items: center;
  padding-left: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fffdf8;
}

.budget-input span {
  color: var(--muted);
  font-size: 11px;
}

.budget-input input {
  border: 0;
  background: transparent;
}

.slot-options {
  display: flex;
  gap: 8px;
  margin-top: 19px;
}

.slot-options button {
  padding: 8px 13px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: transparent;
  color: var(--muted-strong);
  font-size: 10px;
  cursor: pointer;
}

.slot-options button.active {
  border-color: var(--forest);
  background: var(--forest);
  color: #fff;
}

.slot-sheet__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 25px;
  padding-top: 20px;
  border-top: 1px solid var(--line);
}

.slot-sheet__footer p {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  color: var(--muted);
  font-size: 10px;
}

.slot-sheet__footer p svg {
  color: #63816d;
}

.sheet-enter-active,
.sheet-leave-active {
  transition: opacity 240ms ease;
}

.sheet-enter-active .slot-sheet,
.sheet-leave-active .slot-sheet {
  transition: transform 300ms cubic-bezier(0.2, 0.8, 0.2, 1);
}

.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}

.sheet-enter-from .slot-sheet,
.sheet-leave-to .slot-sheet {
  transform: translateY(35px) scale(0.98);
}

@media (max-width: 1000px) {
  .hero {
    gap: 35px;
  }

  .hero__visual {
    min-height: 500px;
  }

  .postcard {
    min-height: 430px;
  }

  .postcard__sky {
    height: 270px;
  }

  .inspiration-grid {
    grid-template-columns: 1.2fr 0.8fr;
    grid-template-rows: 340px 250px;
  }

  .place-card--prompt {
    grid-column: span 2;
  }

  .how-it-works {
    grid-template-columns: 1fr;
    gap: 50px;
  }
}

@media (max-width: 760px) {
  .hero {
    grid-template-columns: 1fr;
    padding-top: 42px;
  }

  .hero h1 {
    font-size: clamp(52px, 16vw, 74px);
  }

  .hero__lead {
    font-size: 14px;
  }

  .hero__visual {
    min-height: 430px;
    margin-top: 8px;
  }

  .postcard {
    left: 7%;
    width: 86%;
    min-height: 380px;
  }

  .postcard--back {
    left: 14%;
  }

  .postcard__sky {
    height: 225px;
  }

  .route-note {
    right: 0;
    bottom: 20px;
    min-width: 215px;
  }

  .planner-wrap {
    margin-top: 12px;
  }

  .planner-card {
    padding: 17px;
  }

  .ai-mode,
  .prompt-hint {
    display: none;
  }

  .prompt-box {
    flex-direction: column;
    align-items: stretch;
  }

  .prompt-submit {
    width: 100%;
  }

  .prompt-footer {
    overflow-x: auto;
  }

  .quick-prompts {
    min-width: max-content;
  }

  .inspiration {
    padding: 84px 0 76px;
  }

  .section-heading {
    display: block;
  }

  .section-heading > p {
    margin-top: 17px;
    text-align: left;
  }

  .inspiration-grid {
    grid-template-columns: 1fr;
    grid-template-rows: repeat(4, 330px);
  }

  .place-card--prompt {
    grid-column: auto;
  }

  .place-card__body {
    padding-top: 155px;
  }

  .how-it-works {
    padding: 76px 0 84px;
  }

  .steps {
    grid-template-columns: 1fr;
    gap: 38px;
  }

  .steps article {
    padding-left: 24px;
  }

  .steps article > svg {
    margin-bottom: 19px;
  }

  .footer {
    gap: 15px;
    flex-wrap: wrap;
  }

  .slot-backdrop {
    padding: 12px;
  }

  .slot-sheet {
    padding: 23px 19px;
    border-radius: 22px;
  }

  .slot-sheet h2 {
    font-size: 31px;
  }

  .slot-sheet__number {
    display: none;
  }

  .slot-grid {
    grid-template-columns: 1fr 1fr;
  }

  .slot-options {
    flex-wrap: wrap;
  }

  .slot-sheet__footer {
    align-items: flex-start;
    gap: 16px;
    flex-direction: column;
  }

  .slot-sheet__footer .primary-button {
    width: 100%;
  }
}
</style>
