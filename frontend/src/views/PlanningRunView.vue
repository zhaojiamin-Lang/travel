<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  AlertTriangle,
  ArrowLeft,
  Check,
  Clock3,
  LoaderCircle,
  RefreshCw,
  RotateCcw,
} from 'lucide-vue-next'
import SiteHeader from '../components/SiteHeader.vue'
import { api } from '../services/api'
import { useTripStore } from '../services/tripStore'
import type {
  OrchestrationResponse,
  PlanningAgentResult,
  PlanningRunStatus,
} from '../types'

const route = useRoute()
const router = useRouter()
const tripStore = useTripStore()
const runId = computed(() => String(route.params.runId))
const status = ref<PlanningRunStatus | null>(null)
const loading = ref(true)
const resuming = ref(false)
const error = ref('')
let pollTimer: number | undefined

const record = computed(() => tripStore.planningRun(runId.value))
const state = computed(() => status.value?.state || 'LOADING')
const failed = computed(() => state.value === 'FAILED')
const suspended = computed(() => state.value === 'SUSPENDED')
const succeeded = computed(() => state.value === 'SUCCEEDED')
const active = computed(() => [
  'CREATED',
  'RESTORING',
  'READY',
  'COMPACTING',
  'GUARDING',
  'EXECUTING',
  'BACKOFF',
  'COMMITTING',
  'CHECKPOINTED',
].includes(state.value))

const stateLabel = computed(() => ({
  LOADING: '正在读取任务',
  CREATED: '任务已创建',
  RESTORING: '正在恢复检查点',
  READY: '准备执行',
  COMPACTING: '正在整理上下文',
  GUARDING: '正在检查执行条件',
  EXECUTING: '正在生成行程',
  BACKOFF: '等待重试',
  COMMITTING: '正在保存步骤结果',
  CHECKPOINTED: '检查点已保存',
  SUSPENDED: '任务已暂停',
  SUCCEEDED: '规划已完成',
  FAILED: '规划失败',
}[state.value] || state.value))

const stateMessage = computed(() => {
  if (failed.value) {
    return status.value?.errorMessage || '规划没有完成，请返回首页重新发起。'
  }
  if (suspended.value) {
    return status.value?.errorMessage || '任务在安全检查点暂停，可以继续执行。'
  }
  if (succeeded.value) {
    return '所有规划步骤均已完成，可以载入最终行程。'
  }
  return '页面会自动刷新任务状态，请保持当前页面打开。'
})

const steps = computed(() => {
  const completed = new Set(status.value?.completedSteps || [])
  return [
    {
      id: 'normalize-requirements',
      title: '整理旅行需求',
      detail: '补全日期、天数、人数、预算与偏好',
    },
    {
      id: 'solve-and-validate',
      title: '排程与约束校验',
      detail: '生成可执行行程并校验预算和时间',
    },
  ].map((step, index) => ({
    ...step,
    state: completed.has(step.id)
      ? 'done'
      : status.value?.nextStepIndex === index
        ? 'current'
        : 'waiting',
  }))
})

onMounted(refresh)
onUnmounted(clearPoll)

async function refresh() {
  clearPoll()
  error.value = ''
  if (!status.value) loading.value = true
  try {
    status.value = await api.getPlanningRunStatus(runId.value)
    schedulePoll()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '任务状态读取失败'
  } finally {
    loading.value = false
  }
}

function schedulePoll() {
  clearPoll()
  if (!active.value) return
  pollTimer = window.setTimeout(refresh, 2500)
}

