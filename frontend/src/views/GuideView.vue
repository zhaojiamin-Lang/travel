<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  Clock3,
  Compass,
  Headphones,
  Info,
  LocateFixed,
  MapPin,
  Navigation,
  Pause,
  Play,
  Send,
  Sparkles,
  Ticket,
  Waves,
} from 'lucide-vue-next'
import BrandMark from '../components/BrandMark.vue'
import RouteMap from '../components/RouteMap.vue'
import { api } from '../services/api'
import { useTripStore } from '../services/tripStore'
import type {
  GuideContent,
  GuideSession,
  LocationCandidate,
  Plan,
  PlanItem,
} from '../types'

interface ChatMessage {
  role: 'user' | 'guide'
  text: string
}

const route = useRoute()
const router = useRouter()
const tripStore = useTripStore()
const plan = ref<Plan | null>(null)
const session = ref<GuideSession | null>(null)
const current = ref<GuideContent | null>(null)
const loading = ref(true)
const locating = ref(false)
const selecting = ref(false)
const asking = ref(false)
const error = ref('')
const locationMessage = ref('打开导游后，可用当前位置找到附近景点')
const locationStatus = ref<'idle' | 'ready' | 'error'>('idle')
const candidates = ref<LocationCandidate[]>([])
const speaking = ref(false)
const question = ref('')
const messages = ref<ChatMessage[]>([])
const chatEnd = ref<HTMLDivElement | null>(null)
let utterance: SpeechSynthesisUtterance | null = null

const planPois = computed<PlanItem[]>(() => {
  return plan.value?.dayPlans.flatMap((day) => day.items).filter((item) => item.type === 'POI') || []
})

const routePoints = computed(() => plan.value?.mapPoints || [])

