/**
 * 模块：Service Worker（public/sw.js）
 *
 * 职责：PWA 的离线层，策略是「网络优先、成功即缓存、失败回缓存」。
 * - install/activate：预缓存应用外壳，激活时清掉旧版本缓存（改 CACHE_NAME 即可全量失效）。
 * - /api/ 请求：只缓存 bootstrap 与 /api/plans/*（行程详情），断网时用旧数据顶住；
 *   其余接口（写操作、账号、问诊）不缓存，避免拿到脏数据。
 * - 静态资源：优先网络，离线时回缓存；页面导航失败回 `/`，保证刷新不白屏。
 */
const CACHE_NAME = 'manyou-here-v1'
const APP_SHELL = ['/', '/manifest.webmanifest', '/icons/app-icon.svg']

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL)))
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))),
    ),
  )
  self.clients.claim()
})

self.addEventListener('fetch', (event) => {
  const request = event.request
  if (request.method !== 'GET') return

  const url = new URL(request.url)
  if (url.origin !== self.location.origin) return

  if (url.pathname.startsWith('/api/')) {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok && (url.pathname === '/api/bootstrap' || url.pathname.startsWith('/api/plans/'))) {
            const copy = response.clone()
            caches.open(CACHE_NAME).then((cache) => cache.put(request, copy))
          }
          return response
        })
        .catch(() => caches.match(request)),
    )
    return
  }

  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response.ok) {
          const copy = response.clone()
          caches.open(CACHE_NAME).then((cache) => cache.put(request, copy))
        }
        return response
      })
      .catch(async () => {
        const cached = await caches.match(request)
        return cached || (request.mode === 'navigate' ? caches.match('/') : Response.error())
      }),
  )
})