function clearPoll() {
  if (pollTimer !== undefined) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

async function recover() {
  if (!record.value || resuming.value) return
  resuming.value = true
  error.value = ''
  try {
    const response = await api.resumePlanningRun(
      runId.value,
      record.value.request,
    )
    if (await finishWithPlan(response.primaryResult)) return
    await refresh()
    if (!active.value && !suspended.value && !failed.value) {
      error.value = '任务已结束，但没有返回可用行程。'
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '恢复任务失败'
  } finally {
    resuming.value = false
  }
}

async function finishWithPlan(result?: PlanningAgentResult) {
  if (!result?.plan) return false
  tripStore.rememberPlan(result.plan)
  tripStore.forgetPlanningRun(runId.value)
  await router.replace({
    name: 'plan',
    params: { planId: result.plan.id },
  })
  return true
}

async function copyRunId() {
  try {
    await navigator.clipboard.writeText(runId.value)
  } catch {
    error.value = '浏览器未允许复制，请手动选择任务编号。'
  }
}
</script>

<template>
  <div class="run-page">
    <SiteHeader action-label="返回规划" @action="router.push('/')" />
    <main class="run-shell page-shell">
      <section class="run-intro">
        <button class="run-back" type="button" @click="router.push('/')">
          <ArrowLeft :size="16" />
          返回首页
        </button>
        <p class="eyebrow">Recoverable planning</p>
        <h1>行程没有丢，<br />只是停在了检查点。</h1>
        <p>
          规划任务每完成一步都会保存进度。你可以安全离开页面，
          稍后回来继续，不需要从头重复已经完成的步骤。
        </p>
      </section>

      <section class="run-card" aria-live="polite">
        <header class="run-card__head">
          <div class="run-orbit" :class="{ spinning: active || loading }">
            <LoaderCircle v-if="active || loading" :size="25" />
            <Check v-else-if="succeeded" :size="25" />
            <AlertTriangle v-else :size="25" />
          </div>
          <div>
            <span>{{ stateLabel }}</span>
            <h2>{{ stateMessage }}</h2>
          </div>
          <button class="icon-button" type="button" aria-label="刷新状态" @click="refresh">
            <RefreshCw :size="16" />
          </button>
        </header>

        <div class="run-meta">
          <button type="button" title="复制任务编号" @click="copyRunId">
            <span>RUN ID</span>
            <strong>{{ runId }}</strong>
          </button>
          <div>
            <span>WORKFLOW</span>
            <strong>{{ status?.workflowId || 'travel-planning' }}</strong>
          </div>
          <div>
            <span>STATE</span>
            <strong>{{ state }}</strong>
          </div>
        </div>

        <ol class="run-steps">
          <li
            v-for="(step, index) in steps"
            :key="step.id"
            :class="step.state"
          >
            <span class="step-marker">
              <Check v-if="step.state === 'done'" :size="15" />
              <LoaderCircle v-else-if="step.state === 'current' && active" :size="15" />
              <Clock3 v-else :size="15" />
            </span>
            <div>
              <small>STEP {{ index + 1 }}</small>
              <strong>{{ step.title }}</strong>
              <p>{{ step.detail }}</p>
            </div>
            <em>
              {{ step.state === 'done' ? '已完成' : step.state === 'current' ? '当前步骤' : '等待中' }}
            </em>
          </li>
        </ol>

        <div v-if="failed || suspended" class="run-alert">
          <div>
            <span>{{ failed ? '无法自动继续' : '任务可恢复' }}</span>
            <p>{{ failed ? status?.errorMessage || '请返回首页调整条件后重新规划。' : '使用保存在本机的原始请求继续执行。' }}</p>
          </div>
          <code v-if="status?.errorCode">{{ status.errorCode }}</code>
        </div>

        <p v-if="error" class="run-error">{{ error }}</p>
        <p v-if="!record && (suspended || succeeded)" class="run-error">
          当前浏览器没有原始请求记录，无法恢复这个任务。请返回首页重新发起。
        </p>

        <footer class="run-actions">
          <button class="ghost-button" type="button" @click="router.push('/')">
            返回修改
          </button>
          <button
            class="primary-button"
            type="button"
            :disabled="!record || resuming || failed"
            @click="recover"
          >
            <LoaderCircle v-if="resuming" class="spin" :size="16" />
            <RotateCcw v-else :size="16" />
            {{ resuming ? '正在恢复' : succeeded ? '载入规划结果' : '从检查点继续' }}
          </button>
        </footer>
      </section>
    </main>
  </div>
</template>

<style scoped>
.run-page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 82% 8%, rgba(214, 173, 111, 0.22), transparent 28%),
    radial-gradient(circle at 12% 82%, rgba(90, 128, 101, 0.16), transparent 25%),
    var(--cream);
}

.run-shell {
  display: grid;
  grid-template-columns: 0.82fr 1.18fr;
  gap: 72px;
  align-items: start;
  padding: 130px 0 90px;
}

.run-back {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 54px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--muted-strong);
  font-size: 10px;
  cursor: pointer;
}

