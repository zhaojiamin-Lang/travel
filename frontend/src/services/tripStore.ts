/**
 * 模块：行程状态与离线缓存（services/tripStore.ts）
 *
 * 职责：Pinia store，缓存「当前行程」和「启动配置」，并把行程写进
 * localStorage（`last-plan-id`、`plan-cache-<id>`）。
 * 用途：刷新页面或断网时，导游页仍能靠 `cachedPlan()` 读出上一份行程，
 * 这是 PWA 离线可用的一半（另一半是 public/sw.js）。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Bootstrap, Plan, StoredPlanningRun } from '../types'

const PLANNING_RUNS_KEY = 'planning-runs'

function savedPlanningRuns(): Record<string, StoredPlanningRun> {
  try {
    return JSON.parse(localStorage.getItem(PLANNING_RUNS_KEY) || '{}') as
      Record<string, StoredPlanningRun>
  } catch {
    return {}
  }
}

export const useTripStore = defineStore('trip', () => {
  const plan = ref<Plan | null>(null)
  const bootstrap = ref<Bootstrap | null>(null)
  const planningRuns = ref<Record<string, StoredPlanningRun>>(savedPlanningRuns())

  function rememberPlan(nextPlan: Plan) {
    plan.value = nextPlan
    localStorage.setItem('last-plan-id', nextPlan.id)
    localStorage.setItem(`plan-cache-${nextPlan.id}`, JSON.stringify(nextPlan))
  }

  function loadPlan(nextPlan: Plan) {
    plan.value = nextPlan
    localStorage.setItem(`plan-cache-${nextPlan.id}`, JSON.stringify(nextPlan))
  }

  function cachedPlan(planId: string): Plan | null {
    const value = localStorage.getItem(`plan-cache-${planId}`)
    if (!value) return null
    try {
      return JSON.parse(value) as Plan
    } catch {
      return null
    }
  }

  function rememberPlanningRun(run: StoredPlanningRun) {
    planningRuns.value = {
      ...planningRuns.value,
      [run.runId]: run,
    }
    localStorage.setItem(PLANNING_RUNS_KEY, JSON.stringify(planningRuns.value))
  }

  function planningRun(runId: string) {
    return planningRuns.value[runId] || null
  }

  function latestPlanningRun() {
    return Object.values(planningRuns.value).sort((left, right) =>
      right.createdAt.localeCompare(left.createdAt),
    )[0] || null
  }

  function forgetPlanningRun(runId: string) {
    const next = { ...planningRuns.value }
    delete next[runId]
    planningRuns.value = next
    localStorage.setItem(PLANNING_RUNS_KEY, JSON.stringify(next))
  }

  return {
    plan,
    bootstrap,
    planningRuns,
    rememberPlan,
    loadPlan,
    cachedPlan,
    rememberPlanningRun,
    planningRun,
    latestPlanningRun,
    forgetPlanningRun,
  }
})