onMounted(async () => {
  try {
    const planId = String(route.params.planId)
    plan.value = tripStore.plan?.id === planId ? tripStore.plan : await api.getPlan(planId)
    tripStore.loadPlan(plan.value)

    const storedSessionId = localStorage.getItem('guide-session-id')
    if (storedSessionId) {
      try {
        const stored = await api.getGuide(storedSessionId)
        if (stored.planId === planId) {
          session.value = stored
        }
      } catch {
        localStorage.removeItem('guide-session-id')
      }
    }

    if (!session.value) {
      session.value = await api.createGuide(planId)
      localStorage.setItem('guide-session-id', session.value.id)
    }

    const initialPoi =
      (typeof route.query.poi === 'string' && route.query.poi) ||
      session.value.currentPoi?.poiId ||
      plan.value.mapPoints[0]?.poiId
    if (initialPoi) {
      await selectPoi(initialPoi)
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '导游暂时无法打开'
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  window.speechSynthesis?.cancel()
})

async function selectPoi(poiId: string) {
  if (!session.value || selecting.value) return
  selecting.value = true
  error.value = ''
  stopSpeaking()
  try {
    const selected = await api.selectPoi(session.value.id, poiId)
    session.value = selected
    current.value = selected.currentPoi || null
    messages.value = []
    question.value = ''
    if (selected.currentPoi) {
      locationMessage.value = `正在为您讲解 ${selected.currentPoi.name}`
      locationStatus.value = 'ready'
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '无法加载景点资料'
  } finally {
    selecting.value = false
  }
}

function locateCurrent() {
  if (!session.value || locating.value) return
  if (!navigator.geolocation) {
    locationStatus.value = 'error'
    locationMessage.value = '当前浏览器不支持定位，请手动选择景点'
    return
  }
  locating.value = true
  locationStatus.value = 'idle'
  locationMessage.value = '正在获取你的位置…'
  navigator.geolocation.getCurrentPosition(
    async (position) => {
      try {
        const result = await api.locate(
          session.value!.id,
          position.coords.latitude,
          position.coords.longitude,
          position.coords.accuracy,
        )
        candidates.value = result.candidates
        locationMessage.value = result.message
        locationStatus.value = result.status === 'NOT_FOUND' ? 'error' : 'ready'
        if (result.status === 'FOUND' && result.candidates[0]) {
          await selectPoi(result.candidates[0].poi.id)
        }
      } catch (reason) {
        locationStatus.value = 'error'
        locationMessage.value = reason instanceof Error ? reason.message : '定位匹配失败'
      } finally {
        locating.value = false
      }
    },
    (geolocationError) => {
      locating.value = false
      locationStatus.value = 'error'
      locationMessage.value =
        geolocationError.code === geolocationError.PERMISSION_DENIED
          ? '你未授权定位，可以继续手动选择景点'
          : '定位失败，请手动选择景点'
    },
    { enableHighAccuracy: true, timeout: 9000, maximumAge: 30000 },
  )
}

async function simulateArrival() {
  if (!session.value || !plan.value || locating.value) return
  const currentPoint = current.value
    ? routePoints.value.find((point) => point.poiId === current.value?.poiId)
    : routePoints.value[0]
  if (!currentPoint) return
  locating.value = true
  locationMessage.value = '正在模拟当前位置…'
  try {
    const result = await api.locate(
      session.value.id,
      currentPoint.latitude + 0.00012,
      currentPoint.longitude + 0.00008,
      18,
    )
    candidates.value = result.candidates
    locationMessage.value = result.message
    locationStatus.value = result.status === 'NOT_FOUND' ? 'error' : 'ready'
    if (result.candidates[0]) {
      await selectPoi(result.candidates[0].poi.id)
    }
  } catch (reason) {
    locationStatus.value = 'error'
    locationMessage.value = reason instanceof Error ? reason.message : '模拟定位失败'
  } finally {
    locating.value = false
  }
}

function speak() {
  if (!current.value) return
  if (speaking.value) {
    stopSpeaking()
    return
  }
  if (!('speechSynthesis' in window)) {
    error.value = '当前浏览器不支持语音朗读，请继续阅读文字。'
    return
  }
  utterance = new SpeechSynthesisUtterance(current.value.narrative)
  utterance.lang = 'zh-CN'
  utterance.rate = 0.92
  utterance.pitch = 1
  utterance.onend = () => {
    speaking.value = false
  }
  utterance.onerror = () => {
    speaking.value = false
    error.value = '语音播放失败，但文字讲解仍可阅读。'
  }
  speaking.value = true
  window.speechSynthesis.speak(utterance)
}

function stopSpeaking() {
  window.speechSynthesis?.cancel()
  speaking.value = false
}

async function ask(value?: string) {
  const text = (value ?? question.value).trim()
  if (!text || !session.value || asking.value) return
  messages.value.push({ role: 'user', text })
  question.value = ''
  asking.value = true
  error.value = ''
  await scrollChat()
  try {
    const result = await api.ask(session.value.id, text)
    messages.value.push({ role: 'guide', text: result.answer })
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '暂时没能回答这个问题'
  } finally {
    asking.value = false
    await scrollChat()
  }
}

async function scrollChat() {
  await nextTick()
  chatEnd.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}

function goBack() {
  if (plan.value) {
    router.push({ name: 'plan', params: { planId: plan.value.id } })
  } else {
    router.push('/')
  }
}

function confidenceLabel(value: number) {
  return value >= 80 ? '高置信度' : value >= 60 ? '可能位置' : '请确认'
}

function poiAccent(index: number) {
  const colors = ['#C96B45', '#5F7651', '#806B55', '#56748B']
  return colors[index % colors.length]
}
</script>

<template>
  <div class="guide-page">
    <div v-if="loading" class="guide-loading">
      <Compass :size="32" />
      <span>正在连接这座城市的现场故事...</span>
    </div>

    <div v-else-if="error && !current" class="guide-error">
      <span><Info :size="22" /></span>
      <h1>暂时没听见城市的声音</h1>
      <p>{{ error }}</p>
      <button class="primary-button" type="button" @click="goBack">返回行程</button>
    </div>

    <template v-else>
      <section class="guide-map">
        <RouteMap
          :points="routePoints"
          :active-poi-id="current?.poiId"
          :map-key="tripStore.bootstrap?.mapKey"
          mode="guide"
        />

        <header class="guide-topbar">
          <button class="round-icon round-icon--dark" type="button" aria-label="返回行程" @click="goBack">
            <ArrowLeft :size="18" />
          </button>
          <BrandMark light compact />
          <span class="on-air"><i></i> FIELD GUIDE</span>
        </header>

        <div class="location-card">
          <span class="location-card__icon">
            <LocateFixed :size="18" />
          </span>
          <div>
            <span :class="{ error: locationStatus === 'error' }">
              {{ locationStatus === 'ready' ? '当前位置已连接' : '现场模式' }}
            </span>
            <p>{{ locationMessage }}</p>
          </div>
          <button type="button" :disabled="locating" @click="locateCurrent">
            <span v-if="locating" class="spinner spinner--dark"></span>
            <Navigation v-else :size="16" />
          </button>
        </div>

        <div v-if="candidates.length > 1" class="candidate-list">
          <button
            v-for="candidate in candidates"
            :key="candidate.poi.id"
            type="button"
            :class="{ active: current?.poiId === candidate.poi.id }"
            @click="selectPoi(candidate.poi.id)"
          >
            <span>{{ candidate.poi.name }}</span>
            <small>{{ Math.round(candidate.distanceMeters) }}m · {{ confidenceLabel(candidate.confidence) }}</small>
          </button>
        </div>

        <div class="map-controls">
          <button type="button" title="模拟到达当前景点" @click="simulateArrival">
            <Sparkles :size="15" />
            <span>演示定位</span>
          </button>
        </div>

        <div class="guide-map__caption">
          <span>31°14′N</span>
          <div>
            <Waves :size="14" />
            <p>Page open · location active only while visible</p>
          </div>
          <span>{{ plan?.city }} / 2026</span>
        </div>
      </section>

      <section class="guide-content">
        <header class="guide-content__header">
          <div>
            <p class="eyebrow">{{ current?.eyebrow || 'Field guide' }}</p>
            <h1>{{ current?.name || '选择一个景点' }}</h1>
            <div class="guide-location">
              <MapPin :size="14" />
              <span>{{ current?.area || plan?.city }} · 现场讲解</span>
            </div>
          </div>
          <button class="listen-button" type="button" :class="{ active: speaking }" @click="speak">
            <span class="listen-button__icon">
              <Pause v-if="speaking" :size="17" />
              <Play v-else :size="17" fill="currentColor" />
            </span>
            <span>{{ speaking ? '暂停讲解' : '听讲解' }}</span>
            <span class="sound-wave" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
          </button>
        </header>

        <div v-if="error" class="guide-inline-error">{{ error }}</div>

        <article v-if="current" class="story-card">
          <span class="story-card__quote">“</span>
          <p>{{ current.narrative }}</p>
          <footer>
            <span>此刻 · {{ current.name }}</span>
            <span class="story-card__line"></span>
            <Headphones :size="15" />
          </footer>
        </article>

        <div v-if="current" class="fact-grid">
          <article>
            <Clock3 :size="17" />
            <span>开放时间</span>
            <strong>{{ current.opening }}</strong>
          </article>
          <article>
            <Ticket :size="17" />
            <span>参考门票</span>
            <strong>{{ current.ticket }}</strong>
          </article>
          <article>
            <CheckCircle2 :size="17" />
            <span>资料来源</span>
            <strong>{{ current.sourceName }}</strong>
          </article>
        </div>

        <section v-if="current" class="history-note">
          <span>THE LONGER STORY</span>
          <p>{{ current.history }}</p>
          <a v-if="current.sourceUrl" :href="current.sourceUrl" target="_blank" rel="noreferrer">
            查看公开资料来源 <ArrowRight :size="14" />
          </a>
        </section>

        <section class="guide-chat">
          <div class="guide-chat__head">
            <div>
              <span><Sparkles :size="14" /> 继续问，漫游在这里</span>
              <p>回答只围绕当前景点与本次行程。</p>
            </div>
            <span class="guide-chat__status">AI GUIDE</span>
          </div>

          <div v-if="messages.length" class="chat-messages">
            <div
              v-for="(message, index) in messages"
              :key="index"
              class="chat-message"
              :class="`chat-message--${message.role}`"
            >
              <span v-if="message.role === 'guide'" class="chat-avatar">漫</span>
              <p>{{ message.text }}</p>
            </div>
            <div v-if="asking" class="chat-message chat-message--guide">
              <span class="chat-avatar">漫</span>
              <p class="typing"><i></i><i></i><i></i></p>
            </div>
            <div ref="chatEnd"></div>
          </div>

          <div class="question-chips">
            <button type="button" @click="ask('这里建议停留多久？')">建议停留多久</button>
            <button type="button" @click="ask('附近还能看什么？')">附近还能看什么</button>
            <button type="button" @click="ask('给我一段更短的讲解。')">更短的讲解</button>
          </div>

          <form class="question-box" @submit.prevent="ask()">
            <input v-model="question" type="text" placeholder="问问眼前的风景……" />
            <button type="submit" :disabled="asking || !question.trim()" aria-label="发送问题">
              <Send :size="17" />
            </button>
          </form>
        </section>

        <section v-if="current?.nearby.length" class="nearby-section">
          <div class="nearby-section__head">
            <div>
              <p class="eyebrow">Keep wandering</p>
              <h2>附近，也值得停一停</h2>
            </div>
            <span>{{ current.nearby.length }} PLACES</span>
          </div>
          <div class="nearby-grid">
            <button
              v-for="(poi, index) in current.nearby"
              :key="poi.id"
              type="button"
              :style="{ '--near-accent': poiAccent(index) }"
              @click="selectPoi(poi.id)"
            >
              <span class="nearby-grid__art">
                <span>{{ poi.name.slice(0, 1) }}</span>
              </span>
              <span class="nearby-grid__copy">
                <small>{{ poi.area }} · {{ poi.durationMinutes }} MIN</small>
                <strong>{{ poi.name }}</strong>
                <span>{{ poi.tags[0] }}</span>
              </span>
              <ArrowRight :size="15" />
            </button>
          </div>
        </section>

        <footer class="guide-footer">
          <span>慢一点，看得更多。</span>
          <button type="button" @click="goBack">返回完整行程 <ArrowRight :size="14" /></button>
        </footer>
      </section>
    </template>
  </div>
</template>

<style scoped>
.guide-page {
  display: grid;
  min-height: 100vh;
  grid-template-columns: minmax(360px, 45vw) minmax(0, 1fr);
  background: #f7f3e9;
}

.guide-loading,
.guide-error {
  grid-column: 1 / -1;
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  background: var(--cream);
}

.guide-loading svg {
  color: var(--forest);
  animation: spin 3s linear infinite;
}

.guide-loading span {
  margin-top: 17px;
  color: var(--muted);
  font-size: 11px;
}

.guide-error > span {
  display: grid;
  width: 54px;
  height: 54px;
  place-items: center;
  border-radius: 50%;
  background: rgba(201, 103, 67, 0.1);
  color: var(--clay);
}

.guide-error h1 {
  margin: 19px 0 7px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 38px;
}

.guide-error p {
  margin: 0 0 22px;
  color: var(--muted);
  font-size: 11px;
}

.guide-map {
  position: sticky;
  top: 0;
  height: 100vh;
  min-height: 640px;
  overflow: hidden;
  background: var(--forest);
}

.guide-topbar {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22px 24px;
}

.round-icon {
  display: grid;
  width: 39px;
  height: 39px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  cursor: pointer;
  backdrop-filter: blur(12px);
}

.on-air {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: rgba(255, 255, 255, 0.54);
  font-size: 8px;
  letter-spacing: 0.15em;
}

.on-air i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ddb876;
  box-shadow: 0 0 0 4px rgba(221, 184, 118, 0.12);
}

.location-card {
  position: absolute;
  top: 91px;
  right: 24px;
  left: 24px;
  z-index: 5;
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 12px;
  align-items: center;
  padding: 13px 14px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 14px;
  background: rgba(238, 230, 211, 0.11);
  color: #fff;
  backdrop-filter: blur(18px);
}

.location-card__icon {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 50%;
  background: rgba(240, 185, 115, 0.16);
  color: #f0c385;
}

.location-card div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.location-card div > span {
  color: rgba(255, 255, 255, 0.86);
  font-size: 10px;
  font-weight: 600;
}

.location-card div > span.error {
  color: #f1b398;
}

.location-card p {
  margin: 0;
  color: rgba(255, 255, 255, 0.5);
  font-size: 8px;
  line-height: 1.5;
}

.location-card > button {
  display: grid;
  width: 35px;
  height: 35px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  cursor: pointer;
}

.spinner--dark {
  border-color: rgba(255, 255, 255, 0.2);
  border-top-color: #fff;
}

.candidate-list {
  position: absolute;
  top: 174px;
  right: 24px;
  left: 24px;
  z-index: 6;
  display: flex;
  gap: 7px;
  overflow-x: auto;
}

.candidate-list button {
  display: flex;
  min-width: max-content;
  flex-direction: column;
  gap: 3px;
  align-items: flex-start;
  padding: 9px 12px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 9px;
  background: rgba(23, 63, 50, 0.7);
  color: #fff;
  cursor: pointer;
  backdrop-filter: blur(12px);
}

.candidate-list button.active {
  border-color: rgba(239, 183, 111, 0.7);
}

.candidate-list button span {
  font-size: 9px;
}

.candidate-list button small {
  color: rgba(255, 255, 255, 0.45);
  font-size: 7px;
}

.map-controls {
  position: absolute;
  right: 24px;
  bottom: 89px;
  z-index: 5;
}

.map-controls button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 9px 12px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 999px;
  background: rgba(22, 58, 45, 0.65);
  color: rgba(255, 255, 255, 0.68);
  font-size: 8px;
  cursor: pointer;
  backdrop-filter: blur(12px);
}

.guide-map__caption {
  position: absolute;
  right: 24px;
  bottom: 22px;
  left: 24px;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: rgba(255, 255, 255, 0.28);
  font-size: 6px;
  letter-spacing: 0.14em;
}

.guide-map__caption div {
  display: flex;
  align-items: center;
  gap: 6px;
}

.guide-map__caption p {
  margin: 0;
}

.guide-content {
  max-height: 100vh;
  padding: 58px clamp(34px, 5vw, 75px) 35px;
  overflow-y: auto;
  background:
    radial-gradient(circle at 94% 4%, rgba(206, 184, 147, 0.22), transparent 23%),
    #f8f4ea;
}

.guide-content__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 25px;
}

