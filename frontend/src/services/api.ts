/**
 * 模块：后端 API 客户端（services/api.ts）
 *
 * 职责：全站唯一的 HTTP 出口。统一处理 baseURL（默认 `/api`，可用
 * `VITE_API_BASE` 覆盖）、JSON 序列化、`credentials: 'include'`
 * （登录态走 Cookie/Session）、非 2xx 时抛出带后端 message 的错误。
 * 下方 `api` 对象按业务域分组：行程、账号、导游、重排，视图只调这里的方法，
 * 不直接写 fetch。
 */
import type {
  Bootstrap,
  DayOptimization,
  Draft,
  ExecutionProgress,
  AskResult,
  GuideSession,
  HistoryItem,
  LocationResult,
  OrchestrationRequest,
  OrchestrationResponse,
  Plan,
  PlanDiff,
  PlanExplanation,
  PlanIntent,
  ReplanProposal,
  Poi,
  RollbackResult,
  PlanningRunStatus,
  User,
  WeatherSnapshot,
} from '../types'

const API_BASE = import.meta.env.VITE_API_BASE || '/api'
const VISITOR_ID_KEY = 'travel-visitor-id'

function visitorId() {
  const saved = localStorage.getItem(VISITOR_ID_KEY)
  if (saved && /^usr_[a-f0-9]{32}$/.test(saved)) return saved
  const random = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID().replaceAll('-', '')
    : `${Date.now().toString(16)}${Math.random().toString(16).slice(2)}`.padEnd(32, '0').slice(0, 32)
  const value = `usr_${random}`
  localStorage.setItem(VISITOR_ID_KEY, value)
  return value
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export function createPlanningRunId() {
  const random = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`
  const suffix = random.replaceAll('-', '').slice(0, 16)
  return `run_${suffix}`
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      'X-Travel-Visitor-Id': visitorId(),
      ...(init?.headers || {}),
    },
  })

  if (!response.ok) {
    let message = '请求没有完成，请稍后再试。'
    try {
      const body = await response.json()
      message = body.message || message
    } catch {
      // Keep the friendly fallback.
    }
    throw new ApiError(message, response.status)
  }

  return response.json() as Promise<T>
}

export const api = {
  bootstrap() {
    return request<Bootstrap>('/bootstrap')
  },

  latestWeather() {
    return request<WeatherSnapshot>('/weather/latest')
  },

  startPlanningRun(runId: string, payload: OrchestrationRequest) {
    return request<OrchestrationResponse>('/orchestration/runs', {
      method: 'POST',
      headers: { 'X-Run-Id': runId },
      body: JSON.stringify(payload),
    })
  },

  getPlanningRunStatus(runId: string) {
    return request<PlanningRunStatus>(
      `/orchestration/runs/${encodeURIComponent(runId)}`,
    )
  },

  resumePlanningRun(runId: string, payload: OrchestrationRequest) {
    return request<OrchestrationResponse>(
      `/orchestration/runs/${encodeURIComponent(runId)}/resume`,
      {
        method: 'POST',
        body: JSON.stringify(payload),
      },
    )
  },

  optimizeDay(
    planId: string,
    dayNumber: number,
    payload: {
      delayMinutes?: number
      lockedItemIds?: string[]
      excludedItemIds?: string[]
      maxSolveSeconds?: number
    },
  ) {
    return request<DayOptimization>(`/optimizer/plans/${planId}/days/${dayNumber}`, {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },

  getProgress(planId: string) {
    return request<ExecutionProgress>(`/progress/${planId}`)
  },

  saveProgress(
    planId: string,
    payload: {
      currentItemId?: string
      completedItemIds: string[]
      status: ExecutionProgress['status']
      delayMinutes?: number
      expectedRevision: number
    },
  ) {
    return request<ExecutionProgress>(`/progress/${planId}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    })
  },

  pois() {
    return request<Poi[]>('/pois')
  },

  currentUser() {
    return request<User>('/auth/me').then((user) => {
      if (user.guest && /^usr_[a-f0-9]{32}$/.test(user.id)) {
        localStorage.setItem(VISITOR_ID_KEY, user.id)
      }
      return user
    })
  },

  register(payload: { email: string; password: string; displayName: string }) {
    return request<User>('/auth/register', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },

  login(payload: { email: string; password: string }) {
    return request<User>('/auth/login', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },

  logout() {
    return request<{ success: boolean }>('/auth/logout', { method: 'POST' })
  },

  history() {
    return request<HistoryItem[]>('/history')
  },

  updateRequirements(
    requestId: string,
    payload: {
      city?: string
      startDate: string
      days: number
      people: number
      budget: number
      pace: string
      noSpicy: boolean
    },
  ) {
    return request<Draft>(`/trips/${requestId}/requirements`, {
      method: 'PATCH',
      body: JSON.stringify(payload),
    })
  },

  getPlan(planId: string) {
    return request<Plan>(`/plans/${planId}`)
  },

  getVersions(requestId: string) {
    return request<Plan[]>(`/trips/${requestId}/plans`)
  },

  getAlternatives(planId: string, itemId: string) {
    return request<Poi[]>(`/plans/${planId}/items/${itemId}/alternatives`)
  },

  swapPoi(planId: string, itemId: string, replacementPoiId: string) {
    return request<Plan>(`/plans/${planId}/swap`, {
      method: 'POST',
      body: JSON.stringify({ itemId, replacementPoiId }),
    })
  },

  restoreVersion(planId: string) {
    return request<Plan>(`/plans/${planId}/restore`, { method: 'POST' })
  },

  getPlanDiff(planId: string, againstPlanId: string) {
    return request<PlanDiff>(
      `/plan-intelligence/plans/${planId}/diff?against=${encodeURIComponent(againstPlanId)}`,
    )
  },

  explainPlanDiff(planId: string, againstPlanId: string) {
    return request<PlanExplanation>(
      `/plan-intelligence/plans/${planId}/diff/${againstPlanId}/explain`,
      { method: 'POST' },
    )
  },

  rollbackPlan(planId: string) {
    return request<RollbackResult>(`/plan-intelligence/plans/${planId}/rollback`, {
      method: 'POST',
    })
  },

  interpretPlan(planId: string, message: string) {
    return request<PlanIntent>(`/plan-intelligence/plans/${planId}/interpret`, {
      method: 'POST',
      body: JSON.stringify({ message }),
    })
  },

  getReplanProposal(proposalId: string) {
    return request<ReplanProposal>(`/replan/proposals/${proposalId}`)
  },

  acceptReplanProposal(proposalId: string) {
    return request<Plan>(`/replan/proposals/${proposalId}/accept`, { method: 'POST' })
  },

  createGuide(planId: string) {
    return request<GuideSession>('/guide/sessions', {
      method: 'POST',
      body: JSON.stringify({ planId }),
    })
  },

  getGuide(sessionId: string) {
    return request<GuideSession>(`/guide/sessions/${sessionId}`)
  },

  locate(sessionId: string, latitude: number, longitude: number, accuracy?: number) {
    return request<LocationResult>(`/guide/sessions/${sessionId}/locate`, {
      method: 'POST',
      body: JSON.stringify({ latitude, longitude, accuracy }),
    })
  },

  selectPoi(sessionId: string, poiId: string) {
    return request<GuideSession>(`/guide/sessions/${sessionId}/select-poi`, {
      method: 'POST',
      body: JSON.stringify({ poiId }),
    })
  },

  ask(sessionId: string, question: string) {
    return request<AskResult>(
      `/guide/sessions/${sessionId}/ask`,
      {
        method: 'POST',
        body: JSON.stringify({ question }),
      },
    )
  },
}
