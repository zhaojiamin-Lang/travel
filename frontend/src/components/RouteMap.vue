<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { MapPin, Navigation } from 'lucide-vue-next'
import type { MapPoint } from '../types'

const props = withDefaults(
  defineProps<{
    points: MapPoint[]
    activePoiId?: string
    mapKey?: string
    mode?: 'plan' | 'guide'
  }>(),
  {
    activePoiId: '',
    mapKey: '',
    mode: 'plan',
  },
)

const mapEl = ref<HTMLDivElement | null>(null)
const amapReady = ref(false)
let map: any = null

const normalizedPoints = computed(() => {
  if (!props.points.length) return []
  const lats = props.points.map((point) => point.latitude)
  const lngs = props.points.map((point) => point.longitude)
  const minLat = Math.min(...lats)
  const maxLat = Math.max(...lats)
  const minLng = Math.min(...lngs)
  const maxLng = Math.max(...lngs)
  const latRange = maxLat - minLat || 0.01
  const lngRange = maxLng - minLng || 0.01

  return props.points.map((point, index) => ({
    ...point,
    left: 16 + ((point.longitude - minLng) / lngRange) * 68,
    top: 82 - ((point.latitude - minLat) / latRange) * 61,
    current: point.poiId === props.activePoiId,
    index,
  }))
})

const routeLine = computed(() => {
  return normalizedPoints.value
    .map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.left} ${point.top}`)
    .join(' ')
})

onMounted(() => {
  if (props.mapKey) {
    loadAmap()
  }
})

watch(
  () => [props.points, props.activePoiId],
  () => {
    if (amapReady.value) renderAmap()
  },
  { deep: true },
)

watch(
  () => props.mapKey,
  (key) => {
    if (key && !amapReady.value) {
      loadAmap()
    }
  },
)

async function loadAmap() {
  try {
    if (!(window as any).AMap) {
      const script = document.createElement('script')
      script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(props.mapKey)}`
      script.async = true
      await new Promise<void>((resolve, reject) => {
        script.onload = () => resolve()
        script.onerror = () => reject(new Error('AMap failed to load'))
        document.head.appendChild(script)
      })
    }
    await nextTick()
    if (!mapEl.value || !(window as any).AMap) return
    map = new (window as any).AMap.Map(mapEl.value, {
      zoom: 12,
      center: props.points[0]
        ? [props.points[0].longitude, props.points[0].latitude]
        : [121.4737, 31.2304],
      mapStyle: 'amap://styles/whitesmoke',
      showLabel: false,
    })
    amapReady.value = true
    renderAmap()
  } catch {
    amapReady.value = false
  }
}

function renderAmap() {
  if (!map || !(window as any).AMap) return
  map.clearMap()
  const AMap = (window as any).AMap
  const path = props.points.map((point) => [point.longitude, point.latitude])
  if (path.length > 1) {
    const polyline = new AMap.Polyline({
      path,
      strokeColor: '#315c49',
      strokeOpacity: 0.7,
      strokeWeight: 3,
      strokeStyle: 'dashed',
      lineJoin: 'round',
      lineCap: 'round',
    })
    map.add(polyline)
  }
  props.points.forEach((point) => {
    const active = point.poiId === props.activePoiId
    const marker = new AMap.Marker({
      position: [point.longitude, point.latitude],
      content: `<div class="amap-dot ${active ? 'amap-dot--active' : ''}">${point.order}</div>`,
      anchor: 'center',
    })
    map.add(marker)
  })
  map.setFitView()
}
</script>