.run-intro h1 {
  margin: 18px 0 24px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: clamp(45px, 5vw, 68px);
  font-weight: 500;
  letter-spacing: -0.055em;
  line-height: 1.04;
}

.run-intro > p:last-child {
  max-width: 470px;
  margin: 0;
  color: var(--muted-strong);
  font-size: 13px;
  line-height: 1.9;
}

.run-card {
  padding: 29px;
  border: 1px solid rgba(47, 75, 61, 0.12);
  border-radius: 22px;
  background: rgba(255, 253, 247, 0.84);
  box-shadow: 0 30px 80px rgba(32, 55, 43, 0.12);
  backdrop-filter: blur(18px);
}

.run-card__head {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 16px;
  align-items: center;
}

.run-orbit {
  display: grid;
  width: 49px;
  height: 49px;
  place-items: center;
  border-radius: 50%;
  background: rgba(66, 103, 81, 0.1);
  color: var(--forest);
}

.run-orbit.spinning svg {
  animation: spin 1.15s linear infinite;
}

.run-card__head span,
.run-meta span,
.run-steps small {
  color: var(--muted);
  font-size: 7px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.run-card__head h2 {
  margin: 6px 0 0;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 500;
  line-height: 1.35;
}

.icon-button {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: transparent;
  color: var(--forest);
  cursor: pointer;
}

.run-meta {
  display: grid;
  grid-template-columns: 1.6fr 1fr 0.8fr;
  gap: 8px;
  margin: 25px 0;
}

.run-meta > * {
  display: flex;
  min-width: 0;
  padding: 11px 12px;
  flex-direction: column;
  gap: 5px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: rgba(248, 244, 232, 0.48);
  text-align: left;
}

button.run-meta {
  cursor: copy;
}

.run-meta strong {
  overflow: hidden;
  color: var(--forest);
  font-family: monospace;
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.run-steps {
  display: flex;
  gap: 0;
  margin: 0;
  padding: 0;
  flex-direction: column;
  list-style: none;
}

.run-steps li {
  position: relative;
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  min-height: 82px;
  padding: 12px 0;
  border-top: 1px solid var(--line);
}

.step-marker {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 1px solid var(--line);
  border-radius: 50%;
  color: var(--muted);
}

.run-steps li.done .step-marker {
  border-color: rgba(57, 104, 76, 0.2);
  background: rgba(185, 210, 186, 0.38);
  color: #426e52;
}

.run-steps li.current .step-marker {
  border-color: rgba(197, 111, 74, 0.32);
  color: var(--clay);
}

.run-steps li.current .step-marker svg {
  animation: spin 1.15s linear infinite;
}

.run-steps li > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.run-steps strong {
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 17px;
}

.run-steps p {
  margin: 0;
  color: var(--muted);
  font-size: 8px;
}

.run-steps em {
  color: var(--muted);
  font-size: 8px;
  font-style: normal;
}

.run-steps li.done em {
  color: #56765f;
}

.run-alert {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 14px;
  padding: 13px 14px;
  border-radius: 11px;
  background: rgba(214, 173, 111, 0.13);
}

.run-alert span {
  color: var(--forest);
  font-size: 9px;
  font-weight: 700;
}

.run-alert p {
  margin: 4px 0 0;
  color: var(--muted-strong);
  font-size: 8px;
  line-height: 1.6;
}

.run-alert code {
  padding: 5px 7px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.55);
  color: #8a5a43;
  font-size: 7px;
}

.run-error {
  margin: 13px 0 0;
  color: #9d4c34;
  font-size: 9px;
  line-height: 1.6;
}

.run-actions {
  display: flex;
  justify-content: flex-end;
  gap: 9px;
  margin-top: 24px;
}

.run-actions button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 900px) {
  .run-shell {
    grid-template-columns: 1fr;
    gap: 34px;
    padding-top: 100px;
  }

  .run-back {
    margin-bottom: 34px;
  }
}

@media (max-width: 620px) {
  .run-card {
    padding: 20px;
    border-radius: 18px;
  }

  .run-meta {
    grid-template-columns: 1fr 1fr;
  }

  .run-meta > :first-child {
    grid-column: span 2;
  }

  .run-steps li {
    grid-template-columns: 34px minmax(0, 1fr);
  }

  .run-steps em {
    display: none;
  }
}
</style>
