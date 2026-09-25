<template>
  <section class="memory-evaluation-panel" aria-labelledby="memory-evaluation-title">
    <header class="memory-toolbar">
      <div>
        <p>长期记忆</p>
        <h3 id="memory-evaluation-title">长期记忆评测</h3>
        <span>使用固定、脱敏的离线样例；不读取真实用户记忆，也不调用外部模型。</span>
      </div>
      <el-button
        type="primary"
        :loading="running"
        :disabled="loading"
        aria-label="运行长期记忆评测"
        @click="runEvaluation"
      >
        {{ running ? '评测中…' : '运行记忆评测' }}
      </el-button>
    </header>

    <el-alert
      v-if="errorMessage"
      class="memory-error"
      type="error"
      :title="errorMessage"
      :closable="false"
      show-icon
    >
      <template #default>
        <el-button size="small" type="danger" plain :loading="loading" @click="loadEvaluation">
          重新加载
        </el-button>
      </template>
    </el-alert>

    <section class="memory-overview" :aria-busy="loading || running" v-loading="loading || running">
      <div class="memory-status" :class="`is-${statusTone}`">
        <span class="status-mark" aria-hidden="true">{{ statusMark }}</span>
        <div>
          <span class="eyebrow">最近一次评测</span>
          <strong>{{ statusLabel }}</strong>
          <small>{{ statusHint }}</small>
        </div>
      </div>
      <dl class="memory-stats">
        <div><dt>评测版本</dt><dd>{{ evaluation.suiteVersion || '—' }}</dd></div>
        <div><dt>用例总数</dt><dd>{{ evaluation.totalCases }}</dd></div>
        <div><dt>通过 / 未通过</dt><dd>{{ evaluation.passedCases }} / {{ evaluation.failedCases }}</dd></div>
        <div><dt>耗时</dt><dd>{{ formatDuration(evaluation.durationMs) }}</dd></div>
      </dl>
    </section>

    <p class="memory-feedback" role="status" aria-live="polite">
      <span>{{ feedback }}</span>
      <time v-if="evaluation.finishedAt" :datetime="evaluation.finishedAt">
        完成于 {{ formatDateTime(evaluation.finishedAt) }}<template v-if="evaluation.id"> · 记录 #{{ evaluation.id }}</template>
      </time>
    </p>

    <section class="memory-metrics" aria-labelledby="memory-metrics-title">
      <header class="memory-section-heading">
        <div>
          <p>离线样例结果</p>
          <h4 id="memory-metrics-title">核心指标</h4>
        </div>
        <span>{{ metricCards.length }} 项</span>
      </header>
      <div class="metric-grid">
        <article v-for="metric in metricCards" :key="metric.key" class="metric-card">
          <span>{{ metric.label }}</span>
          <strong>{{ formatPercent(metric.value) }}</strong>
          <small>{{ metric.note }}</small>
        </article>
      </div>
    </section>

    <section class="memory-cases" aria-labelledby="memory-cases-title" v-loading="loading || running">
      <header class="memory-section-heading">
        <div>
          <p>逐项检查</p>
          <h4 id="memory-cases-title">评测用例</h4>
        </div>
        <span>{{ evaluation.cases.length }} 个</span>
      </header>
      <div v-if="evaluation.cases.length" class="memory-case-list">
        <article
          v-for="caseItem in evaluation.cases"
          :key="caseItem.caseKey"
          class="memory-case"
          :class="{ 'is-failed': !caseItem.passed }"
        >
          <div class="case-topline">
            <div>
              <span class="case-key">{{ caseItem.caseKey }}</span>
              <h5>{{ caseItem.description }}</h5>
            </div>
            <el-tag :type="caseItem.passed ? 'success' : 'danger'" size="small">
              {{ caseItem.passed ? '通过' : '未通过' }}
            </el-tag>
          </div>
          <span class="case-metric">{{ caseItem.metric }}</span>
          <dl class="case-values">
            <div><dt>预期</dt><dd>{{ caseItem.expected }}</dd></div>
            <div><dt>实际</dt><dd>{{ caseItem.actual }}</dd></div>
          </dl>
        </article>
      </div>
      <div v-else class="memory-empty">
        尚无评测记录，点击“运行记忆评测”生成第一份结果。
      </div>
    </section>

    <section v-if="evaluation.unmeasuredMetrics.length" class="unmeasured-panel" aria-labelledby="unmeasured-title">
      <header class="memory-section-heading">
        <div>
          <p>边界说明</p>
          <h4 id="unmeasured-title">当前未测量的线上指标</h4>
        </div>
        <span>{{ evaluation.unmeasuredMetrics.length }} 项</span>
      </header>
      <ul class="unmeasured-list">
        <li v-for="metric in evaluation.unmeasuredMetrics" :key="metric.metric">
          <div><strong>{{ unmeasuredLabel(metric.metric) }}</strong><el-tag type="info" size="small">未测量</el-tag></div>
          <p>{{ metric.reason }}</p>
        </li>
      </ul>
    </section>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAdminMemoryEvaluation, runAdminMemoryEvaluation } from '../api/adminMemoryEvaluation'

