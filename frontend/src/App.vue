<template>
  <el-config-provider :locale="zhCn">
    <el-container
      class="app-shell"
      :class="{ 'kitchen-world-chrome': route.name === 'home' }"
      :data-theme="activeTheme"
    >
    <el-header class="app-header">
      <RouterLink class="brand" to="/" aria-label="小厨灵 AI COOKING ASSISTANT 首页">
        <span class="brand-mark" aria-hidden="true">
          <img src="/images/brand-cookbook.png" alt="" />
        </span>
        <span class="brand-copy">
          <strong>小厨灵</strong>
          <small>AI COOKING ASSISTANT</small>
        </span>
      </RouterLink>

      <div class="account">
        <template v-if="auth.isLoggedIn">
          <RouterLink
            v-if="auth.isUser"
            class="notification-link"
            :to="kitchenFeatureLocation('notifications')"
            :aria-label="notificationUnreadCount > 0 ? `消息中心，有${notificationUnreadCount}条未读消息` : '消息中心'"
          >
            <Bell :size="17" aria-hidden="true" />
            <span v-if="notificationUnreadCount > 0" class="notification-count" aria-hidden="true">
              {{ notificationUnreadCount > 99 ? '99+' : notificationUnreadCount }}
            </span>
          </RouterLink>
          <RouterLink
            v-if="auth.isUser"
            custom
            :to="kitchenNavigationLocation('account')"
            v-slot="{ href }"
          >
            <a
              class="account-profile"
              :href="href"
              :aria-label="`打开${auth.displayName || roleDisplay}的账号中心`"
              @click.prevent="requestKitchenNavigation('account')"
            >
              <span class="account-name">{{ auth.displayName || roleDisplay }}</span>
              <span class="account-avatar" aria-hidden="true">
                <img v-if="headerAvatarUrl" :src="headerAvatarUrl" alt="" />
                <UserCircle v-else :size="19" stroke-width="2.2" />
              </span>
            </a>
          </RouterLink>
          <span v-else class="account-name">{{ auth.displayName || roleDisplay }}</span>
          <el-button class="header-button" type="primary" plain @click="logout">
            <LogOut :size="16" aria-hidden="true" />
            <span>退出登录</span>
          </el-button>
        </template>
        <RouterLink v-else class="login-link" to="/login">
          <LogIn :size="16" aria-hidden="true" />
          <span>登录</span>
        </RouterLink>
      </div>
    </el-header>

    <el-container class="app-body">
      <aside class="app-sidebar" aria-label="工作区导航">
        <nav class="sidebar-navigation" aria-label="厨房功能入口导航">
          <div class="sidebar-caption">工作区</div>
          <RouterLink
            class="sidebar-link"
            :class="{ 'sidebar-link-active': isKitchenWorkbenchActive }"
            :to="kitchenWorldLocation()"
            :aria-current="isKitchenWorkbenchActive ? 'page' : undefined"
          >
            <Home :size="17" aria-hidden="true" />
            <span>智能工作台</span>
          </RouterLink>

          <div class="sidebar-caption sidebar-caption-spaced">功能扩展</div>
          <template v-if="auth.isAdmin">
            <RouterLink
              v-for="item in adminPanelNavigation"
              :key="item.panel"
              custom
              :to="adminPanelRoute(item.panel)"
              v-slot="{ href, navigate }"
            >
              <a
                class="sidebar-link"
                :class="{ 'sidebar-link-admin-active': activeAdminPanel === item.panel }"
                :href="href"
                :aria-current="activeAdminPanel === item.panel ? 'page' : undefined"
                @click="navigate"
              >
                <component :is="item.icon" :size="17" aria-hidden="true" />
                <span>{{ item.label }}</span>
              </a>
            </RouterLink>
          </template>
          <template v-else>
            <RouterLink
              v-for="item in userSidebarNavigation"
              :key="item.featureId"
              custom
              :to="kitchenNavigationLocation(item.featureId)"
              v-slot="{ href }"
            >
              <a
                class="sidebar-link"
                :class="{ 'sidebar-link-active': isKitchenNavigationActive(item.featureId, route.query) }"
                :href="href"
                :aria-current="isKitchenNavigationActive(item.featureId, route.query) ? 'page' : undefined"
                @click.prevent="requestKitchenNavigation(item.featureId)"
              >
                <component :is="item.icon" :size="17" aria-hidden="true" />
                <span>{{ item.navigation.label }}</span>
              </a>
            </RouterLink>
          </template>

          <RouterLink v-if="auth.isAdmin" custom :to="adminPanelRoute('overview')" v-slot="{ href, navigate }">
            <a class="sidebar-link sidebar-admin-link" :href="href" @click="navigate">
              <ShieldCheck :size="17" aria-hidden="true" />
              <span>管理后台</span>
            </a>
          </RouterLink>
        </nav>
        <KitchenAgentWidget v-if="route.name !== 'login' && !auth.isAdmin" />
      </aside>

      <el-main class="app-main">
        <RouterView />
      </el-main>
    </el-container>

    </el-container>
  </el-config-provider>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { Activity, Bell, CircleAlert, ClipboardCheck, ClipboardList, Flame, Gauge, Home, LogIn, LogOut, MessageSquare, Settings, ShieldCheck, Users, UserCircle } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { getUnreadNotificationCount } from './api/notifications'
