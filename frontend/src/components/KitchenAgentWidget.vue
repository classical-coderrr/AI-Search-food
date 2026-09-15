<template>
  <div class="agent-widget">
    <div v-if="isOpen" class="agent-backdrop" aria-hidden="true" @click="closePanel" />

    <section
      v-if="isOpen"
      ref="panel"
      class="agent-panel"
      :class="{
        'is-expanded': isExpanded,
        'is-positioned': Boolean(panelPosition),
        'is-dragging': isDragging,
        'is-resizing': isResizing
      }"
      :style="panelStyle"
      role="dialog"
      aria-modal="false"
      aria-labelledby="kitchen-agent-title"
    >
      <header class="agent-panel__header" @pointerdown="startDrag">
        <div class="agent-panel__identity">
          <span class="agent-mini-sprite" aria-hidden="true">
            <img src="/sprites/1-D-1.png" alt="" />
          </span>
          <div>
            <p class="agent-panel__eyebrow">厨房总管 · 小厨灵</p>
            <h2 id="kitchen-agent-title">今天的厨房，我来盯</h2>
          </div>
        </div>
        <div class="agent-panel__actions">
          <button type="button" class="agent-icon-button" aria-label="最小化小厨灵" title="最小化小厨灵" @click="minimizePanel" @pointerdown.stop>
            <span class="agent-pixel-minus" aria-hidden="true" />
          </button>
          <button
            v-if="desktopViewport"
            type="button"
            class="agent-icon-button"
            :aria-label="isExpanded ? '还原小厨灵窗口' : '放大小厨灵窗口'"
            :title="isExpanded ? '还原小厨灵窗口' : '放大小厨灵窗口'"
            :aria-pressed="isExpanded"
            @pointerdown.stop
            @click="togglePanelExpanded"
          >
            <Minimize2 v-if="isExpanded" :size="17" aria-hidden="true" />
            <Maximize2 v-else :size="17" aria-hidden="true" />
          </button>
          <button type="button" class="agent-icon-button" aria-label="关闭小厨灵" title="关闭" @click="closePanel" @pointerdown.stop>
            <X :size="17" aria-hidden="true" />
          </button>
        </div>
      </header>

      <div
        class="agent-resize-handle"
        role="separator"
        aria-orientation="horizontal"
        aria-label="调整小厨灵窗口高度"
        title="上下拖动调整窗口高度"
        tabindex="0"
        @pointerdown.stop.prevent="startResize"
        @keydown="handleResizeKeydown"
      >
        <span aria-hidden="true" />
      </div>

      <div v-if="recoveryStatus" class="agent-recovery-banner" role="status" aria-live="polite">
        <span class="agent-recovery-banner__dot" aria-hidden="true" />
        <span>{{ recoveryStatus }}</span>
      </div>

      <div ref="messageList" class="agent-messages" aria-live="polite" aria-relevant="additions text">
        <div v-if="!messages.length" class="agent-empty-state">
          <span class="agent-empty-state__stamp">待命</span>
          <strong>先从一件小事开始</strong>
          <p>我可以读取你的真实库存、菜单、提醒和营养设置，也能让阿灶根据库存现做菜谱。</p>
        </div>

        <article
          v-for="message in messages"
          :key="message.id"
          class="agent-message"
          :class="`agent-message--${message.role}`"
        >
          <div v-if="message.role === 'assistant' && message.statusText" class="agent-status-line">
            <span class="agent-status-dot" :class="{ spinning: loading }" aria-hidden="true" />
            <span>{{ message.statusText }}</span>
          </div>
          <div v-if="message.content" class="agent-message__bubble" :class="{ 'is-error': message.error }">
            {{ message.content }}
          </div>
          <img v-if="message.imagePreview" class="agent-message__image" :src="message.imagePreview" alt="本轮上传的厨房图片" />

          <div v-if="message.card" class="agent-card" :class="`agent-card--${message.card.cardType}`">
            <template v-if="message.card.cardType === 'inventory-card'">
              <div class="agent-card__head">
                <div><Database :size="15" aria-hidden="true" /><strong>我的食材库存</strong></div>
                <span>{{ message.card.payload.count || 0 }} 种</span>
              </div>
              <div class="agent-card__metrics">
                <span><i class="metric-dot metric-dot--warn" />临期 {{ message.card.payload.expiringSoonCount || 0 }}</span>
                <span><i class="metric-dot metric-dot--danger" />过期 {{ message.card.payload.expiredCount || 0 }}</span>
              </div>
              <div class="agent-card__list">
                <div v-for="item in visibleItems(message.card.payload.items, message)" :key="`${item.name}-${item.expireDate}`" class="agent-card__row">
                  <strong>{{ item.name }}</strong>
                  <span>{{ formatQuantity(item.quantity, item.unit) }}</span>
                  <em :class="statusClass(item.status)">{{ item.status }}</em>
                </div>
              </div>
              <button v-if="hasMore(message.card.payload.items, message)" type="button" class="agent-card__expand" @click="toggleExpanded(message)">
                {{ message.expanded ? '收起列表' : `展开全部（${message.card.payload.items.length} 项）` }}
              </button>
            </template>

            <template v-else-if="message.card.cardType === 'reminder-card'">
              <div class="agent-card__head">
                <div><Bell :size="15" aria-hidden="true" /><strong>我的提醒</strong></div>
                <span>{{ message.card.payload.unreadCount || 0 }} 条未读</span>
              </div>
              <div v-if="message.card.payload.items?.length" class="agent-card__list">
                <div v-for="item in message.card.payload.items" :key="`${item.title}-${item.createdAt}`" class="agent-reminder-row">
                  <span class="agent-reminder-row__badge">{{ reminderLabel(item.type) }}</span>
                  <div><strong>{{ item.title }}</strong><small>{{ item.summary }}</small></div>
                </div>
              </div>
              <p v-else class="agent-card__empty">目前没有未读提醒。</p>
            </template>

            <template v-else-if="message.card.cardType === 'menu-card'">
              <div class="agent-card__head">
                <div><CalendarDays :size="15" aria-hidden="true" /><strong>本周菜单</strong></div>
                <span>{{ formatDate(message.card.payload.weekStart) }}—{{ formatDate(message.card.payload.weekEnd) }}</span>
              </div>
              <div v-if="message.card.payload.items?.length" class="agent-card__list">
                <div v-for="item in visibleItems(message.card.payload.items, message)" :key="`${item.date}-${item.mealType}`" class="agent-card__row agent-card__row--menu">
                  <span>{{ formatDate(item.date) }}</span><strong>{{ mealLabel(item.mealType) }}</strong><span>{{ item.recipe || '未安排' }}</span>
                </div>
              </div>
              <p v-else class="agent-card__empty">本周还没有保存菜单。</p>
              <button v-if="hasMore(message.card.payload.items, message)" type="button" class="agent-card__expand" @click="toggleExpanded(message)">
                {{ message.expanded ? '收起菜单' : '展开全部菜单' }}
              </button>
            </template>

            <template v-else-if="message.card.cardType === 'nutrition-card'">
              <div class="agent-card__head"><div><HeartPulse :size="15" aria-hidden="true" /><strong>健康与营养设置</strong></div></div>
              <div class="agent-nutrition-grid">
                <div><small>热量</small><strong>{{ nutritionValue(message.card.payload.nutritionTarget?.caloriesKcal, '千卡') }}</strong></div>
                <div><small>蛋白质</small><strong>{{ nutritionValue(message.card.payload.nutritionTarget?.proteinG, '克') }}</strong></div>
                <div><small>脂肪</small><strong>{{ nutritionValue(message.card.payload.nutritionTarget?.fatG, '克') }}</strong></div>
                <div><small>碳水</small><strong>{{ nutritionValue(message.card.payload.nutritionTarget?.carbohydrateG, '克') }}</strong></div>
              </div>
              <p class="agent-card__note">口味：{{ message.card.payload.dietPreference?.taste || '未设置' }} · 目标：{{ goalLabel(message.card.payload.dietPreference?.defaultGoal) }}</p>
            </template>

            <template v-else-if="message.card.cardType === 'recipe-list-card'">
              <div class="agent-card__head"><div><BookOpen :size="15" aria-hidden="true" /><strong>最近保存的菜谱</strong></div><span>{{ message.card.payload.count || 0 }} 道</span></div>
              <div v-if="message.card.payload.items?.length" class="agent-card__list">
                <div v-for="item in message.card.payload.items" :key="item.id" class="agent-card__row agent-card__row--recipe-list"><strong>{{ item.title }}</strong><span>{{ formatDateTime(item.savedAt) }}</span></div>
              </div>
              <p v-else class="agent-card__empty">还没有保存菜谱。</p>
            </template>

            <template v-else-if="message.card.cardType === 'recipe-card'">
              <div class="agent-recipe-card__topline"><span class="agent-card__stamp">阿灶推荐</span><strong>库存匹配 {{ message.card.payload.inventoryMatch || 0 }}%</strong></div>
              <h3>{{ message.card.payload.recipe?.title || '一份新菜谱' }}</h3>
              <p class="agent-recipe-card__summary">{{ message.card.payload.recipe?.summary }}</p>
              <div class="agent-recipe-card__section"><strong>需要的食材</strong><div class="agent-recipe-card__chips"><span v-for="item in message.card.payload.recipe?.ingredients || []" :key="item.name">{{ item.name }} {{ item.amount }}</span></div></div>
              <div v-if="message.card.payload.recipe?.missingIngredients?.length" class="agent-recipe-card__section agent-recipe-card__section--missing"><strong>还缺这些</strong><p>{{ message.card.payload.recipe.missingIngredients.map((item) => `${item.name} ${item.amount}`).join('、') }}</p></div>
              <div class="agent-recipe-card__section"><strong>做法</strong><ol><li v-for="step in message.card.payload.recipe?.steps || []" :key="step.order"><span>{{ step.title || `第${step.order}步` }}</span>{{ step.description }}</li></ol></div>
              <div v-if="message.card.payload.recipe?.nutritionEstimate" class="agent-recipe-card__nutrition">每份约 {{ message.card.payload.recipe.nutritionEstimate.caloriesKcal }} 千卡 · 蛋白质 {{ message.card.payload.recipe.nutritionEstimate.proteinG }} 克</div>
              <div class="agent-card__source"><Clock3 :size="13" aria-hidden="true" />{{ message.card.source }}</div>
              <div class="agent-card__actions"><button type="button" class="agent-button agent-button--primary" @click="saveRecipe(message)"><Save :size="14" aria-hidden="true" />保存菜谱</button><button type="button" class="agent-button" @click="sendPrompt('再来一道不同的')">再来一道</button></div>
            </template>

            <template v-else-if="message.card.cardType === 'confirmation-card'">
              <div class="agent-confirmation__title"><ShieldCheck :size="16" aria-hidden="true" /><strong>{{ message.card.title || '请确认这次操作' }}</strong></div>
              <p>{{ message.card.impact }}</p>
              <small>只有点击确认后才会修改当前账号的数据；同一操作不会重复执行。</small>
              <div class="agent-card__actions"><button type="button" class="agent-button agent-button--primary" :disabled="loading || message.card.confirmed" @click="confirmAction(message)"><Check :size="14" aria-hidden="true" />{{ message.card.confirmed ? '已确认' : (message.card.actionLabel || '确认执行') }}</button><button type="button" class="agent-button" :disabled="loading || message.card.confirmed" @click="cancelConfirmation(message)">{{ message.card.cancelLabel || '暂不执行' }}</button></div>
            </template>

            <template v-else-if="['operation-result-card', 'image-recognition-card', 'attachment-card', 'finished-dish-card'].includes(message.card.cardType)">
              <div class="agent-card__head"><div><ClipboardCheck :size="15" aria-hidden="true" /><strong>{{ message.card.payload?.title || '厨房操作结果' }}</strong></div></div>
              <p class="agent-card__note">{{ message.card.payload?.summary || '操作已完成' }}</p>
              <pre v-if="message.card.payload?.detail" class="agent-result-detail">{{ prettyDetail(message.card.payload.detail) }}</pre>
            </template>

            <div v-if="message.card.source && message.card.cardType !== 'recipe-card'" class="agent-card__source"><Clock3 :size="13" aria-hidden="true" />{{ message.card.source }}</div>
          </div>

          <details v-if="message.trace?.length" class="agent-trace">
            <summary>本次使用了哪些数据</summary>
            <span v-for="entry in message.trace" :key="entry">{{ entry }}</span>
          </details>
          <div v-if="message.error" class="agent-error-actions"><button type="button" class="agent-button agent-button--retry" @click="retryLast">重试</button></div>
        </article>
      </div>

      <div v-if="!auth.isUser" class="agent-login-hint"><LockKeyhole :size="14" aria-hidden="true" />登录后我才能读取你的真实厨房数据</div>
      <div v-if="quickPrompts.length && !messages.some((message) => message.role === 'user')" class="agent-quick-prompts">
        <button v-for="prompt in quickPrompts" :key="prompt" type="button" class="agent-quick-prompt" @click="sendPrompt(prompt)">{{ prompt }}</button>
      </div>
      <form class="agent-composer" @submit.prevent="sendMessage">
        <div v-if="attachment" class="agent-attachment-preview">
          <img :src="attachmentPreview" alt="待发送的厨房图片预览" />
          <div><strong>{{ attachment.name }}</strong><small>{{ formatFileSize(attachment.size) }} · 仅在本轮对话中处理</small></div>
          <button type="button" class="agent-icon-button" aria-label="移除附件" title="移除附件" :disabled="loading" @click="clearAttachment"><X :size="16" aria-hidden="true" /></button>
        </div>
        <label for="kitchen-agent-input" class="visually-hidden">问小厨灵</label>
        <textarea id="kitchen-agent-input" ref="input" v-model="draft" rows="2" maxlength="1000" placeholder="问问你的厨房……" :disabled="loading" @keydown="handleInputKeydown" />
        <input ref="attachmentInput" class="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp" @change="selectAttachment" />
        <div class="agent-composer__footer"><span>Enter 发送 · 图片最大 5MB</span><div class="agent-composer__actions"><button type="button" class="agent-attach-button" aria-label="添加厨房图片" title="添加图片" :disabled="loading" @click="attachmentInput?.click()"><Paperclip :size="16" aria-hidden="true" /></button><button v-if="loading" type="button" class="agent-button agent-button--stop" @click="stopGeneration"><Square :size="14" aria-hidden="true" />停止</button><button v-else type="submit" class="agent-send-button" aria-label="发送消息" :disabled="!draft.trim() && !attachment"><Send :size="16" aria-hidden="true" /></button></div></div>
      </form>
      <footer class="agent-panel__footer"><span>数据只来自当前账号</span><button type="button" @click="clearConversation">清空会话</button></footer>
    </section>

    <button
      type="button"
      class="agent-launcher"
      ref="launcher"
      :class="{ 'is-open': isOpen, 'is-thinking': loading }"
      aria-label="打开小厨灵厨房助手"
      :aria-expanded="isOpen"
      @click="togglePanel"
    >
      <span class="agent-launcher__sprite" aria-hidden="true"><img src="/sprites/1-D-1.png" alt="" /></span>
      <span class="agent-launcher__copy"><strong>小厨灵</strong><small>{{ loading ? '正在忙活…' : '厨房总管' }}</small></span>
      <span class="agent-launcher__signal" aria-hidden="true" />
    </button>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Bell, BookOpen, CalendarDays, Check, ClipboardCheck, Clock3, Database, HeartPulse, LockKeyhole, Maximize2, Minimize2, Paperclip, Save, Send, ShieldCheck, Square, X } from 'lucide-vue-next'
