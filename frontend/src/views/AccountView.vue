<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  CalendarDays,
  Check,
  LogOut,
  Map,
  Sparkles,
  UserRound,
} from 'lucide-vue-next'
import SiteHeader from '../components/SiteHeader.vue'
import { api } from '../services/api'
import type { HistoryItem, User } from '../types'

const router = useRouter()
const user = ref<User | null>(null)
const history = ref<HistoryItem[]>([])
const loading = ref(true)
const submitting = ref(false)
const mode = ref<'login' | 'register'>('register')
const error = ref('')

const form = reactive({
  displayName: '',
  email: '',
  password: '',
})

const isGuest = computed(() => user.value?.guest ?? true)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    user.value = await api.currentUser()
    history.value = await api.history()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '账户信息加载失败'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (submitting.value) return
  error.value = ''
  submitting.value = true
  try {
    user.value =
      mode.value === 'register'
        ? await api.register({ ...form })
        : await api.login({ email: form.email, password: form.password })
    history.value = await api.history()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '操作失败'
  } finally {
    submitting.value = false
  }
}

async function logout() {
  await api.logout()
  await load()
}

function formatDate(value?: string) {
  if (!value) return '日期待定'
  return new Date(`${value}T12:00:00`).toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
}

function openHistory(item: HistoryItem) {
  if (!item.latestPlanId) {
    router.push('/')
    return
  }
  router.push({ name: 'plan', params: { planId: item.latestPlanId } })
}
</script>

<template>
  <div class="account-page">
    <SiteHeader :show-nav="false" action-label="开始规划" @action="router.push('/')" />

    <main class="account-shell page-shell">
      <section class="account-hero">
        <div>
          <p class="eyebrow">Your travel archive</p>
          <h1>{{ isGuest ? '把旅程留在这里。' : `你好，${user?.displayName}` }}</h1>
          <p>
            登录后可以跨设备找回规划记录。导游位置只在当前页面使用，不会作为连续轨迹保存。
          </p>
        </div>
        <div class="account-badge" :class="{ guest: isGuest }">
          <UserRound :size="22" />
          <span>{{ isGuest ? '游客身份' : '已登录' }}</span>
          <strong>{{ user?.displayName || '旅行者' }}</strong>
          <small v-if="user?.email">{{ user.email }}</small>
        </div>
      </section>

      <div class="account-grid">
        <section class="auth-card">
          <template v-if="isGuest">
            <div class="auth-tabs">
              <button :class="{ active: mode === 'register' }" @click="mode = 'register'">创建账号</button>
              <button :class="{ active: mode === 'login' }" @click="mode = 'login'">已有账号</button>
            </div>

            <h2>{{ mode === 'register' ? '保存你的旅行档案' : '继续上次的旅程' }}</h2>
            <p class="auth-note">
              当前游客行程会自动归入新账号，无需重新规划。
            </p>

            <form @submit.prevent="submit">
              <label v-if="mode === 'register'">
                <span>昵称</span>
                <input v-model.trim="form.displayName" required maxlength="30" placeholder="怎么称呼你" />
              </label>
              <label>
                <span>邮箱</span>
                <input v-model.trim="form.email" required type="email" placeholder="you@example.com" />
              </label>
              <label>
                <span>密码</span>
                <input v-model="form.password" required type="password" minlength="6" placeholder="至少 6 位" />
              </label>
              <button class="primary-button" type="submit" :disabled="submitting">
                <span v-if="submitting" class="spinner"></span>
                <span v-else>{{ mode === 'register' ? '创建并保存' : '登录' }}</span>
                <ArrowRight v-if="!submitting" :size="17" />
              </button>
            </form>
            <p v-if="error" class="account-error">{{ error }}</p>
          </template>

          <template v-else>
            <span class="signed-icon"><Check :size="22" /></span>
            <h2>{{ user?.displayName }}，旅程已同步</h2>
            <p class="auth-note">你可以从历史记录继续查看任意一次行程。</p>
            <button class="ghost-button" type="button" @click="logout">
              <LogOut :size="15" /> 退出登录
            </button>
          </template>
        </section>

        <section class="history-card">
          <div class="section-title">
            <div>
              <p class="eyebrow">Past stories</p>
              <h2>历史行程</h2>
            </div>
            <span>{{ history.length }} TRIPS</span>
          </div>

          <div v-if="loading" class="history-loading">
            <span class="spinner spinner--dark"></span>
          </div>

          <div v-else-if="history.length" class="history-list">
            <button v-for="item in history" :key="item.requestId" type="button" @click="openHistory(item)">
              <span class="history-art"><Map :size="20" /></span>
              <span class="history-copy">
                <small><CalendarDays :size="12" />{{ formatDate(item.startDate) }}</small>
                <strong>{{ item.summary }}</strong>
                <span>{{ item.planCount }} 个版本 · 可继续查看</span>
              </span>
              <ArrowRight :size="17" />
            </button>
          </div>

          <div v-else class="history-empty">
            <Sparkles :size="22" />
            <h3>还没有保存的行程</h3>
            <p>先完成一次规划，它会出现在这里。</p>
            <button class="primary-button" type="button" @click="router.push('/')">
              去规划 <ArrowRight :size="16" />
            </button>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>

