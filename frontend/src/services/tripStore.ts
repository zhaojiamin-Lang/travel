import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Bootstrap, Plan } from '../types'

export const useTripStore = defineStore('trip', () => {
  const plan = ref<Plan | null>(null)
  const bootstrap = ref<Bootstrap | null>(null)

  function rememberPlan(nextPlan: Plan) {
    plan.value = nextPlan
    localStorage.setItem('last-plan-id', nextPlan.id)
  }

  function loadPlan(nextPlan: Plan) {
    plan.value = nextPlan
  }

  return { plan, bootstrap, rememberPlan, loadPlan }
})