import { deleteAgentConversation, getAgentConversationMessages, getAgentRunStatus, getLatestAgentConversation, streamAgentChat } from '../api/agent.js'
import { useAuthStore } from '../stores/auth.js'
import { clampAgentPanelPosition } from '../utils/agentPanelPosition.js'

const auth = useAuthStore()
const isOpen = ref(false)
const loading = ref(false)
const draft = ref('')
const messages = ref([])
const conversationId = ref(null)
const runId = ref(null)
const recoveryStatus = ref('')
const trackedRunId = ref(null)
const panel = ref(null)
const messageList = ref(null)
const input = ref(null)
const launcher = ref(null)
const attachmentInput = ref(null)
const attachment = ref(null)
const attachmentPreview = ref('')
const abortController = ref(null)
const lastPrompt = ref('')
const lastAttachment = ref(null)
const quickPrompts = ['我今天有什么食材', '哪些食材快过期', '今晚能做什么', '本周菜单是什么', '我有哪些提醒']
const isExpanded = ref(false)
const desktopViewport = ref(typeof window === 'undefined' || window.innerWidth > 720)
const panelPosition = ref(null)
const savedPanelPosition = ref(null)
const isDragging = ref(false)
const isResizing = ref(false)
const panelHeight = ref(null)
const previewUrls = new Set()
let messageSeed = 0
let dragState = null
let resizeState = null
let resizeFrame = 0
let recoveryTimer = null
let recoveryAttempts = 0