<template>
  <div class="route-map" :class="[`route-map--${mode}`, { 'route-map--amap': amapReady }]">
    <div ref="mapEl" class="route-map__amap"></div>

    <div v-if="!amapReady" class="route-map__paper" aria-label="行程路线示意图">
      <svg class="route-map__grid" viewBox="0 0 600 500" preserveAspectRatio="none" aria-hidden="true">
        <g>
          <path d="M-20 360C90 300 139 345 224 290S386 231 620 278" />
          <path d="M-30 230C84 192 178 223 255 185S435 137 650 180" />
          <path d="M93 -20C132 91 115 163 174 222S247 328 218 520" />
          <path d="M388 -20C371 98 409 177 456 220S530 294 521 520" />
          <path d="M-20 92C108 129 195 94 303 124S505 178 630 133" />
        </g>
      </svg>
      <div class="route-map__river"></div>
      <span class="route-map__city-name">SHANGHAI</span>
      <span class="route-map__coords">31.2304° N / 121.4737° E</span>
      <svg class="route-map__route" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
        <path v-if="routeLine" :d="routeLine" />
      </svg>
      <button
        v-for="point in normalizedPoints"
        :key="point.poiId"
        type="button"
        class="route-map__point"
        :class="{ 'route-map__point--active': point.current }"
        :style="{ left: `${point.left}%`, top: `${point.top}%` }"
        :title="point.name"
      >
        <span v-if="point.current" class="route-map__pulse"></span>
        <span class="route-map__number">{{ point.order }}</span>
        <span v-if="point.current" class="route-map__label">{{ point.name }}</span>
      </button>
      <div class="route-map__compass" aria-hidden="true">
        <Navigation :size="15" />
        <span>N</span>
      </div>
      <div class="route-map__legend">
        <MapPin :size="14" />
        <span>{{ points.length }} 个行程地点</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.route-map {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 285px;
  overflow: hidden;
  border-radius: inherit;
  background: #e8e3d5;
}

.route-map__amap {
  position: absolute;
  inset: 0;
}

