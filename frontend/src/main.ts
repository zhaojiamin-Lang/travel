/**
 * 模块：应用入口（main.ts）
 *
 * 职责：创建 Vue 应用，挂上 Pinia 与路由，引入全局样式；只在构建产物（PROD）
 * 中注册 Service Worker，避免 PWA 离线缓存干扰本地开发调试。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/global.css'

createApp(App).use(createPinia()).use(router).mount('#app')

if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      // The app remains usable when service workers are unavailable.
    })
  })
}