.guide-content__header h1 {
  margin: 16px 0 9px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: clamp(45px, 5vw, 70px);
  font-weight: 500;
  letter-spacing: -0.06em;
  line-height: 0.95;
}

.guide-location {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--muted);
  font-size: 9px;
}

.listen-button {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 9px;
  padding: 8px 12px 8px 8px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.55);
  color: var(--forest);
  font-size: 9px;
  cursor: pointer;
}

.listen-button__icon {
  display: grid;
  width: 33px;
  height: 33px;
  place-items: center;
  border-radius: 50%;
  background: var(--forest);
  color: #fff;
}

.listen-button.active .listen-button__icon {
  background: var(--clay);
}

.sound-wave {
  display: flex;
  align-items: center;
  gap: 2px;
  height: 13px;
}

.sound-wave i {
  width: 1px;
  height: 5px;
  border-radius: 2px;
  background: var(--clay);
}

.sound-wave i:nth-child(2) {
  height: 10px;
}

.sound-wave i:nth-child(3) {
  height: 7px;
}

.listen-button.active .sound-wave i {
  animation: sound 700ms ease-in-out infinite alternate;
}

.listen-button.active .sound-wave i:nth-child(2) {
  animation-delay: 150ms;
}

.listen-button.active .sound-wave i:nth-child(3) {
  animation-delay: 300ms;
}

