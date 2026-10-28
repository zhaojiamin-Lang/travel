<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, CloudSun, RefreshCw, X } from 'lucide-vue-next'

interface Notice {
  id: string
  kind: 'weather' | 'proposal'
  title: string
  message: string
  planId?: string
}

interface WeatherEvent {
  city: string
  status: string
  forecasts: Array<{
    date: string
    dayWeather: string
    nightWeather: string
    dayTemperature: string
    nightTemperature: string
  }>
}

interface ProposalEvent {
  proposalId: string
  planId: string
  eventType: string
  summary: string
}

const router = useRouter()
const notices = ref<Notice[]>([])
const connected = ref(false)
let source: EventSource | null = null
let lastWeatherKey = ''

onMounted(() => {
  if (!('EventSource' in window)) return
  connect()
})

onBeforeUnmount(() => source?.close())

function connect() {
  source?.close()
  source = new EventSource('/api/notifications/stream')

  source.addEventListener('connected', () => {
    connected.value = true
  })

  source.addEventListener('weather', (event) => {
    const data = JSON.parse((event as MessageEvent).data) as WeatherEvent
    const key = `${data.city}-${data.forecasts[0]?.date}-${data.forecasts[0]?.dayWeather}`
    if (key === lastWeatherKey) return
    lastWeatherKey = key
    const today = data.forecasts[0]
    addNotice({
      id: `weather-${key}`,
      kind: 'weather',
      title: `${data.city}天气更新`,
      message: today
        ? `${today.dayWeather}，${today.nightTemperature}–${today.dayTemperature}℃。行程中请注意现场天气变化。`
        : '天气预报已经更新，出发前请再次确认。',
    })
  })

  source.addEventListener('replan-proposal', (event) => {
    const data = JSON.parse((event as MessageEvent).data) as ProposalEvent
    addNotice({
      id: data.proposalId,
      kind: 'proposal',
      title: data.eventType === 'CLOSURE' ? '发现景点闭馆调整方案' : '发现新的行程调整方案',
      message: data.summary,
      planId: data.planId,
    })
  })

  source.onerror = () => {
    connected.value = false
  }
}

function addNotice(notice: Notice) {
  if (notices.value.some((item) => item.id === notice.id)) return
  notices.value.push(notice)
  window.setTimeout(() => dismiss(notice.id), notice.kind === 'proposal' ? 15000 : 8000)
}

function dismiss(id: string) {
  notices.value = notices.value.filter((notice) => notice.id !== id)
}

function openProposal(notice: Notice) {
  if (notice.planId) {
    router.push({ name: 'plan', params: { planId: notice.planId } })
  }
  dismiss(notice.id)
}
</script>

<template>
  <aside class="notification-stack" aria-live="polite" aria-label="实时旅行通知">
    <TransitionGroup name="notice">
      <article
        v-for="notice in notices"
        :key="notice.id"
        class="notification-card"
        :class="`notification-card--${notice.kind}`"
      >
        <span class="notification-icon">
          <CloudSun v-if="notice.kind === 'weather'" :size="18" />
          <RefreshCw v-else :size="18" />
        </span>
        <div class="notification-copy">
          <span>{{ notice.kind === 'weather' ? 'LIVE WEATHER' : 'PLAN UPDATE' }}</span>
          <strong>{{ notice.title }}</strong>
          <p>{{ notice.message }}</p>
          <button v-if="notice.planId" type="button" @click="openProposal(notice)">
            查看调整方案 <ArrowRight :size="13" />
          </button>
        </div>
        <button class="notification-close" type="button" aria-label="关闭通知" @click="dismiss(notice.id)">
          <X :size="14" />
        </button>
      </article>
    </TransitionGroup>

    <span v-if="!connected && notices.length === 0" class="notification-status">
      实时通知正在重连
    </span>
  </aside>
</template>

<style scoped>
.notification-stack {
  position: fixed;
  top: 22px;
  right: 22px;
  z-index: 300;
  display: flex;
  width: min(360px, calc(100vw - 32px));
  gap: 10px;
  flex-direction: column;
  pointer-events: none;
}

.notification-card {
  position: relative;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) 24px;
  gap: 11px;
  padding: 15px;
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 15px;
  background: rgba(255, 252, 245, 0.94);
  box-shadow: 0 18px 45px rgba(25, 47, 36, 0.18);
  pointer-events: auto;
  backdrop-filter: blur(16px);
}

.notification-card--proposal {
  border-color: rgba(191, 112, 72, 0.32);
}

.notification-icon {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 50%;
  background: rgba(71, 117, 91, 0.12);
  color: #4a7257;
}

.notification-card--proposal .notification-icon {
  background: rgba(197, 104, 67, 0.12);
  color: var(--clay);
}

.notification-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.notification-copy > span {
  color: #9a8d78;
  font-size: 6px;
  font-weight: 700;
  letter-spacing: 0.15em;
}

.notification-copy strong {
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 15px;
}

.notification-copy p {
  margin: 0;
  color: var(--muted);
  font-size: 9px;
  line-height: 1.6;
}

.notification-copy button {
  display: inline-flex;
  width: max-content;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--forest);
  font-size: 8px;
  font-weight: 600;
  cursor: pointer;
}

.notification-close {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #94978f;
  cursor: pointer;
}

.notification-status {
  align-self: flex-end;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(255, 252, 245, 0.76);
  color: var(--muted);
  font-size: 7px;
  pointer-events: auto;
}

.notice-enter-active,
.notice-leave-active {
  transition: opacity 220ms ease, transform 220ms ease;
}

.notice-enter-from,
.notice-leave-to {
  opacity: 0;
  transform: translateX(20px);
}

@media (max-width: 640px) {
  .notification-stack {
    top: 12px;
    right: 12px;
  }
}
</style>