const emptyEvaluation = () => ({
  id: null,
  suiteVersion: 'memory-evaluation-v2',
  status: 'NEVER',
  startedAt: null,
  finishedAt: null,
  totalCases: 0,
  passedCases: 0,
  failedCases: 0,
  durationMs: 0,
  metrics: {},
  cases: [],
  unmeasuredMetrics: []
})

const loading = ref(false)
const running = ref(false)
const errorMessage = ref('')
const evaluation = ref(emptyEvaluation())

const statusLabel = computed(() => ({ PASSED: '全部通过', FAILED: '存在未通过项', NEVER: '尚未评测' }[evaluation.value.status] || '状态未知'))
const statusTone = computed(() => ({ PASSED: 'success', FAILED: 'danger', NEVER: 'neutral' }[evaluation.value.status] || 'neutral'))
const statusMark = computed(() => ({ PASSED: '✓', FAILED: '!', NEVER: '·' }[evaluation.value.status] || '?'))
const statusHint = computed(() => evaluation.value.status === 'NEVER'
  ? '运行后将显示固定样例的评测结果。'
  : `已通过 ${evaluation.value.passedCases} / ${evaluation.value.totalCases} 个用例`)
const feedback = computed(() => {
  if (evaluation.value.status === 'NEVER') return '评测只写入本次评测记录，不修改用户画像或记忆。'
  if (evaluation.value.status === 'FAILED') return '存在未通过的固定样例，请展开用例查看预期与实际结果。'
  return '固定离线样例全部通过；这不代表线上用户效果或真实胜率。'
})

const metricCards = computed(() => {
  const metrics = evaluation.value.metrics || {}
  return [
    { key: 'extractionPrecision', label: '记忆提取精确率', value: metrics.extractionPrecision, note: '抽取结果中正确项的比例' },
    { key: 'extractionRecall', label: '记忆提取召回率', value: metrics.extractionRecall, note: '预期记忆被找回的比例' },
    { key: 'rerankRecallAt3', label: '检索 Recall@3', value: metrics.rerankRecallAt3, note: '相关记忆进入前三的比例' },
    { key: 'canonicalDedupAccuracy', label: '标签归一与去重', value: metrics.canonicalDedupAccuracy, note: '同义标签归入同一组的比例' },
    { key: 'conflictScenarioSelectionAccuracy', label: '冲突场景选择', value: metrics.conflictScenarioSelectionAccuracy, note: '场景选择正确且保留长期/近期记忆' },
    { key: 'staleMemoryTop3SelectionRate', label: '过期记忆 Top-3 命中', value: metrics.staleMemoryTop3SelectionRate, note: '越低越好；表示过期候选进入前三的比例' },
    { key: 'personalizationScenarioWinRate', label: '离线个性化场景胜率', value: metrics.personalizationScenarioWinRate, note: '固定场景比较，不等于真实用户胜率' }
  ]
})

onMounted(loadEvaluation)

async function loadEvaluation() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await getAdminMemoryEvaluation()
    evaluation.value = normalizeEvaluation(response?.data?.data)
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || '记忆评测结果加载失败'
  } finally {
    loading.value = false
  }
}

async function runEvaluation() {
  running.value = true
  errorMessage.value = ''
  try {
    const response = await runAdminMemoryEvaluation()
    evaluation.value = normalizeEvaluation(response?.data?.data)
    ElMessage.success(evaluation.value.status === 'PASSED' ? '长期记忆评测通过' : '评测已完成，请查看未通过用例')
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || '记忆评测执行失败'
    ElMessage.error(errorMessage.value)
  } finally {
    running.value = false
  }
}

function normalizeEvaluation(value) {
  const defaults = emptyEvaluation()
  return {
    ...defaults,
    ...(value || {}),
    metrics: value?.metrics || {},
    cases: Array.isArray(value?.cases) ? value.cases : [],
    unmeasuredMetrics: Array.isArray(value?.unmeasuredMetrics) ? value.unmeasuredMetrics : []
  }
}

function formatPercent(value) {
  const numericValue = Number(value)
  return Number.isFinite(numericValue) ? `${(numericValue * 100).toFixed(1)}%` : '—'
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
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false
  }).format(date)
}

function unmeasuredLabel(metric) {
  return ({
    conflictResolutionAccuracy: '冲突消解准确率',
    wrongMemoryUsageRate: '错误记忆使用率',
    staleMemoryUsageRate: '过期记忆实际使用率',
    personalizationWinRate: '真实个性化胜率'
  })[metric] || metric
}
</script>

