<template>
  <section v-if="eligible || checkError" class="memory-response-feedback" aria-label="个人记忆反馈">
    <template v-if="eligible">
      <p>这条回复中的个人记忆对你有帮助吗？</p>
      <div class="memory-response-feedback__options" role="group" aria-label="选择个人记忆反馈">
        <button
          v-for="option in options"
          :key="option.value"
          type="button"
          :disabled="submitting"
          :aria-pressed="feedbackType === option.value"
          :class="{ 'is-selected': feedbackType === option.value }"
          @click="submit(option.value)"
        >
          {{ option.label }}
        </button>
      </div>
      <p v-if="statusText" class="memory-response-feedback__status" role="status" aria-live="polite">
        {{ statusText }}
      </p>
      <p v-if="errorText" class="memory-response-feedback__error" role="alert">{{ errorText }}</p>
      <div class="memory-target-feedback">
        <p class="memory-target-feedback__heading">也可以逐条检查本次实际用到的记忆</p>
        <p v-if="targetsLoading" class="memory-response-feedback__status" role="status">正在读取使用过的记忆…</p>
        <div v-else-if="targetsError" class="memory-target-feedback__error" role="alert">
          <span>{{ targetsError }}</span>
          <button type="button" :disabled="targetsLoading" @click="loadTargets">重试</button>
        </div>
        <article v-for="target in targets" :key="targetKey(target)" class="memory-target-feedback__item">
          <div class="memory-target-feedback__title-row">
            <span class="memory-target-feedback__kind">
              {{ target.sourceKind === 'MEMORY_ITEM' ? '画像记忆' : '行为记忆' }}
            </span>
            <strong>{{ target.title || '记忆内容' }}</strong>
          </div>
          <p v-if="target.detail" class="memory-target-feedback__detail">{{ target.detail }}</p>
          <div class="memory-response-feedback__options" role="group" :aria-label="`对“${target.title || '记忆内容'}”的反馈`">
            <button
              v-for="option in options"
              :key="option.value"
              type="button"
              :disabled="submittingTargetKey === targetKey(target)"
              :aria-pressed="target.feedbackType === option.value"
              :class="{ 'is-selected': target.feedbackType === option.value }"
              @click="submitTarget(target, option.value)"
            >
              {{ option.label }}
            </button>
          </div>
          <p v-if="targetStatus[targetKey(target)]" class="memory-response-feedback__status" role="status" aria-live="polite">
            {{ targetStatus[targetKey(target)] }}
          </p>
          <p v-if="targetErrors[targetKey(target)]" class="memory-response-feedback__error" role="alert">
            {{ targetErrors[targetKey(target)] }}
          </p>
        </article>
        <p v-if="!targetsLoading && !targetsError && !targets.length" class="memory-response-feedback__status">
          本次只使用了结构化画像，没有单独注入某条记忆。
        </p>
        <a v-if="needsCorrection" class="memory-target-feedback__manage" href="/?station=account">
          去账号中心检查并修改记忆
        </a>
      </div>
    </template>
    <template v-else>
      <span class="memory-response-feedback__status" role="status">暂时无法确认本次回复是否使用了个人记忆。</span>
      <button type="button" class="memory-response-feedback__retry" :disabled="checking" @click="loadStatus">
        {{ checking ? '检查中…' : '重试' }}
      </button>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  getMemoryFeedbackStatus,
  getMemoryFeedbackTargets,
  submitMemoryFeedback,
  submitMemoryTargetFeedback
} from '../api/memory.js'

const props = defineProps({
  traceId: { type: String, required: true },
  initialFeedbackType: { type: String, default: null }
})

const emit = defineEmits(['feedback-change'])
const options = [
  { value: 'HELPFUL', label: '有帮助' },
  { value: 'NOT_RELEVANT', label: '不相关' },
  { value: 'INCORRECT', label: '不准确' },
  { value: 'OUTDATED', label: '已过期' }
]
const eligible = ref(false)
const checking = ref(false)
const checkError = ref(false)
const submitting = ref(false)
const feedbackType = ref(props.initialFeedbackType)
const statusText = ref('')
const errorText = ref('')
const targets = ref([])
const targetsLoading = ref(false)
const targetsError = ref('')
const submittingTargetKey = ref('')
const targetStatus = ref({})
const targetErrors = ref({})
const needsCorrection = computed(() => targets.value.some((target) =>
  target.feedbackType === 'INCORRECT' || target.feedbackType === 'OUTDATED'))

onMounted(loadStatus)

async function loadStatus() {
  checking.value = true
  checkError.value = false
  try {
    const response = await getMemoryFeedbackStatus(props.traceId)
    const status = response?.data?.data
    eligible.value = status?.eligible === true
    if (eligible.value) {
      feedbackType.value = status.feedbackType || null
      emit('feedback-change', feedbackType.value)
      await loadTargets()
    }
  } catch (error) {
    checkError.value = error?.response?.status !== 404
  } finally {
    checking.value = false
  }
}

