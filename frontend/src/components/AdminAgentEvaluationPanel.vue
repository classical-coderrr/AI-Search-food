<template>
  <section class="agent-evaluation-workspace" aria-labelledby="agent-evaluation-title">
    <header class="evaluation-toolbar">
      <div class="toolbar-copy">
        <p>Agent 评测</p>
        <h2 id="agent-evaluation-title">路由边界自动验收</h2>
        <span>固定脱敏样例验证工具暴露边界，不调用真实模型。</span>
      </div>
      <el-button
        type="primary"
        :loading="running"
        :disabled="loading"
        aria-label="立即执行 Agent 评测"
        title="立即执行 Agent 评测"
        @click="runEvaluation"
      >
        <RefreshCw :size="16" aria-hidden="true" />
        <span>{{ running ? '评测中…' : '立即执行评测' }}</span>
      </el-button>
    </header>

    <el-alert
      v-if="errorMessage"
      class="evaluation-error"
      type="error"
      :title="errorMessage"
      description="请检查管理员权限和后端服务状态，然后重新加载评测结果。"
      show-icon
      :closable="false"
    >
      <template #default>
        <el-button size="small" type="danger" plain :loading="loading" @click="loadEvaluation">
          重新加载
        </el-button>
      </template>
    </el-alert>

    <section
      class="evaluation-summary"
      aria-labelledby="evaluation-summary-title"
      :aria-busy="loading || running"
      v-loading="loading || running"
    >
      <div class="summary-status" :class="`is-${statusTone}`">
        <span class="status-icon" aria-hidden="true">
          <component :is="statusIcon" :size="22" />
        </span>
        <div>
          <span id="evaluation-summary-title" class="summary-label">最近一次评测</span>
          <strong>{{ statusLabel }}</strong>
          <small>{{ statusHint }}</small>
        </div>
      </div>

      <div class="summary-score" aria-label="评测通过率">
        <strong>{{ passRateText }}</strong>
        <span>通过率</span>
        <span class="score-track" aria-hidden="true"><span :style="{ width: `${passRate}%` }" /></span>
      </div>

      <dl class="summary-stats">
        <div><dt>总用例</dt><dd>{{ evaluation.totalCases }}</dd></div>
        <div><dt>已通过</dt><dd class="is-success">{{ evaluation.passedCases }}</dd></div>
        <div><dt>未通过</dt><dd class="is-danger">{{ evaluation.failedCases }}</dd></div>
        <div><dt>耗时</dt><dd>{{ formatDuration(evaluation.durationMs) }}</dd></div>
      </dl>
    </section>

    <p class="evaluation-feedback" role="status" aria-live="polite">
      <ClipboardCheck :size="16" aria-hidden="true" />
      <span>{{ feedback }}</span>
      <time v-if="evaluation.finishedAt" :datetime="evaluation.finishedAt">
        完成于 {{ formatDateTime(evaluation.finishedAt) }}
      </time>
    </p>

    <section class="cases-panel" aria-labelledby="evaluation-cases-title" v-loading="loading || running">
      <header class="panel-header">
        <div>
          <p>边界用例</p>
          <h3 id="evaluation-cases-title">工具暴露校验明细</h3>
        </div>
        <span>{{ evaluation.cases.length }} 个用例</span>
      </header>

      <div v-if="evaluation.cases.length" class="case-list">
        <article
          v-for="caseItem in evaluation.cases"
          :key="caseItem.caseKey"
          class="case-card"
          :class="{ 'is-failed': !caseItem.passed }"
        >
          <span class="case-icon" :class="caseItem.passed ? 'is-success' : 'is-danger'" aria-hidden="true">
            <CheckCircle2 v-if="caseItem.passed" :size="18" />
            <CircleAlert v-else :size="18" />
          </span>
          <div class="case-content">
            <div class="case-heading">
              <div>
                <span class="case-key">{{ caseItem.caseKey }}</span>
                <h4>{{ caseItem.description }}</h4>
              </div>
              <el-tag :type="caseItem.passed ? 'success' : 'danger'" size="small">
                {{ caseItem.passed ? '通过' : '失败' }}
              </el-tag>
            </div>
            <p class="case-input">输入：{{ caseItem.inputMessage }}</p>
            <div class="tool-groups">
              <div class="tool-group">
                <span>应暴露</span>
                <code v-for="tool in caseItem.expectedTools" :key="`expected-${tool}`">{{ tool }}</code>
                <em v-if="!caseItem.expectedTools.length">无</em>
              </div>
              <div class="tool-group">
                <span>实际暴露</span>
                <code v-for="tool in caseItem.actualTools" :key="`actual-${tool}`">{{ tool }}</code>
                <em v-if="!caseItem.actualTools.length">无</em>
              </div>
            </div>
            <p v-if="caseItem.failureReason" class="case-failure">
              <CircleAlert :size="14" aria-hidden="true" />
              <span>失败原因：{{ caseItem.failureReason }}</span>
            </p>
          </div>
        </article>
      </div>

      <div v-else class="empty-state">
        <ClipboardCheck :size="22" aria-hidden="true" />
        <strong>尚未生成评测结果</strong>
        <span>点击“立即执行评测”，验证当前 Agent 的工具路由边界。</span>
      </div>
    </section>
    <AdminMemoryEvaluationPanel />
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CheckCircle2, CircleAlert, ClipboardCheck, RefreshCw } from 'lucide-vue-next'
import AdminMemoryEvaluationPanel from './AdminMemoryEvaluationPanel.vue'
import { getAdminAgentEvaluation, runAdminAgentEvaluation } from '../api/adminAgentEvaluation'
import { normalizeAgentEvaluation } from '../utils/adminAgentEvaluation'