const PANEL_MIN_HEIGHT = 360
const PANEL_MAX_HEIGHT = 760

const panelStyle = computed(() => {
  const style = {}
  if (panelPosition.value) {
    style.left = `${panelPosition.value.left}px`
    style.top = `${panelPosition.value.top}px`
  }
  if (!isExpanded.value && panelHeight.value) style.height = `${panelHeight.value}px`
  return Object.keys(style).length ? style : undefined
})

const storageKey = () => `ai-kitchen-agent:${auth.token ? auth.token.slice(-20) : 'guest'}`

onMounted(() => {
  void restoreConversation()
  updateViewportMode()
  window.addEventListener('resize', schedulePositionCorrection)
  window.visualViewport?.addEventListener('resize', schedulePositionCorrection)
})
onBeforeUnmount(() => {
  stopGeneration()
  stopRunTracking()
  stopDragging()
  stopResizing()
  window.removeEventListener('resize', schedulePositionCorrection)
  window.visualViewport?.removeEventListener('resize', schedulePositionCorrection)
  if (resizeFrame) window.cancelAnimationFrame(resizeFrame)
  revokeAllPreviews()
})

watch([messages, conversationId, runId], () => {
  if (auth.isUser) {
    const storedMessages = messages.value.slice(-80).map(({ imagePreview, ...message }) => message)
    localStorage.setItem(storageKey(), JSON.stringify({
      conversationId: conversationId.value,
      runId: runId.value,
      messages: storedMessages
    }))
  }
  void scrollToBottom()
}, { deep: true })

function togglePanel() {
  isOpen.value = !isOpen.value
  if (isOpen.value) {
    void nextTick(() => {
      correctPanelPosition()
      input.value?.focus()
    })
  }
}

function closePanel() {
  restorePanelPosition()
  isOpen.value = false
  void nextTick(() => launcher.value?.focus())
}

function minimizePanel() {
  isOpen.value = false
  void nextTick(() => launcher.value?.focus())
  restorePanelPosition()
}

function togglePanelExpanded() {
  if (!desktopViewport.value || !panel.value) return
  if (isExpanded.value) {
    restorePanelPosition()
    void nextTick(correctPanelPosition)
    return
  }

  const rect = panel.value.getBoundingClientRect()
  savedPanelPosition.value = clampPanelRect(rect)
  panelPosition.value = null
  isExpanded.value = true
}

function restorePanelPosition() {
  if (!isExpanded.value) return
  isExpanded.value = false
  panelPosition.value = savedPanelPosition.value ? { ...savedPanelPosition.value } : null
  savedPanelPosition.value = null
}

function startDrag(event) {
  if (!desktopViewport.value || event.button !== 0) return
  const target = event.target instanceof Element ? event.target : null
  if (target?.closest('button, a, input, textarea, select, [role="button"], .agent-panel__actions')) return
  const rect = panel.value?.getBoundingClientRect()
  if (!rect) return

  dragState = {
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    originLeft: rect.left,
    originTop: rect.top,
    width: rect.width,
    height: rect.height
  }
  isDragging.value = false
  window.addEventListener('pointermove', handleDragMove)
  window.addEventListener('pointerup', stopDragging)
  window.addEventListener('pointercancel', stopDragging)
}