import { getMyAccount, loadMyAvatar } from './api/userAccount'
import KitchenAgentWidget from './components/KitchenAgentWidget.vue'
import { useAuthStore } from './stores/auth'
import { useKitchenStore } from './stores/kitchen'
import {
  ADMIN_PANEL_NAVIGATION,
  buildAdminPanelQuery,
  resolveAdminPanel
} from './utils/hotIngredientNavigation'
import {
  getKitchenNavigation,
  getKitchenNavigationTarget,
  isKitchenNavigationActive,
  kitchenFeatureLocation,
  kitchenNavigationLocation,
  kitchenWorldLocation,
  parseKitchenFeature,
  parseKitchenStation
} from './utils/kitchenFeatures'

const RECIPE_THEME_KEY = 'ai-recipe-theme'
const themeConfigs = [
  {
    key: 'business',
    variables: {
      '--app-bg': '#F5F7FA',
      '--app-surface': '#FFFFFF',
      '--app-surface-strong': '#EEF3F7',
      '--app-surface-soft': '#E3EBF2',
      '--app-field-bg': '#FFFFFF',
      '--app-line': '#D6DEE8',
      '--app-line-strong': '#9FB2C3',
      '--app-text': '#1E3A5F',
      '--app-text-soft': '#304D6D',
      '--app-text-muted': '#4A90A4',
      '--app-text-faint': '#8EA2B5',
      '--app-accent': '#E8B339',
      '--app-accent-hover': '#F2C85C',
      '--app-accent-soft': '#F8E6AF',
      '--app-accent-text': '#1E3A5F',
      '--app-header-bg': 'rgba(245, 247, 250, 0.92)',
      '--app-grid-line': 'rgba(30, 58, 95, 0.055)',
      '--app-grid-line-strong': 'rgba(30, 58, 95, 0.08)',
      '--app-grid-line-soft': 'rgba(74, 144, 164, 0.07)',
      '--app-panel-shadow': '0 18px 60px rgba(30, 58, 95, 0.12)',
      '--app-focus-shadow': '0 0 0 1px #4A90A4 inset',
      '--app-brand-shadow': '0 0 28px rgba(232, 179, 57, 0.22)'
    }
  },
  {
    key: 'minimal',
    variables: {
      '--app-bg': '#FFFFFF',
      '--app-surface': '#FFFFFF',
      '--app-surface-strong': '#F3F4F6',
      '--app-surface-soft': '#E5E7EB',
      '--app-field-bg': '#FFFFFF',
      '--app-line': '#E5E7EB',
      '--app-line-strong': '#D1D5DB',
      '--app-text': '#111827',
      '--app-text-soft': '#374151',
      '--app-text-muted': '#6B7280',
      '--app-text-faint': '#9CA3AF',
      '--app-accent': '#10B981',
      '--app-accent-hover': '#34D399',
      '--app-accent-soft': '#A7F3D0',
      '--app-accent-text': '#FFFFFF',
      '--app-header-bg': 'rgba(255, 255, 255, 0.94)',
      '--app-grid-line': 'rgba(17, 24, 39, 0.045)',
      '--app-grid-line-strong': 'rgba(17, 24, 39, 0.07)',
      '--app-grid-line-soft': 'rgba(107, 114, 128, 0.055)',
      '--app-panel-shadow': '0 18px 60px rgba(17, 24, 39, 0.09)',
      '--app-focus-shadow': '0 0 0 1px #10B981 inset',
      '--app-brand-shadow': '0 0 28px rgba(16, 185, 129, 0.18)'
    }
  },
  {
    key: 'warm',
    variables: {
      '--app-bg': '#FFFAF0',
      '--app-surface': '#FFFFFF',
      '--app-surface-strong': '#FFF3DF',
      '--app-surface-soft': '#FEE7C5',
      '--app-field-bg': '#FFFFFF',
      '--app-line': '#F1D7B8',
      '--app-line-strong': '#E9B476',
      '--app-text': '#7C2D12',
      '--app-text-soft': '#9A3412',
      '--app-text-muted': '#C05621',
      '--app-text-faint': '#B7791F',
      '--app-accent': '#38B2AC',
      '--app-accent-hover': '#4FD1C5',
      '--app-accent-soft': '#B2F5EA',
      '--app-accent-text': '#073331',
      '--app-header-bg': 'rgba(255, 250, 240, 0.93)',
      '--app-grid-line': 'rgba(192, 86, 33, 0.06)',
      '--app-grid-line-strong': 'rgba(237, 137, 54, 0.1)',
      '--app-grid-line-soft': 'rgba(56, 178, 172, 0.07)',
      '--app-panel-shadow': '0 18px 60px rgba(192, 86, 33, 0.12)',
      '--app-focus-shadow': '0 0 0 1px #38B2AC inset',
      '--app-brand-shadow': '0 0 28px rgba(237, 137, 54, 0.22)'
    }
  },
  {
    key: 'tech-dark',
    variables: {
      '--app-bg': '#0B1120',
      '--app-surface': '#0F172A',
      '--app-surface-strong': '#111C33',
      '--app-surface-soft': '#17233D',
      '--app-field-bg': '#0B1120',
      '--app-line': '#1E293B',
      '--app-line-strong': '#334155',
      '--app-text': '#E5E7EB',
      '--app-text-soft': '#CBD5E1',
      '--app-text-muted': '#94A3B8',
      '--app-text-faint': '#64748B',
      '--app-accent': '#3B82F6',
      '--app-accent-hover': '#60A5FA',
      '--app-accent-soft': '#93C5FD',
      '--app-accent-text': '#FFFFFF',
      '--app-header-bg': 'rgba(11, 17, 32, 0.91)',
      '--app-grid-line': 'rgba(59, 130, 246, 0.07)',
      '--app-grid-line-strong': 'rgba(59, 130, 246, 0.12)',
      '--app-grid-line-soft': 'rgba(148, 163, 184, 0.07)',
      '--app-panel-shadow': '0 18px 60px rgba(0, 0, 0, 0.36)',
      '--app-focus-shadow': '0 0 0 1px #3B82F6 inset',
      '--app-brand-shadow': '0 0 28px rgba(59, 130, 246, 0.28)'
    }
  },
  {
    key: 'youth',
    variables: {
      '--app-bg': '#F7F9FC',
      '--app-surface': '#FFFFFF',
      '--app-surface-strong': '#F0F5F8',
      '--app-surface-soft': '#E6F7F5',
      '--app-field-bg': '#FFFFFF',
      '--app-line': '#DCE8EF',
      '--app-line-strong': '#A8DADC',
      '--app-text': '#263238',
      '--app-text-soft': '#37474F',
      '--app-text-muted': '#607D8B',
      '--app-text-faint': '#90A4AE',
      '--app-accent': '#FF6B6B',
      '--app-accent-hover': '#FF8585',
      '--app-accent-soft': '#FFD6D6',
      '--app-accent-text': '#FFFFFF',
      '--app-header-bg': 'rgba(247, 249, 252, 0.94)',
      '--app-grid-line': 'rgba(78, 205, 196, 0.08)',
      '--app-grid-line-strong': 'rgba(255, 107, 107, 0.1)',
      '--app-grid-line-soft': 'rgba(78, 205, 196, 0.07)',
      '--app-panel-shadow': '0 18px 60px rgba(38, 50, 56, 0.1)',
      '--app-focus-shadow': '0 0 0 1px #4ECDC4 inset',
      '--app-brand-shadow': '0 0 28px rgba(255, 107, 107, 0.2)'
    }
  }
]