const loading = ref(false)
const running = ref(false)
const errorMessage = ref('')
const evaluation = ref(normalizeAgentEvaluation())

const statusLabel = computed(() => ({ PASSED: '全部通过', FAILED: '存在失败', NEVER: '尚未评测' }[evaluation.value.status]))
const statusTone = computed(() => ({ PASSED: 'success', FAILED: 'danger', NEVER: 'neutral' }[evaluation.value.status]))
const statusIcon = computed(() => ({ PASSED: CheckCircle2, FAILED: CircleAlert, NEVER: ClipboardCheck }[evaluation.value.status]))
const statusHint = computed(() => {
  if (evaluation.value.status === 'PASSED') return `已通过 ${evaluation.value.passedCases} / ${evaluation.value.totalCases} 个边界用例`
  if (evaluation.value.status === 'FAILED') return `有 ${evaluation.value.failedCases} 个用例需要处理`
  return '执行一次评测后，这里会显示最近结果'
})
const passRate = computed(() => evaluation.value.totalCases ? Math.round((evaluation.value.passedCases / evaluation.value.totalCases) * 100) : 0)
const passRateText = computed(() => evaluation.value.totalCases ? `${passRate.value}%` : '—')
const feedback = computed(() => {
  if (evaluation.value.status === 'NEVER') return '评测结果会持久化，方便在管理员后台复核最近一次边界验收。'
  if (evaluation.value.status === 'FAILED') return '发现工具边界偏差，请查看失败用例并修复后重新执行。'
  return '当前固定评测集全部通过，Agent 工具暴露边界符合预期。'
})

onMounted(loadEvaluation)

async function loadEvaluation() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await getAdminAgentEvaluation()
    evaluation.value = normalizeAgentEvaluation(response.data.data)
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || 'Agent 评测结果加载失败'
  } finally {
    loading.value = false
  }
}

async function runEvaluation() {
  running.value = true
  errorMessage.value = ''
  try {
    const response = await runAdminAgentEvaluation()
    evaluation.value = normalizeAgentEvaluation(response.data.data)
    ElMessage.success(evaluation.value.status === 'PASSED' ? 'Agent 评测已通过' : 'Agent 评测完成，请查看失败用例')
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || 'Agent 评测执行失败'
    ElMessage.error(errorMessage.value)
  } finally {
    running.value = false
  }
}

function formatDuration(value) {
  const duration = Number(value) || 0
  return duration < 1000 ? `${duration.toFixed(0)} ms` : `${(duration / 1000).toFixed(2)} s`
}

function formatDateTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date)
}
</script>