.guide-inline-error {
  margin-top: 18px;
  padding: 10px 13px;
  border-radius: 8px;
  background: rgba(201, 103, 67, 0.08);
  color: #9e4d33;
  font-size: 9px;
}

.story-card {
  position: relative;
  margin-top: 40px;
  padding: 32px 32px 23px;
  overflow: hidden;
  border: 1px solid rgba(30, 75, 57, 0.11);
  border-radius: 18px;
  background:
    radial-gradient(circle at 87% 23%, rgba(222, 198, 151, 0.32), transparent 25%),
    #fffdf7;
  box-shadow: 0 18px 48px rgba(45, 61, 50, 0.07);
}

.story-card::after {
  position: absolute;
  top: -40px;
  right: -42px;
  width: 160px;
  height: 160px;
  border: 1px solid rgba(42, 85, 66, 0.08);
  border-radius: 50%;
  box-shadow: 0 0 0 27px rgba(42, 85, 66, 0.025);
  content: "";
}

.story-card__quote {
  position: absolute;
  top: 13px;
  left: 20px;
  color: rgba(201, 103, 67, 0.15);
  font-family: var(--font-display);
  font-size: 80px;
  line-height: 1;
}

.story-card > p {
  position: relative;
  z-index: 1;
  max-width: 690px;
  margin: 13px 0 27px;
  color: #315142;
  font-family: var(--font-display);
  font-size: clamp(20px, 2.1vw, 28px);
  line-height: 1.65;
}