function handleDragMove(event) {
  if (!dragState || event.pointerId !== dragState.pointerId) return
  const deltaX = event.clientX - dragState.startX
  const deltaY = event.clientY - dragState.startY
  if (!isDragging.value && Math.hypot(deltaX, deltaY) < 4) return
  isDragging.value = true
  event.preventDefault()
  panelPosition.value = clampAgentPanelPosition(
    { left: dragState.originLeft + deltaX, top: dragState.originTop + deltaY },
    {
      viewportWidth: window.innerWidth,
      viewportHeight: window.innerHeight,
      panelWidth: dragState.width,
      panelHeight: dragState.height
    }
  )
}

function stopDragging(event) {
  if (event?.pointerId != null && dragState?.pointerId !== event.pointerId) return
  dragState = null
  isDragging.value = false
  window.removeEventListener('pointermove', handleDragMove)
  window.removeEventListener('pointerup', stopDragging)
  window.removeEventListener('pointercancel', stopDragging)
}

function getPanelHeightBounds() {
  const viewportHeight = typeof window === 'undefined' ? PANEL_MAX_HEIGHT + 24 : window.innerHeight
  const min = Math.min(PANEL_MIN_HEIGHT, Math.max(280, viewportHeight - 24))
  const max = Math.max(min, Math.min(PANEL_MAX_HEIGHT, viewportHeight - 24))
  return { min, max }
}

function clampPanelHeight(height) {
  const { min, max } = getPanelHeightBounds()
  return Math.min(max, Math.max(min, height))
}

function startResize(event) {
  if (event.button !== 0 || isExpanded.value) return
  const rect = panel.value?.getBoundingClientRect()
  if (!rect) return

  resizeState = {
    pointerId: event.pointerId,
    startY: event.clientY,
    startHeight: rect.height
  }
  isResizing.value = false
  event.currentTarget?.setPointerCapture?.(event.pointerId)
  window.addEventListener('pointermove', handleResizeMove)
  window.addEventListener('pointerup', stopResizing)
  window.addEventListener('pointercancel', stopResizing)
}

function handleResizeMove(event) {
  if (!resizeState || event.pointerId !== resizeState.pointerId) return
  const deltaY = resizeState.startY - event.clientY
  if (!isResizing.value && Math.abs(deltaY) < 2) return
  isResizing.value = true
  event.preventDefault()
  panelHeight.value = clampPanelHeight(resizeState.startHeight + deltaY)
}

function stopResizing(event) {
  if (event?.pointerId != null && resizeState?.pointerId !== event.pointerId) return
  resizeState = null
  isResizing.value = false
  window.removeEventListener('pointermove', handleResizeMove)
  window.removeEventListener('pointerup', stopResizing)
  window.removeEventListener('pointercancel', stopResizing)
}

function handleResizeKeydown(event) {
  if (isExpanded.value) return
  const step = event.shiftKey ? 120 : 48
  if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
    event.preventDefault()
    const currentHeight = panelHeight.value || panel.value?.getBoundingClientRect().height || PANEL_MIN_HEIGHT
    panelHeight.value = clampPanelHeight(currentHeight + (event.key === 'ArrowUp' ? step : -step))
  } else if (event.key === 'Home') {
    event.preventDefault()
    panelHeight.value = getPanelHeightBounds().min
  } else if (event.key === 'End') {
    event.preventDefault()
    panelHeight.value = getPanelHeightBounds().max
  }
}

function clampPanelRect(rect) {
  return clampAgentPanelPosition(
    { left: rect.left, top: rect.top },
    {
      viewportWidth: window.innerWidth,
      viewportHeight: window.innerHeight,
      panelWidth: rect.width,
      panelHeight: rect.height
    }
  )
}

function correctPanelPosition() {
  updateViewportMode()
  const rect = panel.value?.getBoundingClientRect()
  if (!rect || !panelPosition.value || !desktopViewport.value) return
  panelPosition.value = clampPanelRect(rect)
}

function updateViewportMode() {
  const nextDesktop = window.innerWidth > 720
  desktopViewport.value = nextDesktop
  if (!nextDesktop) {
    isExpanded.value = false
    panelPosition.value = null
    savedPanelPosition.value = null
  }
}

function schedulePositionCorrection() {
  updateViewportMode()
  if (resizeFrame) return
  resizeFrame = window.requestAnimationFrame(() => {
    resizeFrame = 0
    correctPanelPosition()
  })
}

function handleInputKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendMessage()
  }
}

function sendMessage() {
  const message = draft.value.trim()
  if ((!message && !attachment.value) || loading.value) return
  const image = attachment.value
  const preview = attachmentPreview.value
  draft.value = ''
  attachment.value = null
  attachmentPreview.value = ''
  if (attachmentInput.value) attachmentInput.value.value = ''
  sendPrompt(message || '请识别这张图片中的食材，并告诉我可以怎么处理', image, preview)
}

function sendPrompt(prompt, image = null, imagePreview = '') {
  if (loading.value) return
  lastPrompt.value = prompt
  lastAttachment.value = image
  if (!auth.isUser) {
    addMessage({ role: 'assistant', content: '请先登录普通用户账号。登录后我才能读取你的真实库存、菜单和提醒。' })
    return
  }
  addMessage({ role: 'user', content: prompt, imagePreview })
  startStream({ conversationId: conversationId.value, message: prompt }, image)
}

async function startStream(payload, image = null) {
  loading.value = true
  const assistant = addMessage({ role: 'assistant', content: '', statusText: '小厨灵正在整理请求', trace: [] })
  abortController.value = new AbortController()
  let trackingRecovery = false
  try {
    await streamAgentChat(payload, { image, signal: abortController.value.signal, onEvent: (event) => applyEvent(event, assistant) })
  } catch (error) {
    if (error?.name !== 'AbortError') {
      if (runId.value) {
        trackingRecovery = true
        assistant.statusText = '连接已断开，后台仍在继续生成'
        recoveryStatus.value = '连接已断开，正在读取 Redis 中的运行状态…'
        void trackRun(runId.value, assistant)
      } else {
        assistant.error = true
        assistant.content = error?.message || '小厨灵暂时没有完成这次操作，请点击重试。'
      }
    }
  } finally {
    if (!trackingRecovery) loading.value = false
    abortController.value = null
    if (!trackingRecovery) assistant.statusText = ''
    void scrollToBottom()
  }
}

function applyEvent(event, assistant) {
  const data = event.data || {}
  if (event.type === 'conversation.ready') {
    conversationId.value = data.conversationId
    runId.value = data.runId || runId.value
    recoveryStatus.value = ''
  }
  if (event.type === 'message.delta') assistant.content += data.content || ''
  if (event.type === 'tool.started') {
    assistant.statusText = data.label || '小厨灵正在处理'
    if (data.label && !assistant.trace.includes(data.label)) assistant.trace.push(data.label)
  }
  if (event.type === 'tool.result') {
    assistant.statusText = data.summary || '已完成数据读取'
    if (data.summary && !assistant.trace.includes(data.summary)) assistant.trace.push(data.summary)
  }
  if (event.type === 'card') assistant.card = data
  if (event.type === 'confirmation.required') assistant.card = { cardType: 'confirmation-card', ...data }
  if (event.type === 'error') {
    assistant.error = true
    assistant.content = data.message || '小厨灵暂时没有完成这次操作，请点击重试。'
  }
}

