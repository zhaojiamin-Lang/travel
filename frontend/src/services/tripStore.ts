import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Bootstrap, Plan } from '../types'

export const useTripStore = defineStore('trip', () => {
  const plan = ref<Plan | null>(null)
  const bootstrap = ref<Bootstrap | null>(null)

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

  return { plan, bootstrap, rememberPlan, loadPlan, cachedPlan }
})