.story-card footer {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #a39d8c;
  font-size: 8px;
  letter-spacing: 0.08em;
}

.story-card__line {
  height: 1px;
  flex: 1;
  background: var(--line);
}

.fact-grid {
  display: grid;
  grid-template-columns: 1.2fr 1fr 1.1fr;
  gap: 9px;
  margin-top: 13px;
}

.fact-grid article {
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: rgba(255, 253, 247, 0.54);
}

.fact-grid svg {
  margin-bottom: 13px;
  color: var(--clay);
}

.fact-grid span {
  margin-bottom: 5px;
  color: var(--muted);
  font-size: 7px;
  letter-spacing: 0.08em;
}

.fact-grid strong {
  overflow: hidden;
  color: var(--forest);
  font-size: 9px;
  font-weight: 500;
  line-height: 1.5;
  text-overflow: ellipsis;
}

.history-note {
  margin-top: 31px;
  padding: 0 2px 29px;
  border-bottom: 1px solid var(--line);
}

.history-note > span {
  color: var(--clay);
  font-size: 7px;
  font-weight: 700;
  letter-spacing: 0.16em;
}

.history-note > p {
  max-width: 750px;
  margin: 12px 0 14px;
  color: var(--muted-strong);
  font-size: 11px;
  line-height: 1.9;
}