async function confirmAction(message) {
  if (loading.value || !message.card?.confirmationId) return
  message.card.confirmed = true
  addMessage({ role: 'user', content: `确认执行：${message.card.title || '厨房操作'}` })
  await startStream({ conversationId: conversationId.value, confirmationId: message.card.confirmationId, idempotencyKey: message.card.idempotencyKey })
}

function cancelConfirmation(message) {
  message.card.confirmed = true
  addMessage({ role: 'assistant', content: '好的，这次操作没有执行。' })
}

function saveRecipe() {
  sendPrompt('保存这道菜')
}

function retryLast() {
  if (lastPrompt.value) sendPrompt(lastPrompt.value, lastAttachment.value)
}

function stopGeneration() {
  abortController.value?.abort()
  abortController.value = null
  loading.value = false
}

async function clearConversation() {
  stopGeneration()
  stopRunTracking()
  clearAttachment()
  revokeAllPreviews()
  if (conversationId.value) {
    try { await deleteAgentConversation(conversationId.value) } catch { /* A local reset is still safe if the network is unavailable. */ }
  }
  localStorage.removeItem(storageKey())
  conversationId.value = null
  runId.value = null
  recoveryStatus.value = ''
  messages.value = []
}

function addMessage(message) {
  const target = { id: `agent-message-${++messageSeed}`, ...message }
  messages.value.push(target)
  return target
}

async function restoreConversation() {
  if (!auth.isUser) return
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey()) || 'null')
    conversationId.value = saved?.conversationId || null
    runId.value = saved?.runId || null
    messages.value = Array.isArray(saved?.messages) ? saved.messages : []
  } catch {
    messages.value = []
  }

  try {
    const response = await getLatestAgentConversation()
    const history = response?.data?.data
    if (history) {
      conversationId.value = history.conversationId || null
      messages.value = (history.messages || []).map(toUiMessage)
      runId.value = history.activeRun?.runId || null
    }
  } catch {
    // The local cache remains usable when the history endpoint is unavailable.
  }

  if (runId.value) {
    const recoveryMessage = messages.value.some((message) => message.recoveryMessage)
      ? messages.value.find((message) => message.recoveryMessage)
      : null
    const statusMessage = recoveryMessage || addMessage({
      role: 'assistant',
      content: '',
      statusText: '正在检查上次运行状态',
      trace: [],
      recoveryMessage: true
    })
    void trackRun(runId.value, statusMessage)
  }
}

function toUiMessage(message) {
  const role = String(message?.role || '').toUpperCase() === 'USER' ? 'user' : 'assistant'
  const blockType = message?.blockType || 'text'
  const target = {
    id: `agent-history-${message?.id || ++messageSeed}`,
    role,
    content: role === 'user' || blockType === 'text' ? (message?.content || '') : '',
    trace: [],
    restored: true
  }
  if (role === 'assistant' && blockType !== 'text') {
    try {
      const parsed = JSON.parse(message?.content || '{}')
      if (blockType === 'confirmation-card') {
        target.card = { cardType: blockType, ...parsed, source: '来自已保存会话' }
      } else if (blockType === 'recipe-card') {
        target.card = {
          cardType: blockType,
          payload: { recipe: parsed },
          source: '来自已保存会话'
        }
      } else {
        target.card = { cardType: blockType, payload: parsed, source: '来自已保存会话' }
      }
    } catch {
      target.content = message?.content || ''
    }
  }
  return target
}

async function trackRun(targetRunId, assistant) {
  if (!targetRunId || trackedRunId.value === targetRunId) return
  stopRunTracking()
  trackedRunId.value = targetRunId
  recoveryAttempts = 0
  loading.value = true
  recoveryStatus.value = '正在读取 Redis 中的运行状态…'

  const poll = async () => {
    if (trackedRunId.value !== targetRunId) return
    try {
      const response = await getAgentRunStatus(targetRunId)
      const status = response?.data?.data
      if (!status) throw new Error('运行状态为空')
      recoveryAttempts = 0

      if (status.status === 'RUNNING') {
        recoveryStatus.value = '小厨灵仍在后台生成，页面会自动更新'
        if (assistant) assistant.statusText = '小厨灵仍在后台生成'
      } else if (status.status === 'RECOVERING') {
        recoveryStatus.value = '服务已恢复，小厨灵正在从 checkpoint 继续'
        if (assistant) assistant.statusText = '正在从 checkpoint 继续'
      } else if (status.status === 'WAITING_CONFIRMATION') {
        recoveryStatus.value = '上次操作等待你的确认'
        if (assistant) assistant.statusText = '等待你的确认'
        // 等待确认不是后台执行中：停止恢复轮询并释放 loading，确认卡片仍可提交。
        finishRunTracking()
        return
      } else if (status.status === 'COMPLETED') {
        recoveryStatus.value = '恢复完成，正在加载最新结果'
        await loadConversationHistory(status.conversationId || conversationId.value)
        finishRunTracking()
        return
      } else {
        if (assistant) {
          assistant.error = true
          assistant.content = status.errorMessage || '上次运行未能完成，请重试。'
          assistant.statusText = ''
        }
        recoveryStatus.value = ''
        finishRunTracking()
        return
      }

      recoveryTimer = window.setTimeout(poll, 2000)
    } catch {
      recoveryAttempts += 1
      if (recoveryAttempts >= 5) {
        if (assistant) {
          assistant.error = true
          assistant.content = '暂时无法读取上次运行状态，请稍后刷新重试。'
          assistant.statusText = ''
        }
        recoveryStatus.value = ''
        finishRunTracking()
        return
      }
      recoveryTimer = window.setTimeout(poll, 2000)
    }
  }

  await poll()
}

async function loadConversationHistory(targetConversationId) {
  if (!targetConversationId) return
  try {
    const response = await getAgentConversationMessages(targetConversationId)
    const history = response?.data?.data
    if (history) {
      conversationId.value = history.conversationId
      messages.value = (history.messages || []).map(toUiMessage)
    }
  } catch {
    // The next refresh can retry the server-side history load.
  }
}

function stopRunTracking() {
  if (recoveryTimer != null) {
    window.clearTimeout(recoveryTimer)
    recoveryTimer = null
  }
  trackedRunId.value = null
  recoveryAttempts = 0
}

function finishRunTracking() {
  stopRunTracking()
  loading.value = false
  recoveryStatus.value = ''
  void scrollToBottom()
}

function visibleItems(items = [], message) {
  return message.expanded ? items : items.slice(0, 5)
}

function hasMore(items = []) {
  return items.length > 5
}

