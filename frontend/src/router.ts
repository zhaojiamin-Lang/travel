/**
 * 模块：路由表（router.ts）
 *
 * 职责：四条页面的路径映射与滚动复位——首页 `/`、行程 `/plan/:planId`、
 * 现场导游 `/guide/:planId`、账号与历史 `/account`。
 * 只有首页是静态引入，其余路由懒加载，首屏更轻。
 */
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