.history-note a {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--forest);
  font-size: 8px;
  text-decoration: none;
}

.guide-chat {
  margin-top: 29px;
}

.guide-chat__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.guide-chat__head > div > span {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 600;
}

.guide-chat__head p {
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 8px;
}

.guide-chat__status {
  padding: 5px 8px;
  border-radius: 999px;
  background: rgba(40, 88, 68, 0.08);
  color: #5b7867;
  font-size: 6px;
  letter-spacing: 0.14em;
}

.chat-messages {
  display: flex;
  gap: 11px;
  margin-top: 18px;
  flex-direction: column;
}

.chat-message {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.chat-message p {
  max-width: 80%;
  margin: 0;
  padding: 11px 13px;
  border-radius: 4px 13px 13px 13px;
  background: #fffdf7;
  color: var(--muted-strong);
  font-size: 10px;
  line-height: 1.7;
}

.chat-message--user {
  justify-content: flex-end;
}

.chat-message--user p {
  color: #fff;
  border-radius: 13px 4px 13px 13px;
  background: var(--forest-soft);
}

.chat-avatar {
  display: grid;
  flex: 0 0 auto;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 50%;
  background: var(--forest);
  color: #eac383;
  font-family: var(--font-display);
  font-size: 10px;
}

.typing {
  display: flex;
  gap: 4px;
}

.typing i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #8fa294;
  animation: typing 800ms ease-in-out infinite alternate;
}

.typing i:nth-child(2) {
  animation-delay: 160ms;
}

.typing i:nth-child(3) {
  animation-delay: 320ms;
}

.question-chips {
  display: flex;
  gap: 7px;
  margin-top: 15px;
  flex-wrap: wrap;
}

.question-chips button {
  padding: 7px 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.44);
  color: var(--muted-strong);
  font-size: 8px;
  cursor: pointer;
}

.question-chips button:hover {
  border-color: rgba(41, 83, 65, 0.3);
  color: var(--forest);
}