<style scoped>
.account-page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 94% 8%, rgba(207, 187, 152, 0.28), transparent 25%),
    var(--cream);
}

.account-shell {
  padding: 65px 0 90px;
}

.account-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 50px;
  padding-bottom: 42px;
  border-bottom: 1px solid var(--line);
}

.account-hero h1 {
  margin: 15px 0 13px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: clamp(43px, 5vw, 64px);
  font-weight: 500;
  letter-spacing: -0.05em;
}

.account-hero > div:first-child > p:last-child {
  max-width: 560px;
  margin: 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.85;
}

.account-badge {
  display: grid;
  min-width: 190px;
  grid-template-columns: auto 1fr;
  gap: 4px 10px;
  align-items: center;
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: rgba(255, 253, 247, 0.66);
}

.account-badge svg {
  grid-row: span 3;
  color: var(--clay);
}

.account-badge span {
  color: var(--muted);
  font-size: 7px;
  letter-spacing: 0.1em;
}

.account-badge strong {
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 18px;
}

.account-badge small {
  color: var(--muted);
  font-size: 8px;
}

.account-grid {
  display: grid;
  grid-template-columns: 0.78fr 1.22fr;
  gap: 20px;
  padding-top: 38px;
}

.auth-card,
.history-card {
  padding: 28px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: rgba(255, 253, 247, 0.7);
}

.auth-card {
  align-self: start;
}

.auth-tabs {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border-radius: 10px;
  background: var(--cream-deep);
}

.auth-tabs button {
  padding: 8px 12px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--muted);
  font-size: 9px;
  cursor: pointer;
}

.auth-tabs button.active {
  background: #fff;
  color: var(--forest);
  box-shadow: 0 3px 12px rgba(39, 58, 46, 0.07);
}

.auth-card h2,
.history-card h2 {
  margin: 23px 0 8px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 28px;
  font-weight: 500;
}

.auth-note {
  margin: 0 0 22px;
  color: var(--muted);
  font-size: 9px;
  line-height: 1.65;
}

.auth-card form {
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.auth-card label {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.auth-card label span {
  color: var(--muted);
  font-size: 8px;
}

.auth-card input {
  height: 44px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  outline: none;
  background: #fffdf8;
  color: var(--ink);
  font-size: 11px;
}

.auth-card input:focus {
  border-color: rgba(38, 85, 66, 0.4);
}

.auth-card form .primary-button {
  width: 100%;
  margin-top: 5px;
}

.account-error {
  margin: 13px 0 0;
  color: #a24932;
  font-size: 9px;
}

.signed-icon {
  display: grid;
  width: 46px;
  height: 46px;
  margin-top: 22px;
  place-items: center;
  border-radius: 50%;
  background: rgba(72, 118, 88, 0.12);
  color: #4d775c;
}

.section-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.section-title h2 {
  margin-top: 12px;
}

.section-title > span {
  color: #9b9b92;
  font-size: 7px;
  letter-spacing: 0.13em;
}

.history-list {
  display: flex;
  gap: 8px;
  margin-top: 20px;
  flex-direction: column;
}

.history-list button {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr) auto;
  gap: 13px;
  align-items: center;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fffdf8;
  cursor: pointer;
  text-align: left;
  transition: transform 180ms ease, box-shadow 180ms ease;
}

.history-list button:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 28px rgba(42, 60, 48, 0.08);
}

.history-art {
  display: grid;
  width: 52px;
  height: 58px;
  place-items: center;
  border-radius: 8px;
  background: linear-gradient(145deg, #a67b5d, #6c7e70);
  color: #fff;
}

.history-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.history-copy small,
.history-copy > span {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--muted);
  font-size: 7px;
}

.history-copy strong {
  overflow: hidden;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-list > button > svg {
  color: #8e958d;
}

.history-loading,
.history-empty {
  display: flex;
  min-height: 230px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  text-align: center;
}

.history-empty svg {
  color: var(--clay);
}

.history-empty h3 {
  margin: 13px 0 6px;
  color: var(--forest);
  font-family: var(--font-display);
  font-size: 21px;
}

.history-empty p {
  margin: 0 0 18px;
  color: var(--muted);
  font-size: 9px;
}

.spinner--dark {
  border-color: rgba(33, 73, 55, 0.15);
  border-top-color: var(--forest);
}

@media (max-width: 760px) {
  .account-shell {
    padding-top: 38px;
  }

  .account-hero {
    align-items: flex-start;
    gap: 24px;
    flex-direction: column;
  }

  .account-badge {
    width: 100%;
  }

  .account-grid {
    grid-template-columns: 1fr;
  }
}
</style>
