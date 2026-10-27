import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
    {
      path: '/plan/:planId',
      name: 'plan',
      component: () => import('./views/PlanView.vue'),
    },
    {
      path: '/guide/:planId',
      name: 'guide',
      component: () => import('./views/GuideView.vue'),
    },
    {
      path: '/account',
      name: 'account',
      component: () => import('./views/AccountView.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

export default router