.question-box {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 11px;
  padding: 6px 6px 6px 15px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fffdf7;
}

.question-box:focus-within {
  border-color: rgba(39, 84, 65, 0.35);
}

.question-box input {
  height: 38px;
  flex: 1;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-size: 10px;
}

.question-box button {
  display: grid;
  width: 37px;
  height: 37px;
  place-items: center;
  border: 0;
  border-radius: 9px;
  background: var(--forest);
  color: #fff;
  cursor: pointer;
}

.question-box button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.nearby-section {
  margin-top: 44px;
  padding-top: 31px;
  border-top: 1px solid var(--line);
}

.nearby-section__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
}

.nearby-section__head h2 {
  margin: 12px 0 0;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 29px;
  font-weight: 500;
  letter-spacing: -0.03em;
}

.nearby-section__head > span {
  color: #9b9b92;
  font-size: 7px;
  letter-spacing: 0.12em;
}

.nearby-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 9px;
  margin-top: 18px;
}

.nearby-grid button {
  --near-accent: #c96b45;
  display: grid;
  min-width: 0;
  grid-template-columns: 50px minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: rgba(255, 253, 247, 0.62);
  text-align: left;
  cursor: pointer;
  transition: transform 180ms ease, background 180ms ease;
}

.nearby-grid button:hover {
  transform: translateY(-2px);
  background: #fffdf7;
}

.nearby-grid__art {
  display: grid;
  width: 50px;
  height: 58px;
  place-items: center;
  border-radius: 7px;
  background:
    radial-gradient(circle at 70% 20%, rgba(255, 255, 255, 0.3), transparent 25%),
    var(--near-accent);
}

.nearby-grid__art span {
  color: rgba(255, 255, 255, 0.27);
  font-family: var(--font-display);
  font-size: 35px;
}

.nearby-grid__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.nearby-grid__copy small {
  overflow: hidden;
  color: #a09e94;
  font-size: 6px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nearby-grid__copy strong {
  overflow: hidden;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nearby-grid__copy > span {
  color: var(--muted);
  font-size: 7px;
}

.nearby-grid button > svg {
  color: #9ca097;
}

.guide-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 48px;
  padding-top: 20px;
  border-top: 1px solid var(--line);
  color: #9b9c93;
  font-size: 8px;
}

.guide-footer button {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--forest);
  font-size: 8px;
  cursor: pointer;
}

@keyframes sound {
  to {
    height: 13px;
  }
}

@keyframes typing {
  to {
    opacity: 0.25;
    transform: translateY(-2px);
  }
}

@media (max-width: 1100px) {
  .guide-page {
    grid-template-columns: 40vw minmax(0, 1fr);
  }

  .guide-content {
    padding-inline: 34px;
  }

  .fact-grid,
  .nearby-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .guide-page {
    display: block;
  }

  .guide-map {
    position: relative;
    height: 55vh;
    min-height: 440px;
  }

  .guide-topbar {
    padding: 16px;
  }

  .location-card {
    top: 75px;
    right: 16px;
    left: 16px;
  }

  .candidate-list {
    top: 156px;
    right: 16px;
    left: 16px;
  }

  .map-controls {
    right: 16px;
    bottom: 72px;
  }

  .guide-map__caption {
    right: 16px;
    bottom: 17px;
    left: 16px;
  }

  .guide-map__caption div {
    display: none;
  }

  .guide-content {
    max-height: none;
    overflow: visible;
    padding: 38px 20px 26px;
  }

  .guide-content__header {
    gap: 10px;
  }

  .guide-content__header h1 {
    font-size: 48px;
  }

  .listen-button {
    padding: 6px;
  }

  .listen-button > span:not(.listen-button__icon) {
    display: none;
  }

  .story-card {
    padding: 27px 21px 19px;
  }

  .story-card > p {
    font-size: 22px;
  }

  .fact-grid {
    grid-template-columns: 1fr 1fr;
  }

  .fact-grid article:last-child {
    grid-column: span 2;
  }

  .nearby-grid {
    grid-template-columns: 1fr;
  }

  .guide-footer {
    gap: 14px;
  }
}
</style>
