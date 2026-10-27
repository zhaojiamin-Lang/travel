<script setup lang="ts">
import { ArrowUpRight } from 'lucide-vue-next'
import BrandMark from './BrandMark.vue'

withDefaults(
  defineProps<{
    light?: boolean
    actionLabel?: string
    showNav?: boolean
  }>(),
  {
    light: false,
    actionLabel: '开始规划',
    showNav: true,
  },
)

defineEmits<{
  action: []
}>()
</script>

<template>
  <header class="site-header page-shell" :class="{ 'site-header--light': light }">
    <BrandMark :light="light" />

    <nav v-if="showNav" class="site-header__nav" aria-label="主要导航">
      <a href="#inspiration">发现灵感</a>
      <a href="#how-it-works">规划方式</a>
      <a href="#guide">现场导游</a>
      <RouterLink to="/account">账户与历史</RouterLink>
    </nav>

    <button class="site-header__action" type="button" @click="$emit('action')">
      {{ actionLabel }}
      <ArrowUpRight :size="16" :stroke-width="1.8" />
    </button>
  </header>
</template>

<style scoped>
.site-header {
  position: relative;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 88px;
}

.site-header__nav {
  display: flex;
  align-items: center;
  gap: 38px;
}

.site-header__nav a {
  color: var(--muted-strong);
  font-size: 13px;
  text-decoration: none;
  transition: color 180ms ease;
}

.site-header__nav a:hover {
  color: var(--forest);
}

.site-header__action {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 11px 18px;
  border: 1px solid rgba(29, 63, 50, 0.16);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.42);
  color: var(--forest);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  backdrop-filter: blur(8px);
  transition: transform 180ms ease, background 180ms ease;
}

.site-header__action:hover {
  transform: translateY(-2px);
  background: #fff;
}

.site-header--light .site-header__nav a {
  color: rgba(255, 255, 255, 0.68);
}

.site-header--light .site-header__nav a:hover {
  color: #fff;
}

.site-header--light .site-header__action {
  color: #fff;
  border-color: rgba(255, 255, 255, 0.22);
  background: rgba(255, 255, 255, 0.08);
}

.site-header--light .site-header__action:hover {
  background: rgba(255, 255, 255, 0.16);
}

@media (max-width: 760px) {
  .site-header {
    min-height: 74px;
  }

  .site-header__nav {
    display: none;
  }

  .site-header__action {
    padding: 10px 14px;
  }
}
</style>