const auth = useAuthStore()
const kitchen = useKitchenStore()
const route = useRoute()
const router = useRouter()
const userSidebarNavigation = computed(() => getKitchenNavigation('sidebar', auth.role))
const isKitchenWorkbenchActive = computed(() => route.name === 'home'
  && !parseKitchenFeature(route.query)
  && !parseKitchenStation(route.query))
const activeAdminPanel = computed(() => resolveAdminPanel(route.query.panel))
const adminPanelIcons = {
  overview: Gauge,
  settings: Settings,
  'hot-ingredients': Flame,
  'operation-logs': ClipboardList,
  'error-logs': CircleAlert,
  'user-management': Users,
  'feature-suggestions': MessageSquare,
  'agent-observability': Activity,
  'agent-evaluation': ClipboardCheck
}
const adminPanelNavigation = ADMIN_PANEL_NAVIGATION.map((item) => ({
  ...item,
  icon: adminPanelIcons[item.panel]
}))
const activeTheme = ref(getInitialTheme())
const headerAvatarUrl = ref('')
const roleDisplay = computed(() => ({ ADMIN: '管理员', USER: '普通用户' })[auth.role] || auth.role)
const activeThemeConfig = computed(
  () => themeConfigs.find((theme) => theme.key === activeTheme.value) || themeConfigs[0]
)
const notificationUnreadCount = ref(0)
let headerAvatarRequestId = 0
let notificationCountTimer = null