async function loadTargets() {
  targetsLoading.value = true
  targetsError.value = ''
  try {
    const response = await getMemoryFeedbackTargets(props.traceId)
    targets.value = Array.isArray(response?.data?.data) ? response.data.data : []
  } catch (error) {
    targetsError.value = error?.response?.data?.message || '读取本次使用的记忆失败，请重试。'
  } finally {
    targetsLoading.value = false
  }
}

async function submit(type) {
  if (submitting.value || !eligible.value) return
  submitting.value = true
  statusText.value = '正在提交反馈…'
  errorText.value = ''
  try {
    const response = await submitMemoryFeedback(props.traceId, type)
    const result = response?.data?.data
    feedbackType.value = result?.feedbackType || type
    emit('feedback-change', feedbackType.value)
    statusText.value = result?.updated ? '反馈已更新，可以重新选择。' : '反馈已记录，可以重新选择。'
  } catch (error) {
    statusText.value = ''
    errorText.value = error?.response?.data?.message || '反馈提交失败，请重试。'
  } finally {
    submitting.value = false
  }
}

async function submitTarget(target, type) {
  const key = targetKey(target)
  if (!key || submittingTargetKey.value) return
  submittingTargetKey.value = key
  targetStatus.value = { ...targetStatus.value, [key]: '正在提交…' }
  targetErrors.value = { ...targetErrors.value, [key]: '' }
  try {
    const response = await submitMemoryTargetFeedback(props.traceId, target.sourceKind, target.sourceId, type)
    const result = response?.data?.data
    target.feedbackType = result?.feedbackType || type
    targetStatus.value = {
      ...targetStatus.value,
      [key]: result?.updated ? '这条记忆的反馈已更新。' : '已记录这条记忆的反馈。'
    }
  } catch (error) {
    targetStatus.value = { ...targetStatus.value, [key]: '' }
    targetErrors.value = {
      ...targetErrors.value,
      [key]: error?.response?.data?.message || '提交失败，请重试。'
    }
  } finally {
    submittingTargetKey.value = ''
  }
}

function targetKey(target) {
  return `${target?.sourceKind || ''}:${target?.sourceId || ''}`
}
</script>

<style scoped>
.memory-response-feedback {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid #d7c6a7;
  color: #5d4936;
  background: #faf5e9;
}

.memory-response-feedback > p { margin: 0; font-size: 12px; font-weight: 800; line-height: 1.5; }
.memory-response-feedback__options { display: flex; flex-wrap: wrap; gap: 6px; }
.memory-response-feedback__options button,
.memory-response-feedback__retry {
  min-height: 44px;
  padding: 0 10px;
  border: 1px solid #b99562;
  color: #5d4936;
  background: #fffdf5;
  font: inherit;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
}
.memory-response-feedback__options button:hover:not(:disabled),
.memory-response-feedback__options button:focus-visible,
.memory-response-feedback__retry:hover:not(:disabled),
.memory-response-feedback__retry:focus-visible { border-color: #4f8ca5; background: #fff4d6; outline: 2px solid #4f8ca5; outline-offset: 2px; }
.memory-response-feedback__options button.is-selected { border-color: #3d7866; color: #285d43; background: #eaf3e9; }
.memory-response-feedback__options button:disabled,
.memory-response-feedback__retry:disabled { opacity: .58; cursor: not-allowed; }
.memory-response-feedback__status { margin: 0; color: #80664a; font-size: 11px; line-height: 1.5; }
.memory-response-feedback__error { margin: 0; padding: 7px 8px; border: 1px solid #d99a8d; color: #8b3e34; background: #fff0ec; font-size: 11px; line-height: 1.5; }
.memory-target-feedback { display: grid; gap: 8px; margin-top: 2px; padding-top: 9px; border-top: 1px solid #e4d6be; }
.memory-target-feedback__heading { margin: 0; font-size: 12px; font-weight: 800; line-height: 1.5; }
.memory-target-feedback__item { display: grid; gap: 6px; padding: 8px; border: 1px solid #e3d7c4; background: #fffdf8; }
.memory-target-feedback__title-row { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px; line-height: 1.5; }
.memory-target-feedback__title-row strong { overflow-wrap: anywhere; font-size: 12px; }
.memory-target-feedback__kind { flex: 0 0 auto; color: #74624b; font-size: 10px; font-weight: 800; }
.memory-target-feedback__detail { margin: 0; color: #80664a; font-size: 11px; line-height: 1.5; overflow-wrap: anywhere; }
.memory-target-feedback__error { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; color: #8b3e34; font-size: 11px; }
.memory-target-feedback__error button { min-height: 40px; padding: 0 10px; border: 1px solid #b99562; color: #5d4936; background: #fffdf5; font: inherit; font-weight: 800; cursor: pointer; }
.memory-target-feedback__manage { justify-self: start; color: #285d43; font-size: 12px; font-weight: 800; text-underline-offset: 3px; }
.memory-target-feedback__manage:focus-visible,
.memory-target-feedback__error button:focus-visible { outline: 2px solid #4f8ca5; outline-offset: 2px; }
@media (max-width: 480px) { .memory-response-feedback__options button { flex: 1 1 calc(50% - 6px); } }
</style>
