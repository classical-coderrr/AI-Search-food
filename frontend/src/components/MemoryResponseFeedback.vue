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
import { onMounted, ref } from 'vue'
import { getMemoryFeedbackStatus, submitMemoryFeedback } from '../api/memory.js'

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
    }
  } catch (error) {
    checkError.value = error?.response?.status !== 404
  } finally {
    checking.value = false
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
@media (max-width: 480px) { .memory-response-feedback__options button { flex: 1 1 calc(50% - 6px); } }
</style>