function toggleExpanded(message) {
  message.expanded = !message.expanded
}

function formatQuantity(quantity, unit) {
  if (quantity === null || quantity === undefined || quantity === '') return '数量未设'
  return `${quantity}${unit || ''}`
}

function statusClass(status) {
  return status === '已过期' ? 'is-danger' : status === '临期' ? 'is-warning' : 'is-normal'
}

function reminderLabel(type) {
  if (type?.includes('PANTRY')) return '库存'
  if (type?.includes('WEEKLY')) return '菜单'
  return '提醒'
}

function mealLabel(value) {
  return ({ BREAKFAST: '早餐', LUNCH: '午餐', DINNER: '晚餐' })[value] || value || '餐次'
}

function formatDate(value) {
  if (!value) return '—'
  return String(value).slice(5).replace('-', '月') + '日'
}

function formatDateTime(value) {
  if (!value) return '刚刚'
  return String(value).replace('T', ' ').slice(0, 16)
}

function nutritionValue(value, unit) {
  return value === null || value === undefined ? `未设置${unit}` : `${value} ${unit}`
}

function goalLabel(value) {
  return ({ balanced: '均衡', fat_loss: '减脂', muscle_gain: '增肌' })[value] || value || '均衡'
}

function selectAttachment(event) {
  const file = event.target.files?.[0]
  if (!file) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    addMessage({ role: 'assistant', content: '附件仅支持 JPG、PNG、WebP 图片。', error: true })
    event.target.value = ''
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    addMessage({ role: 'assistant', content: '附件图片不能超过 5MB。', error: true })
    event.target.value = ''
    return
  }
  revokeAttachmentPreview()
  attachment.value = file
  attachmentPreview.value = URL.createObjectURL(file)
  previewUrls.add(attachmentPreview.value)
}

function clearAttachment() {
  revokeAttachmentPreview()
  attachment.value = null
  if (attachmentInput.value) attachmentInput.value.value = ''
}

function revokeAttachmentPreview() {
  if (attachmentPreview.value?.startsWith('blob:')) {
    URL.revokeObjectURL(attachmentPreview.value)
    previewUrls.delete(attachmentPreview.value)
  }
  attachmentPreview.value = ''
}

function revokeAllPreviews() {
  previewUrls.forEach((url) => URL.revokeObjectURL(url))
  previewUrls.clear()
  attachmentPreview.value = ''
}

function formatFileSize(size) {
  return size < 1024 * 1024 ? `${Math.max(1, Math.round(size / 1024))} KB` : `${(size / 1024 / 1024).toFixed(1)} MB`
}

function prettyDetail(detail) {
  if (typeof detail === 'string') return detail
  return JSON.stringify(detail, null, 2)
}

async function scrollToBottom() {
  await nextTick()
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}
</script>