watch(
  activeThemeConfig,
  (theme) => {
    applyTheme(theme)
  },
  { immediate: true }
)

watch(
  () => [auth.token, auth.avatarVersion, auth.isUser],
  () => {
    void loadHeaderAvatar()
  },
  { immediate: true }
)

watch(
  () => [auth.token, auth.isUser],
  () => {
    clearNotificationCountTimer()
    if (!auth.isUser) {
      notificationUnreadCount.value = 0
      return
    }
    void loadNotificationUnreadCount()
    notificationCountTimer = window.setInterval(loadNotificationUnreadCount, 60_000)
  },
  { immediate: true }
)

onMounted(() => {
  window.addEventListener('notifications-updated', loadNotificationUnreadCount)
})

onBeforeUnmount(() => {
  revokeHeaderAvatar()
  clearNotificationCountTimer()
  window.removeEventListener('notifications-updated', loadNotificationUnreadCount)
})

function requestKitchenNavigation(featureId) {
  const target = getKitchenNavigationTarget(featureId)
  if (!target) return

  if (target.type === 'station') {
    kitchen.requestStation(target.stationId)
    if (route.name !== 'home') router.push(kitchenWorldLocation())
    return
  }

  const currentQuery = route.name === 'home' ? route.query : {}
  router.push(kitchenNavigationLocation(target.featureId, currentQuery))
}

function logout() {
  auth.logout()
  router.push({ name: 'login' })
}

function adminPanelRoute(panel) {
  return {
    name: 'admin',
    query: buildAdminPanelQuery(route.query, panel)
  }
}