.route-map__paper {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background:
    radial-gradient(circle at 72% 26%, rgba(255, 251, 231, 0.94), transparent 18%),
    linear-gradient(145deg, #eeeadf, #ddd8c7);
}

.route-map__paper::after {
  position: absolute;
  inset: 0;
  opacity: 0.25;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 160 160' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.72' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='.1'/%3E%3C/svg%3E");
  pointer-events: none;
  content: "";
}

.route-map__grid {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0.85;
}

.route-map__grid g {
  fill: none;
  stroke: rgba(53, 78, 64, 0.16);
  stroke-width: 1.5;
}

.route-map__river {
  position: absolute;
  top: 36%;
  right: -20%;
  width: 78%;
  height: 18%;
  border-radius: 50%;
  background: rgba(151, 180, 171, 0.22);
  transform: rotate(-14deg);
}

.route-map__city-name {
  position: absolute;
  top: 11%;
  left: 7%;
  color: rgba(39, 76, 59, 0.13);
  font-family: var(--font-display);
  font-size: clamp(46px, 7vw, 88px);
  font-weight: 600;
  letter-spacing: 0.04em;
}

.route-map__coords {
  position: absolute;
  top: 28%;
  left: 7%;
  color: rgba(62, 82, 70, 0.48);
  font-size: 7px;
  letter-spacing: 0.14em;
}

.route-map__route {
  position: absolute;
  inset: 0;
  z-index: 1;
  width: 100%;
  height: 100%;
}

.route-map__route path {
  fill: none;
  stroke: rgba(37, 79, 61, 0.54);
  stroke-width: 0.42;
  stroke-dasharray: 1.35 1.2;
  stroke-linecap: round;
}

.route-map__point {
  position: absolute;
  z-index: 3;
  display: grid;
  width: 29px;
  height: 29px;
  padding: 0;
  place-items: center;
  border: 3px solid rgba(255, 255, 255, 0.88);
  border-radius: 50%;
  background: var(--forest);
  color: #fff;
  box-shadow: 0 7px 15px rgba(35, 61, 48, 0.2);
  cursor: pointer;
  transform: translate(-50%, -50%);
}

.route-map__point--active {
  width: 40px;
  height: 40px;
  background: var(--clay);
  box-shadow: 0 0 0 9px rgba(201, 103, 67, 0.12), 0 12px 28px rgba(80, 54, 40, 0.24);
}

.route-map__number {
  font-family: var(--font-display);
  font-size: 11px;
}

.route-map__label {
  position: absolute;
  top: 48px;
  left: 50%;
  width: max-content;
  max-width: 130px;
  padding: 7px 10px;
  border: 1px solid rgba(255, 255, 255, 0.75);
  border-radius: 8px;
  background: rgba(255, 253, 247, 0.88);
  color: var(--forest);
  font-family: var(--font-sans);
  font-size: 9px;
  box-shadow: 0 8px 22px rgba(41, 56, 46, 0.12);
  transform: translateX(-50%);
  backdrop-filter: blur(8px);
}

.route-map__pulse {
  position: absolute;
  inset: -8px;
  border: 1px solid rgba(201, 103, 67, 0.45);
  border-radius: 50%;
  animation: map-pulse 2.2s ease-out infinite;
}

.route-map__compass {
  position: absolute;
  right: 16px;
  bottom: 16px;
  display: flex;
  align-items: center;
  gap: 5px;
  color: rgba(29, 67, 50, 0.64);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.route-map__legend {
  position: absolute;
  top: 17px;
  right: 17px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  border: 1px solid rgba(255, 255, 255, 0.66);
  border-radius: 999px;
  background: rgba(255, 253, 247, 0.7);
  color: var(--muted-strong);
  font-size: 8px;
  backdrop-filter: blur(8px);
}

.route-map--guide {
  min-height: 100%;
  border-radius: 0;
}

.route-map--guide .route-map__paper {
  background:
    radial-gradient(circle at 68% 28%, rgba(231, 208, 164, 0.27), transparent 24%),
    linear-gradient(145deg, #1c4234, #315c49 54%, #6c8273);
}

.route-map--guide .route-map__paper::before {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(90deg, rgba(255, 255, 255, 0.045) 1px, transparent 1px),
    linear-gradient(rgba(255, 255, 255, 0.045) 1px, transparent 1px);
  background-size: 46px 46px;
  content: "";
}

.route-map--guide .route-map__grid g {
  stroke: rgba(255, 255, 255, 0.13);
}

.route-map--guide .route-map__city-name {
  top: 16%;
  color: rgba(255, 250, 236, 0.075);
}

.route-map--guide .route-map__coords {
  color: rgba(255, 255, 255, 0.34);
}

.route-map--guide .route-map__river {
  background: rgba(148, 187, 175, 0.09);
}

.route-map--guide .route-map__route path {
  stroke: rgba(255, 242, 212, 0.41);
}

.route-map--guide .route-map__point {
  border-color: rgba(255, 255, 255, 0.34);
  background: #ede0c8;
  color: var(--forest);
}

.route-map--guide .route-map__point--active {
  background: #efb36d;
  color: #51382b;
}

.route-map--guide .route-map__label {
  color: #f8f2e5;
  border-color: rgba(255, 255, 255, 0.2);
  background: rgba(17, 49, 38, 0.82);
}

.route-map--guide .route-map__legend,
.route-map--guide .route-map__compass {
  color: rgba(255, 255, 255, 0.67);
}

@keyframes map-pulse {
  0% {
    opacity: 0.8;
    transform: scale(0.7);
  }
  100% {
    opacity: 0;
    transform: scale(1.45);
  }
}

:global(.amap-dot) {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 3px solid rgba(255, 255, 255, 0.9);
  border-radius: 50%;
  background: var(--forest);
  color: #fff;
  font-size: 10px;
  box-shadow: 0 6px 16px rgba(31, 54, 42, 0.24);
}

:global(.amap-dot--active) {
  width: 38px;
  height: 38px;
  background: var(--clay);
}
</style>
