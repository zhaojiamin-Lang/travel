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
  Draft,
  GuideSession,
  HistoryItem,
  LocationResult,
  Plan,
  Poi,
  User,
} from '../types'

const API_BASE = import.meta.env.VITE_API_BASE || '/api'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
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
    throw new Error(message)
  }

  return response.json() as Promise<T>
}

export const api = {
  bootstrap() {
    return request<Bootstrap>('/bootstrap')
  },

  pois() {
    return request<Poi[]>('/pois')
  },

  createDraft(prompt: string) {
    return request<Draft>('/trips/draft', {
      method: 'POST',
      body: JSON.stringify({
        prompt,
        sessionId: localStorage.getItem('travel-session') || 'web-demo',
      }),
    })
  },

  currentUser() {
    return request<User>('/auth/me')
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

  generatePlan(requestId: string) {
    return request<Plan>(`/trips/${requestId}/plans`, {
      method: 'POST',
    })
  },

  updateFromNaturalLanguage(requestId: string, message: string) {
    return request<Draft>(`/trips/${requestId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ message }),
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
    return request<{ answer: string; followUps: string[] }>(
      `/guide/sessions/${sessionId}/ask`,
      {
        method: 'POST',
        body: JSON.stringify({ question }),
      },
    )
  },
}