async function loadHeaderAvatar() {
  const requestId = ++headerAvatarRequestId
  if (!auth.isUser) {
    revokeHeaderAvatar()
    return
  }

  try {
    const accountResponse = await getMyAccount()
    if (requestId !== headerAvatarRequestId || !accountResponse.data.data?.avatarUrl) {
      if (requestId === headerAvatarRequestId) revokeHeaderAvatar()
      return
    }

    const avatarResponse = await loadMyAvatar()
    if (requestId !== headerAvatarRequestId) return

    revokeHeaderAvatar()
    headerAvatarUrl.value = URL.createObjectURL(avatarResponse.data)
  } catch {
    if (requestId === headerAvatarRequestId) revokeHeaderAvatar()
  }
}

function revokeHeaderAvatar() {
  if (headerAvatarUrl.value) URL.revokeObjectURL(headerAvatarUrl.value)
  headerAvatarUrl.value = ''
}

async function loadNotificationUnreadCount() {
  if (!auth.isUser) {
    notificationUnreadCount.value = 0
    return
  }
  try {
    const response = await getUnreadNotificationCount()
    notificationUnreadCount.value = Number(response.data.data || 0)
  } catch {
    notificationUnreadCount.value = 0
  }
}

function clearNotificationCountTimer() {
  if (notificationCountTimer) window.clearInterval(notificationCountTimer)
  notificationCountTimer = null
}

function getInitialTheme() {
  if (typeof window === 'undefined') {
    return themeConfigs[0].key
  }

  const savedTheme = window.localStorage.getItem(RECIPE_THEME_KEY)
  return themeConfigs.some((theme) => theme.key === savedTheme) ? savedTheme : themeConfigs[0].key
}

function applyTheme(theme) {
  if (typeof document === 'undefined') {
    return
  }

  Object.entries(theme.variables).forEach(([name, value]) => {
    document.documentElement.style.setProperty(name, value)
  })
  document.body.dataset.theme = theme.key
  window.localStorage.setItem(RECIPE_THEME_KEY, theme.key)
}
</script>

<style scoped>
:global(:root) {
  --app-bg: #F5F7FA;
  --app-surface: #FFFFFF;
  --app-surface-strong: #EEF3F7;
  --app-surface-soft: #E3EBF2;
  --app-field-bg: #FFFFFF;
  --app-line: #D6DEE8;
  --app-line-strong: #9FB2C3;
  --app-text: #1E3A5F;
  --app-text-soft: #304D6D;
  --app-text-muted: #4A90A4;
  --app-text-faint: #8EA2B5;
  --app-accent: #E8B339;
  --app-accent-hover: #F2C85C;
  --app-accent-soft: #F8E6AF;
  --app-accent-text: #1E3A5F;
  --app-header-bg: rgba(245, 247, 250, 0.92);
  --app-grid-line: rgba(30, 58, 95, 0.055);
  --app-grid-line-strong: rgba(30, 58, 95, 0.08);
  --app-grid-line-soft: rgba(74, 144, 164, 0.07);
  --app-panel-shadow: 0 18px 60px rgba(30, 58, 95, 0.12);
  --app-focus-shadow: 0 0 0 1px #4A90A4 inset;
  --app-brand-shadow: 0 0 28px rgba(232, 179, 57, 0.22);
  --el-color-primary: var(--app-accent);
  --el-color-primary-light-3: var(--app-accent-hover);
  --el-color-primary-light-5: var(--app-accent-soft);
  --el-color-primary-light-7: var(--app-line-strong);
  --el-color-primary-light-9: var(--app-surface-soft);
  --el-color-primary-dark-2: var(--app-accent-hover);
  --el-border-radius-base: 6px;
  --el-bg-color: var(--app-surface);
  --el-bg-color-overlay: var(--app-surface-strong);
  --el-border-color: var(--app-line);
  --el-border-color-light: var(--app-line-strong);
  --el-fill-color-blank: var(--app-surface);
  --el-text-color-primary: var(--app-text);
  --el-text-color-regular: var(--app-text-soft);
  --el-text-color-secondary: var(--app-text-muted);
  color: var(--app-text);
  background: var(--app-bg);
  font-family:
    "Inter", "Microsoft YaHei", "PingFang SC", ui-sans-serif, system-ui, -apple-system,
    BlinkMacSystemFont, "Segoe UI", sans-serif;
}