<style scoped>
.memory-evaluation-panel { display: grid; align-content: start; gap: 12px; padding: 14px; border: 1px solid var(--app-line); border-radius: 8px; background: var(--app-surface-strong); }
.memory-toolbar, .memory-section-heading, .case-topline { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.memory-toolbar > div, .memory-section-heading > div { display: grid; gap: 3px; }
.memory-toolbar p, .memory-toolbar h3, .memory-toolbar span, .memory-section-heading p, .memory-section-heading h4 { margin: 0; }
.memory-toolbar p, .memory-section-heading p, .eyebrow, .memory-stats dt, .case-key { color: var(--app-text-muted); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 11px; font-weight: 800; }
.memory-toolbar h3 { color: var(--app-text); font-size: 18px; line-height: 1.2; }
.memory-toolbar > div > span { color: var(--app-text-muted); font-size: 12px; line-height: 1.5; }
.memory-overview, .memory-feedback, .memory-metrics, .memory-cases, .unmeasured-panel { border: 1px solid var(--app-line); border-radius: 7px; background: var(--app-surface); }
.memory-overview { display: grid; grid-template-columns: minmax(200px, 0.7fr) minmax(0, 1.7fr); align-items: stretch; gap: 12px; padding: 12px; }
.memory-status { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: 10px; }
.status-mark { display: grid; width: 38px; height: 38px; place-items: center; border: 1px solid var(--app-line); border-radius: 8px; background: var(--app-surface-strong); font-size: 20px; font-weight: 800; }
.memory-status.is-success .status-mark { color: #0f766e; }
.memory-status.is-danger .status-mark { color: #c2410c; }
.memory-status.is-neutral .status-mark { color: var(--app-text-muted); }
.memory-status > div { display: grid; gap: 4px; }
.memory-status strong { color: var(--app-text); font-size: 18px; }
.memory-status small { color: var(--app-text-muted); font-size: 11px; }
.memory-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 7px; margin: 0; }
.memory-stats div { display: grid; align-content: center; gap: 5px; min-width: 0; padding: 8px; border: 1px solid var(--app-line); border-radius: 5px; background: var(--app-surface-strong); }
.memory-stats dt, .memory-stats dd { margin: 0; }
.memory-stats dd { overflow-wrap: anywhere; color: var(--app-text); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 14px; font-variant-numeric: tabular-nums; }
.memory-feedback { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 38px; margin: 0; padding: 8px 11px; color: var(--app-text-soft); font-size: 12px; }
.memory-feedback time { color: var(--app-text-muted); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 10px; white-space: nowrap; }
.memory-metrics, .memory-cases, .unmeasured-panel { padding: 12px; }
.memory-section-heading { margin-bottom: 10px; }
.memory-section-heading h4 { color: var(--app-text); font-size: 14px; }
.memory-section-heading > span { color: var(--app-text-muted); font-size: 11px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 7px; }
.metric-card { display: grid; align-content: start; gap: 5px; min-width: 0; padding: 9px; border: 1px solid var(--app-line); border-radius: 5px; background: var(--app-surface-strong); }
.metric-card > span { color: var(--app-text-soft); font-size: 11px; }
.metric-card > strong { color: var(--app-text); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 19px; font-variant-numeric: tabular-nums; }
.metric-card > small { color: var(--app-text-muted); font-size: 10px; line-height: 1.4; }
.memory-case-list, .unmeasured-list { display: grid; gap: 7px; }
.memory-case, .unmeasured-list li { padding: 9px; border: 1px solid var(--app-line); border-radius: 5px; background: var(--app-surface-strong); }
.memory-case.is-failed { border-color: color-mix(in srgb, #c2410c 45%, var(--app-line)); }
.case-topline { align-items: flex-start; }
.case-topline > div { min-width: 0; }
.case-key { font-size: 10px; overflow-wrap: anywhere; }
.case-topline h5 { margin: 3px 0 0; color: var(--app-text); font-size: 12px; }
.case-metric { display: inline-block; margin-top: 5px; color: var(--app-text-muted); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 9px; }
.case-values { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px; margin: 7px 0 0; }
.case-values div { min-width: 0; }
.case-values dt { color: var(--app-text-muted); font-size: 10px; }
.case-values dd { margin: 3px 0 0; overflow-wrap: anywhere; color: var(--app-text-soft); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 10px; white-space: pre-wrap; }
.memory-empty { padding: 22px 8px; color: var(--app-text-muted); font-size: 12px; text-align: center; }
.unmeasured-list { margin: 0; padding: 0; list-style: none; }
.unmeasured-list li > div { display: flex; align-items: center; gap: 8px; }
.unmeasured-list strong { color: var(--app-text); font-size: 11px; }
.unmeasured-list p { margin: 5px 0 0; color: var(--app-text-muted); font-size: 11px; line-height: 1.5; }
@media (max-width: 900px) { .memory-overview { grid-template-columns: 1fr; } .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .memory-toolbar, .memory-feedback { align-items: flex-start; flex-direction: column; } .memory-toolbar :deep(.el-button) { width: 100%; } .memory-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); } .case-values { grid-template-columns: 1fr; } }
</style>