<style scoped>
.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
.agent-widget { position: fixed; left: 14px; bottom: 18px; z-index: 55; font-family: "Microsoft YaHei", "PingFang SC", sans-serif; color: #3b2b21; }
.agent-launcher { position: relative; display: inline-flex; align-items: center; gap: 9px; min-width: 148px; min-height: 58px; padding: 6px 12px 6px 7px; border: 2px solid #3b2b21; border-radius: 4px; color: #3b2b21; background: #f5e6c3; box-shadow: 4px 4px 0 rgba(59, 43, 33, .25); font: inherit; text-align: left; cursor: pointer; transition: transform 160ms ease, background-color 160ms ease, box-shadow 160ms ease; touch-action: manipulation; }
.agent-launcher:hover, .agent-launcher:focus-visible { background: #fff6df; outline: 2px solid #4f8ca5; outline-offset: 3px; transform: translateY(-2px); box-shadow: 5px 6px 0 rgba(59, 43, 33, .25); }
.agent-launcher:active { transform: translate(2px, 2px); box-shadow: 2px 2px 0 rgba(59, 43, 33, .25); }
.agent-launcher.is-open { background: #ffe5a4; }
.agent-launcher__sprite, .agent-mini-sprite { display: grid; place-items: center; overflow: hidden; border: 2px solid #3b2b21; background: #2b211d; image-rendering: pixelated; }
.agent-launcher__sprite { width: 44px; height: 44px; flex: 0 0 44px; }
.agent-launcher__sprite img, .agent-mini-sprite img { width: 100%; height: 100%; object-fit: contain; image-rendering: pixelated; }
.agent-launcher__copy { display: grid; gap: 2px; min-width: 0; }
.agent-launcher__copy strong { font-size: 15px; font-weight: 900; }
.agent-launcher__copy small { color: #8b6846; font-size: 10px; font-weight: 800; white-space: nowrap; }
.agent-launcher__signal { width: 7px; height: 7px; margin-left: auto; border-radius: 50%; background: #5d9a6e; box-shadow: 0 0 0 3px rgba(93,154,110,.16); }
.agent-launcher.is-thinking .agent-launcher__signal { background: #e5a83f; animation: agent-pulse 900ms ease-in-out infinite alternate; }
.agent-backdrop { display: none; }
.agent-panel { position: fixed; left: 14px; bottom: 88px; display: flex; flex-direction: column; width: min(410px, calc(100vw - 32px)); height: min(720px, calc(100dvh - 104px)); overflow: hidden; border: 2px solid #3b2b21; border-radius: 5px; background: #fff8e8; box-shadow: 8px 10px 0 rgba(59,43,33,.18), 0 20px 50px rgba(59,43,33,.18); }
.agent-panel.is-expanded { left: 50%; top: 50%; bottom: auto; width: min(900px, calc(100vw - 48px)); height: min(760px, calc(100dvh - 48px)); transform: translate(-50%, -50%); }
.agent-panel.is-expanded.is-positioned { transform: none; }
.agent-panel.is-dragging { cursor: grabbing; user-select: none; }
.agent-panel__header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 13px 14px; border-bottom: 1px solid #d6b989; background: #f5e6c3; cursor: grab; user-select: none; touch-action: none; }
.agent-resize-handle { display: grid; flex: 0 0 auto; width: 100%; height: 44px; place-items: center; padding: 0; border: 0; border-bottom: 1px solid #ead9b9; color: #a36e2d; background: #fff8e8; cursor: ns-resize; touch-action: none; }
.agent-resize-handle span { width: 42px; height: 5px; border: 1px solid currentColor; border-radius: 999px; background: currentColor; opacity: .58; }
.agent-resize-handle:hover, .agent-resize-handle:focus-visible { color: #4f8ca5; background: #fff4d6; outline: 2px solid #4f8ca5; outline-offset: -3px; }
.agent-resize-handle:hover span, .agent-resize-handle:focus-visible span { opacity: 1; }
.agent-panel.is-resizing { user-select: none; }
.agent-panel.is-expanded .agent-resize-handle { display: none; }
.agent-panel__identity { display: flex; align-items: center; gap: 9px; min-width: 0; }
.agent-mini-sprite { width: 32px; height: 32px; flex: 0 0 32px; }
.agent-panel__eyebrow { margin: 0 0 2px; color: #a36e2d; font-size: 10px; font-weight: 900; letter-spacing: .1em; }
.agent-panel h2 { margin: 0; overflow-wrap: anywhere; font-size: 16px; line-height: 1.25; }
.agent-panel__actions { display: flex; flex: 0 0 auto; gap: 4px; touch-action: auto; }
.agent-icon-button { display: inline-grid; width: 44px; height: 44px; place-items: center; border: 1px solid transparent; color: #654b37; background: transparent; cursor: pointer; }
.agent-icon-button:hover, .agent-icon-button:focus-visible { border-color: #9b754b; background: #fff4d6; outline: 2px solid #4f8ca5; outline-offset: 2px; }
.agent-pixel-minus { display: block; width: 14px; height: 3px; background: currentColor; }
.agent-recovery-banner { display: flex; align-items: center; gap: 7px; padding: 7px 14px; border-bottom: 1px solid #e8c66d; color: #855c14; background: #fff4c8; font-size: 11px; font-weight: 900; }
.agent-recovery-banner__dot { width: 7px; height: 7px; flex: 0 0 7px; border-radius: 50%; background: #e0a239; animation: agent-pulse 800ms ease-in-out infinite alternate; }
.agent-messages { flex: 1; min-height: 0; overflow-y: auto; padding: 14px; scroll-behavior: smooth; }
.agent-empty-state { display: grid; gap: 8px; margin: 12px 0 14px; padding: 17px; border: 1px dashed #c8aa7b; background: #fffdf4; }
.agent-empty-state__stamp, .agent-card__stamp { width: max-content; padding: 3px 6px; border: 1px solid #a36e2d; color: #a36e2d; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 10px; font-weight: 900; letter-spacing: .08em; }
.agent-empty-state strong { font-size: 16px; }
.agent-empty-state p { margin: 0; color: #80664a; font-size: 13px; line-height: 1.6; }
.agent-message { display: grid; gap: 7px; margin-bottom: 14px; max-width: 94%; }
.agent-message--user { margin-left: auto; justify-items: end; }
.agent-message__bubble { padding: 10px 12px; border: 1px solid #d7bd91; color: #4b3728; background: #fffdf5; font-size: 14px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.agent-message--user .agent-message__bubble { border-color: #4f8ca5; color: #fff; background: #4f8ca5; }
.agent-message__image { display: block; width: min(220px, 72vw); max-height: 180px; object-fit: cover; border: 2px solid #4f8ca5; background: #fffdf5; }
.agent-message__bubble.is-error { border-color: #cf7161; color: #8b3e34; background: #fff0ec; }
.agent-status-line { display: flex; align-items: center; gap: 7px; color: #a36e2d; font-size: 11px; font-weight: 900; }
.agent-status-dot { width: 7px; height: 7px; border-radius: 50%; background: #65a074; }
.agent-status-dot.spinning { background: #e0a239; animation: agent-pulse 800ms ease-in-out infinite alternate; }
.agent-card { display: grid; gap: 10px; padding: 12px; border: 2px solid #c8aa7b; background: #fffdf5; box-shadow: 3px 3px 0 rgba(82,56,36,.1); }
.agent-card__head, .agent-recipe-card__topline, .agent-card__actions { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.agent-card__head > div, .agent-confirmation__title { display: flex; align-items: center; gap: 6px; }
.agent-card__head strong, .agent-confirmation__title strong { font-size: 13px; }
.agent-card__head > span, .agent-recipe-card__topline > strong { color: #8a6846; font-size: 11px; font-weight: 800; }
.agent-card__metrics { display: flex; gap: 14px; color: #80664a; font-size: 11px; font-weight: 800; }
.metric-dot { display: inline-block; width: 7px; height: 7px; margin-right: 4px; border-radius: 50%; }
.metric-dot--warn { background: #d49b35; }.metric-dot--danger { background: #c75b4d; }
.agent-card__list { display: grid; gap: 5px; }
.agent-card__row { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 8px; min-height: 30px; padding: 5px 7px; border: 1px solid #ead9b9; background: #fffaf0; font-size: 12px; }
.agent-card__row strong { overflow-wrap: anywhere; }.agent-card__row > span { color: #6d543d; font-variant-numeric: tabular-nums; }.agent-card__row em { padding: 2px 4px; border: 1px solid currentColor; font-size: 10px; font-style: normal; font-weight: 900; white-space: nowrap; }.agent-card__row em.is-danger { color: #ae554a; }.agent-card__row em.is-warning { color: #ad7c20; }.agent-card__row em.is-normal { color: #5d8966; }
.agent-card__row--menu { grid-template-columns: 48px 38px minmax(0, 1fr); }.agent-card__row--menu span:last-child { overflow-wrap: anywhere; }.agent-card__row--recipe-list { grid-template-columns: minmax(0, 1fr) auto; }
.agent-card__expand { min-height: 44px; border: 1px dashed #bc9767; color: #8b6846; background: transparent; font: inherit; font-size: 11px; font-weight: 900; cursor: pointer; }.agent-card__expand:hover, .agent-card__expand:focus-visible { color: #3b2b21; background: #fff4d6; outline: 2px solid #4f8ca5; outline-offset: 2px; }
.agent-card__source { display: flex; align-items: center; gap: 5px; color: #8b765c; font-size: 10px; line-height: 1.4; }
.agent-card__empty, .agent-card__note { margin: 0; color: #80664a; font-size: 12px; line-height: 1.6; }
.agent-reminder-row { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 8px; align-items: start; padding: 7px; border-bottom: 1px solid #ead9b9; }.agent-reminder-row__badge { padding: 3px 5px; color: #93672d; background: #f8e5b4; font-size: 10px; font-weight: 900; }.agent-reminder-row div { display: grid; gap: 2px; min-width: 0; }.agent-reminder-row small { color: #80664a; overflow-wrap: anywhere; }
.agent-nutrition-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 6px; }.agent-nutrition-grid div { display: grid; gap: 4px; padding: 8px 6px; border: 1px solid #ead9b9; background: #fffaf0; }.agent-nutrition-grid small { color: #8b765c; font-size: 10px; }.agent-nutrition-grid strong { font-size: 11px; overflow-wrap: anywhere; }
.agent-recipe-card__topline { color: #a36e2d; }.agent-card h3 { margin: 0; font-size: 19px; line-height: 1.35; }.agent-recipe-card__summary { margin: -3px 0 0; color: #80664a; font-size: 13px; line-height: 1.6; }.agent-recipe-card__section { display: grid; gap: 6px; padding-top: 9px; border-top: 1px solid #ead9b9; }.agent-recipe-card__section > strong { font-size: 12px; }.agent-recipe-card__chips { display: flex; flex-wrap: wrap; gap: 5px; }.agent-recipe-card__chips span { padding: 4px 6px; border: 1px solid #d5b77f; color: #5d4936; background: #fff4d6; font-size: 11px; }.agent-recipe-card__section--missing p { margin: 0; color: #a45246; font-size: 12px; line-height: 1.5; }.agent-recipe-card__section ol { display: grid; gap: 7px; margin: 0; padding-left: 22px; color: #5d4936; font-size: 12px; line-height: 1.55; }.agent-recipe-card__section li span { margin-right: 4px; color: #a36e2d; font-weight: 900; }.agent-recipe-card__nutrition { padding: 7px 8px; color: #4f7660; background: #e8f1e3; font-size: 11px; font-weight: 800; }
.agent-button { display: inline-flex; align-items: center; justify-content: center; gap: 5px; min-height: 44px; padding: 0 10px; border: 1px solid #b99562; color: #5d4936; background: #fffaf0; font: inherit; font-size: 11px; font-weight: 900; cursor: pointer; }.agent-button:hover, .agent-button:focus-visible { border-color: #4f8ca5; background: #fff4d6; outline: 2px solid #4f8ca5; outline-offset: 2px; }.agent-button--primary { border-color: #3d7866; color: #fff; background: #3d7866; }.agent-button--stop { border-color: #c75b4d; color: #9b4037; background: #fff0ec; }.agent-button--retry { min-width: 64px; color: #9b4037; background: #fff0ec; }.agent-button:disabled { opacity: .5; cursor: not-allowed; }
.agent-confirmation__title { color: #986424; }.agent-card--confirmation-card p { margin: 0; font-size: 13px; line-height: 1.6; }.agent-card--confirmation-card small { color: #8b765c; font-size: 11px; line-height: 1.5; }
.agent-result-detail { max-height: 220px; margin: 0; overflow: auto; padding: 9px; border: 1px solid #ead9b9; color: #5d4936; background: #fffaf0; font: 11px/1.55 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.agent-trace { color: #8b765c; font-size: 10px; }.agent-trace summary { width: max-content; color: #876c4d; cursor: pointer; }.agent-trace span { display: block; margin-top: 4px; padding-left: 10px; overflow-wrap: anywhere; }.agent-trace span::before { content: '·'; margin-right: 5px; color: #a36e2d; }
.agent-error-actions { display: flex; justify-content: flex-start; }.agent-login-hint { display: flex; align-items: center; gap: 6px; padding: 8px 14px; border-top: 1px solid #ead9b9; color: #986424; background: #fff4d6; font-size: 11px; font-weight: 800; }
.agent-quick-prompts { display: flex; flex-wrap: wrap; gap: 6px; overflow-x: hidden; padding: 8px 14px 10px; border-top: 1px solid #ead9b9; }.agent-quick-prompt { min-height: 44px; padding: 0 9px; border: 1px solid #c8aa7b; color: #6d543d; background: #fffdf5; font: inherit; font-size: 11px; font-weight: 800; white-space: nowrap; cursor: pointer; }.agent-quick-prompt:hover, .agent-quick-prompt:focus-visible { border-color: #4f8ca5; background: #fff4d6; outline: 2px solid #4f8ca5; outline-offset: 2px; }
.agent-composer { display: grid; gap: 6px; padding: 10px 14px; border-top: 1px solid #d6b989; background: #f5e6c3; }.agent-composer textarea { width: 100%; min-height: 58px; resize: vertical; padding: 9px 10px; border: 1px solid #b99562; border-radius: 0; color: #3b2b21; background: #fffdf5; font: inherit; font-size: 14px; line-height: 1.5; outline: none; }.agent-composer textarea:focus { border-color: #4f8ca5; box-shadow: 0 0 0 2px rgba(79,140,165,.22); }.agent-composer textarea:disabled { opacity: .65; }.agent-composer__footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #8b765c; font-size: 10px; }.agent-send-button { display: inline-grid; width: 44px; height: 44px; place-items: center; border: 1px solid #3d7866; color: #fff; background: #3d7866; cursor: pointer; }.agent-send-button:hover, .agent-send-button:focus-visible { background: #2f6655; outline: 2px solid #4f8ca5; outline-offset: 2px; }.agent-send-button:disabled { opacity: .5; cursor: not-allowed; }
.agent-composer__actions { display: flex; align-items: center; gap: 7px; }.agent-attach-button { display: inline-grid; width: 44px; height: 44px; place-items: center; border: 1px solid #b99562; color: #654b37; background: #fffaf0; cursor: pointer; }.agent-attach-button:hover, .agent-attach-button:focus-visible { border-color: #4f8ca5; background: #fff4d6; outline: 2px solid #4f8ca5; outline-offset: 2px; }.agent-attach-button:disabled { opacity: .5; cursor: not-allowed; }
.agent-attachment-preview { display: grid; grid-template-columns: 48px minmax(0, 1fr) 44px; align-items: center; gap: 8px; padding: 7px; border: 1px solid #b99562; background: #fffaf0; }.agent-attachment-preview img { width: 48px; height: 48px; object-fit: cover; border: 1px solid #d6b989; }.agent-attachment-preview div { display: grid; gap: 3px; min-width: 0; }.agent-attachment-preview strong, .agent-attachment-preview small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.agent-attachment-preview strong { font-size: 12px; }.agent-attachment-preview small { color: #8b765c; font-size: 10px; }
.agent-panel__footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 7px 14px 9px; color: #9a8060; background: #fff8e8; font-size: 10px; }.agent-panel__footer button { border: 0; color: #9b4037; background: transparent; font: inherit; font-weight: 800; cursor: pointer; }.agent-panel__footer button:hover, .agent-panel__footer button:focus-visible { text-decoration: underline; outline: 2px solid #4f8ca5; outline-offset: 2px; }
@keyframes agent-pulse { from { opacity: .45; transform: scale(.8); } to { opacity: 1; transform: scale(1.1); } }
@media (max-width: 720px) { .agent-widget { left: 16px; right: 16px; bottom: max(16px, env(safe-area-inset-bottom)); } .agent-launcher { width: 100%; justify-content: flex-start; } .agent-panel { position: fixed; left: 0; right: 0; bottom: 0; width: 100%; height: min(78dvh, 760px); max-height: calc(100dvh - 18px); border-width: 2px 2px 0; border-radius: 16px 16px 0 0; z-index: 2; } .agent-panel__header { cursor: default; user-select: auto; touch-action: auto; } .agent-backdrop { display: block; position: fixed; inset: 0; z-index: 1; background: rgba(43,33,29,.28); } .agent-messages { padding-bottom: 10px; } }
@media (prefers-reduced-motion: reduce) { .agent-launcher, .agent-launcher:hover, .agent-launcher:active { transition: none; transform: none; } .agent-launcher.is-thinking .agent-launcher__signal, .agent-status-dot.spinning, .agent-recovery-banner__dot { animation: none; } .agent-messages { scroll-behavior: auto; } }
</style>