:global(*) {
  box-sizing: border-box;
}

:global(html),
:global(body),
:global(#app) {
  width: 100%;
  height: 100%;
}

:global(body) {
  margin: 0;
  min-width: 320px;
  overflow: hidden;
  background: var(--app-bg);
}

:global(a) {
  color: inherit;
  text-decoration: none;
}

:global(.el-form-item__label) {
  color: var(--app-text-soft);
  font-weight: 700;
}

:global(.el-input__wrapper),
:global(.el-select__wrapper),
:global(.el-textarea__inner) {
  background: var(--app-field-bg) !important;
  box-shadow: 0 0 0 1px var(--app-line) inset !important;
}

:global(.el-input__wrapper:hover),
:global(.el-select__wrapper:hover),
:global(.el-textarea__inner:hover),
:global(.el-input__wrapper.is-focus),
:global(.el-select__wrapper.is-focused),
:global(.el-textarea__inner:focus) {
  box-shadow: var(--app-focus-shadow) !important;
}

:global(.el-input__inner),
:global(.el-textarea__inner),
:global(.el-select__placeholder),
:global(.el-select__selected-item) {
  color: var(--app-text) !important;
}

:global(.el-input__inner::placeholder),
:global(.el-textarea__inner::placeholder) {
  color: var(--app-text-faint) !important;
}

:global(.el-button) {
  border-radius: 6px;
  font-weight: 800;
}

:global(.el-button--primary) {
  --el-button-bg-color: var(--app-accent);
  --el-button-border-color: var(--app-accent);
  --el-button-hover-bg-color: var(--app-accent-hover);
  --el-button-hover-border-color: var(--app-accent-hover);
  --el-button-active-bg-color: var(--app-accent-soft);
  --el-button-active-border-color: var(--app-accent-soft);
  color: var(--app-accent-text);
}

:global(.el-button.is-plain) {
  --el-button-bg-color: transparent;
  --el-button-border-color: var(--app-line-strong);
  --el-button-text-color: var(--app-text);
  --el-button-hover-bg-color: var(--app-accent);
  --el-button-hover-border-color: var(--app-accent);
  --el-button-hover-text-color: var(--app-accent-text);
}

:global(.el-radio-button__inner) {
  border-color: var(--app-line) !important;
  color: var(--app-text-soft);
  background: var(--app-surface);
}

:global(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  border-color: var(--app-accent) !important;
  color: var(--app-accent-text);
  background: var(--app-accent);
  box-shadow: none;
}

:global(.el-tag) {
  border-color: var(--app-line-strong);
  color: var(--app-text);
  background: var(--app-surface-strong);
}

:global(.el-table) {
  --el-table-bg-color: var(--app-surface);
  --el-table-tr-bg-color: var(--app-surface);
  --el-table-header-bg-color: var(--app-surface-strong);
  --el-table-header-text-color: var(--app-text);
  --el-table-text-color: var(--app-text-soft);
  --el-table-border-color: var(--app-line);
  --el-table-row-hover-bg-color: var(--app-surface-soft);
  color: var(--app-text-soft);
}

:global(.el-empty__description p) {
  color: var(--app-text-muted);
}

.app-shell {
  width: 100%;
  height: 100vh;
  height: 100dvh;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background:
    linear-gradient(var(--app-grid-line) 1px, transparent 1px),
    linear-gradient(90deg, var(--app-grid-line) 1px, transparent 1px),
    var(--app-bg);
  background-size: 28px 28px;
}

