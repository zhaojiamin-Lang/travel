import type {
  Bootstrap,
  Draft,
  GuideSession,
  LocationResult,
  Plan,
  Poi,
} from '../types'

const API_BASE = import.meta.env.VITE_API_BASE || '/api'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
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

  getPlan(planId: string) {
    return request<Plan>(`/plans/${planId}`)
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
