<!--
  模块：品牌标识组件（components/BrandMark.vue）
  职责：纯展示的「漫游 HERE」标记，支持 light（深底反白）与 compact（紧凑）两种变体。
  无状态、无请求，可被任意页面复用。
-->
<script setup lang="ts">
withDefaults(
  defineProps<{
    light?: boolean
    compact?: boolean
  }>(),
  {
    light: false,
    compact: false,
  },
)
</script>

<template>
  <RouterLink
    to="/"
    class="brand"
    :class="{ 'brand--light': light, 'brand--compact': compact }"
    aria-label="返回漫游首页"
  >
    <span class="brand__mark" aria-hidden="true">
      <svg viewBox="0 0 38 38" fill="none">
        <path d="M6 29C10 24 13 24 17 18C21 12 25 12 32 6" />
        <path d="M6 9H20C26 9 29 12 29 17" />
        <circle cx="6" cy="29" r="2.3" />
        <circle cx="32" cy="6" r="2.3" />
      </svg>
    </span>
    <span class="brand__copy">
      <strong>漫游 <em>HERE</em></strong>
      <small v-if="!compact">把城市走成故事</small>
    </span>
  </RouterLink>
</template>

<style scoped>
.brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  color: var(--ink);
  text-decoration: none;
}

.brand__mark {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 50%;
  background: var(--forest);
  box-shadow: 0 7px 18px rgba(24, 63, 50, 0.18);
}

.brand__mark svg {
  width: 27px;
  stroke: #f5e9d5;
  stroke-width: 1.8;
  stroke-linecap: round;
}

.brand__mark circle {
  fill: #efb777;
  stroke: none;
}

.brand__copy {
  display: flex;
  flex-direction: column;
  line-height: 1;
}

.brand__copy strong {
  font-family: var(--font-display);
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.brand__copy em {
  font-family: var(--font-sans);
  font-size: 10px;
  font-style: normal;
  font-weight: 800;
  letter-spacing: 0.18em;
  color: var(--clay);
}

.brand__copy small {
  margin-top: 6px;
  color: var(--muted);
  font-size: 9px;
  letter-spacing: 0.12em;
}

.brand--light {
  color: #fffaf0;
}

.brand--light .brand__mark {
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.18);
  box-shadow: none;
}

.brand--light .brand__copy small {
  color: rgba(255, 255, 255, 0.55);
}

.brand--compact .brand__copy strong {
  font-size: 18px;
}
</style>