.app-header {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  height: 58px;
  flex: 0 0 58px;
  padding: 0 clamp(12px, 3vw, 36px);
  border-bottom: 1px solid var(--app-line);
  background: var(--app-header-bg);
  backdrop-filter: blur(14px);
}

.app-shell.kitchen-world-chrome .app-header {
  background: rgba(248, 239, 220, 0.92);
}

.brand,
.nav-link,
.login-link,
.header-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.brand {
  min-width: max-content;
  color: var(--app-text);
  letter-spacing: 0;
  text-decoration: none;
}

.brand-mark {
  display: inline-grid;
  place-items: center;
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  border: 1px solid color-mix(in srgb, var(--app-accent) 58%, var(--app-line));
  border-radius: 7px;
  background: color-mix(in srgb, var(--app-accent-soft) 42%, transparent);
  box-shadow: var(--app-brand-shadow);
}

.brand-mark img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  image-rendering: pixelated;
}

.brand-copy {
  display: grid;
  align-content: center;
  gap: 3px;
}

.brand-copy strong {
  color: var(--app-text);
  font-size: 22px;
  font-weight: 900;
  line-height: 1;
  letter-spacing: 0.08em;
}

.brand-copy small {
  color: var(--app-text-muted);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 10px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: 0.08em;
  white-space: nowrap;
}

.nav-links {
  display: flex;
  align-items: center;
  gap: 8px;
}

.nav-link,
.login-link {
  min-height: 38px;
  padding: 0 11px;
  border: 1px solid transparent;
  border-radius: 6px;
  color: var(--app-text-muted);
  font-weight: 700;
  transition:
    border-color 180ms ease,
    background-color 180ms ease,
    color 180ms ease;
}

.nav-link:hover,
.login-link:hover,
.nav-link.router-link-active {
  border-color: var(--app-line-strong);
  color: var(--app-text);
  background: var(--app-surface-strong);
}

.account {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  min-width: 150px;
}

.notification-link {
  position: relative;
  display: inline-grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border: 1px solid transparent;
  border-radius: 50%;
  color: var(--app-text-soft);
  transition:
    border-color 180ms ease,
    background-color 180ms ease,
    color 180ms ease;
}

.notification-link:hover,
.notification-link:focus-visible,
.notification-link.router-link-active {
  border-color: var(--app-line-strong);
  color: var(--app-text);
  background: var(--app-surface-strong);
  outline: 2px solid var(--app-text-muted);
  outline-offset: 2px;
}

.notification-count {
  position: absolute;
  top: -2px;
  right: -4px;
  min-width: 17px;
  padding: 1px 4px;
  border: 2px solid var(--app-header-bg);
  border-radius: 999px;
  color: #ffffff;
  background: #d3544b;
  font-size: 10px;
  font-weight: 900;
  line-height: 14px;
  text-align: center;
}

.account-profile {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  min-height: 38px;
  padding: 3px 0 3px 8px;
  border: 1px solid transparent;
  border-radius: 999px;
  color: inherit;
  transition:
    border-color 180ms ease,
    background-color 180ms ease;
}

.account-profile:hover,
.account-profile:focus-visible {
  border-color: var(--app-line-strong);
  background: var(--app-surface-strong);
  outline: 2px solid var(--app-text-muted);
  outline-offset: 2px;
}

.account-name {
  max-width: 180px;
  overflow: hidden;
  color: var(--app-text-soft);
  font-size: 14px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-avatar {
  display: inline-grid;
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  place-items: center;
  overflow: hidden;
  border: 1px solid var(--app-line-strong);
  border-radius: 50%;
  color: #ffffff;
  background: #a8b0ba;
  box-shadow: 0 3px 10px rgba(38, 50, 56, 0.12);
}

.account-avatar img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.header-button {
  --el-button-text-color: #000000;
  --el-button-hover-text-color: #000000;
  --el-button-active-text-color: #000000;
  color: #000000 !important;
  font-weight: 700;
}

.app-main {
  flex: 1 1 auto;
  padding: 0;
  min-width: 0;
  min-height: 0;
  overflow: auto;
}

.app-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
}