<style scoped>
.agent-evaluation-workspace { display: grid; align-content: start; gap: 12px; height: 100%; min-height: 0; padding-right: 2px; overflow: auto; }
.evaluation-toolbar, .panel-header, .evaluation-feedback { display: flex; align-items: center; }
.evaluation-toolbar, .panel-header { justify-content: space-between; gap: 14px; }
.toolbar-copy { display: grid; gap: 3px; }
.toolbar-copy p, .toolbar-copy h2, .toolbar-copy span, .panel-header p, .panel-header h3 { margin: 0; }
.toolbar-copy p, .panel-header p, .summary-label, .summary-stats dt, .case-key, .tool-group > span { color: var(--app-text-muted); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 11px; font-weight: 800; }
.toolbar-copy h2 { color: var(--app-text); font-size: clamp(20px, 2vw, 26px); line-height: 1.2; }
.toolbar-copy span { color: var(--app-text-muted); font-size: 13px; }
.evaluation-summary, .cases-panel, .evaluation-feedback { border: 1px solid var(--app-line); border-radius: 8px; background: var(--app-surface); box-shadow: inset 0 1px 0 var(--app-grid-line-strong); }
.evaluation-summary { display: grid; grid-template-columns: minmax(220px, 1.1fr) minmax(150px, 0.7fr) minmax(360px, 1.7fr); align-items: stretch; gap: 14px; padding: 14px; }
.summary-status { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: 11px; min-width: 0; }
.status-icon, .case-icon { display: inline-grid; place-items: center; border: 1px solid var(--app-line); border-radius: 8px; background: var(--app-surface-strong); }
.status-icon { width: 42px; height: 42px; }
.summary-status.is-success, .case-icon.is-success { color: #0f766e; }
.summary-status.is-danger, .case-icon.is-danger { color: #c2410c; }
.summary-status.is-neutral { color: var(--app-text-muted); }
.summary-status > div { display: grid; gap: 4px; min-width: 0; }
.summary-status strong { color: var(--app-text); font-size: 22px; line-height: 1; }
.summary-status small { overflow-wrap: anywhere; color: var(--app-text-muted); font-size: 12px; line-height: 1.4; }
.summary-score { display: grid; align-content: center; gap: 5px; min-width: 0; padding-left: 14px; border-left: 1px solid var(--app-line); }
.summary-score strong { color: var(--app-text); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 28px; font-variant-numeric: tabular-nums; line-height: 1; }
.summary-score > span:not(.score-track) { color: var(--app-text-muted); font-size: 12px; }
.score-track { display: block; width: 100%; height: 7px; overflow: hidden; border-radius: 999px; background: var(--app-surface-strong); }
.score-track span { display: block; height: 100%; border-radius: inherit; background: var(--app-accent); transition: width 180ms ease-out; }
.summary-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin: 0; }
.summary-stats div { display: grid; align-content: center; gap: 6px; min-width: 0; padding: 8px 10px; border: 1px solid var(--app-line); border-radius: 6px; background: var(--app-surface-strong); }
.summary-stats dt, .summary-stats dd { margin: 0; }
.summary-stats dd { color: var(--app-text); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 18px; font-variant-numeric: tabular-nums; }
.summary-stats dd.is-success { color: #0f766e; }
.summary-stats dd.is-danger { color: #c2410c; }
.evaluation-feedback { gap: 8px; min-height: 40px; padding: 9px 12px; color: var(--app-text-soft); font-size: 13px; }
.evaluation-feedback > svg { flex: 0 0 auto; color: var(--app-accent); }
.evaluation-feedback time { margin-left: auto; color: var(--app-text-muted); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 11px; white-space: nowrap; }
.cases-panel { min-height: 0; padding: 14px; overflow: hidden; }
.panel-header h3 { color: var(--app-text); font-size: 16px; }
.panel-header > span { color: var(--app-text-muted); font-size: 12px; }
.case-list { display: grid; gap: 8px; margin-top: 12px; }
.case-card { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 10px; padding: 10px; border: 1px solid var(--app-line); border-radius: 6px; background: var(--app-surface-strong); }
.case-card.is-failed { border-color: color-mix(in srgb, #c2410c 45%, var(--app-line)); }
.case-icon { width: 34px; height: 34px; }
.case-content { min-width: 0; }
.case-heading { display: flex; align-items: start; justify-content: space-between; gap: 10px; }
.case-heading > div { min-width: 0; }
.case-key { display: block; overflow-wrap: anywhere; font-size: 10px; }
.case-heading h4 { margin: 3px 0 0; color: var(--app-text); font-size: 14px; }
.case-input { margin: 7px 0 0; overflow-wrap: anywhere; color: var(--app-text-soft); font-size: 12px; line-height: 1.45; }
.tool-groups { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-top: 8px; }
.tool-group { display: flex; align-items: center; flex-wrap: wrap; gap: 5px; min-width: 0; }
.tool-group > span { flex: 0 0 auto; font-size: 10px; }
.tool-group code, .tool-group em { padding: 3px 6px; border: 1px solid var(--app-line); border-radius: 4px; color: var(--app-text-soft); background: var(--app-surface); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 10px; font-style: normal; overflow-wrap: anywhere; }
.tool-group em { color: var(--app-text-muted); }
.case-failure { display: flex; align-items: start; gap: 5px; margin: 8px 0 0; color: #c2410c; font-size: 12px; line-height: 1.45; }
.case-failure svg { flex: 0 0 auto; margin-top: 2px; }
.empty-state { display: grid; justify-items: center; gap: 7px; padding: 42px 12px 30px; color: var(--app-text-muted); text-align: center; }
.empty-state svg { color: var(--app-accent); }
.empty-state strong { color: var(--app-text); font-size: 14px; }
.empty-state span { font-size: 13px; }
@media (max-width: 1080px) { .evaluation-summary { grid-template-columns: minmax(220px, 1fr) minmax(150px, 0.7fr); } .summary-stats { grid-column: 1 / -1; } }
@media (max-width: 720px) { .evaluation-toolbar, .panel-header { align-items: flex-start; flex-direction: column; } .evaluation-toolbar :deep(.el-button) { width: 100%; } .evaluation-summary { grid-template-columns: 1fr; } .summary-score { padding: 12px 0 0; border-top: 1px solid var(--app-line); border-left: 0; } .summary-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); } .tool-groups { grid-template-columns: 1fr; } .evaluation-feedback { align-items: flex-start; flex-wrap: wrap; } .evaluation-feedback time { width: 100%; margin-left: 24px; } }
@media (prefers-reduced-motion: reduce) { .score-track span { transition: none; } }
</style>