.app-shell.kitchen-world-chrome .app-main {
  overflow: hidden;
}

.app-sidebar {
  position: relative;
  display: flex;
  width: 190px;
  flex: 0 0 190px;
  min-height: 0;
  flex-direction: column;
  padding: 18px 14px;
  border-right: 1px solid var(--app-line);
  background: var(--app-surface);
  overflow: hidden;
}

.app-shell.kitchen-world-chrome .app-sidebar {
  background: #f8efdc;
}

.sidebar-navigation {
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
  padding-bottom: 4px;
  scrollbar-width: thin;
}

.sidebar-caption {
  flex: 0 0 auto;
  padding: 0 10px 4px;
  color: var(--app-text-faint);
  font-size: 11px;
  font-weight: 900;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.sidebar-caption-spaced {
  margin-top: 12px;
}

.sidebar-link,
.sidebar-placeholder {
  flex: 0 0 38px;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-height: 38px;
  padding: 0 11px;
  border: 1px solid transparent;
  border-radius: 6px;
  color: var(--app-text-muted);
  background: transparent;
  font: inherit;
  font-size: 14px;
  font-weight: 800;
  text-align: left;
  transition:
    border-color 180ms ease,
    background-color 180ms ease,
    color 180ms ease;
}

.sidebar-button {
  width: 100%;
  cursor: pointer;
}

.sidebar-button:focus-visible {
  border-color: var(--app-line-strong);
  color: var(--app-text);
  background: var(--app-surface-soft);
  outline: 2px solid var(--app-text-muted);
  outline-offset: 2px;
}

.sidebar-link:hover,
.sidebar-link-active,
.sidebar-link-admin-active {
  border-color: var(--app-line);
  color: var(--app-text);
  background: var(--app-surface-soft);
}

.sidebar-link:focus-visible {
  outline: 2px solid var(--app-text-muted);
  outline-offset: 2px;
}

.sidebar-placeholder {
  justify-content: flex-start;
  border-style: dashed;
  color: var(--app-line-strong);
  cursor: not-allowed;
}

.sidebar-admin-link {
  margin-top: auto;
}

.app-sidebar :deep(.agent-widget) {
  position: absolute;
  right: 14px;
  bottom: 18px;
  left: 14px;
}

.app-sidebar :deep(.agent-launcher) {
  width: 100%;
  min-width: 0;
}

@media (max-width: 720px) {
  .app-body {
    flex-direction: column;
    min-height: 0;
  }

  .app-sidebar {
    width: 100%;
    flex: 0 0 auto;
    flex-direction: row;
    align-items: center;
    overflow-x: auto;
    overflow-y: hidden;
    padding: 8px 12px;
    border-right: 0;
    border-bottom: 1px solid var(--app-line);
  }

  .sidebar-navigation {
    flex: 0 0 auto;
    flex-direction: row;
    gap: 4px;
    overflow-x: auto;
    overflow-y: hidden;
    padding: 0;
  }

  .sidebar-caption,
  .sidebar-caption-spaced {
    display: none;
  }

  .sidebar-link,
  .sidebar-placeholder {
    flex: 0 0 auto;
    min-width: max-content;
    min-height: 44px;
  }

  .sidebar-admin-link {
    margin-top: 0;
  }

  .app-sidebar :deep(.agent-widget) {
    position: fixed;
    right: 16px;
    bottom: max(16px, env(safe-area-inset-bottom));
    left: 16px;
  }

  .app-header {
    flex-wrap: wrap;
    height: auto;
    flex-basis: auto;
    padding: 12px 16px;
  }

  .brand {
    width: 100%;
  }

  .brand-mark {
    width: 40px;
    height: 40px;
    flex-basis: 40px;
  }

  .brand-copy strong {
    font-size: 20px;
  }

  .brand-copy small {
    font-size: 9px;
  }

  .account {
    min-width: auto;
  }

  .account-name {
    display: none;
  }
}
</style>
