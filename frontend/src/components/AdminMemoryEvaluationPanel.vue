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

    <section class="online-labels" aria-labelledby="online-labels-title" :aria-busy="onlineLoading">
      <header class="memory-section-heading">
        <div>
          <p>线上观测 · 最近 30 天</p>
          <h4 id="online-labels-title">用户主动标注的记忆使用情况</h4>
        </div>
        <el-button
          size="small"
          :loading="onlineLoading"
          aria-label="刷新线上记忆标注指标"
          @click="loadOnlineMetrics"
        >
          刷新数据
        </el-button>
      </header>
      <el-alert
        v-if="onlineError"
        class="online-error"
        type="warning"
        :title="onlineError"
        :closable="false"
        show-icon
      />
      <p class="online-status" role="status" aria-live="polite">
        {{ onlineStatusMessage }}
      </p>
      <div class="online-metric-grid" v-loading="onlineLoading">
        <article class="online-metric-card">
          <span>记忆目标使用次数</span>
          <strong>{{ onlineLabels.usedTargetCount }}</strong>
          <small>近 30 天实际注入上下文的记忆条目与事件</small>
        </article>
        <article class="online-metric-card">
          <span>主动标注 / 覆盖率</span>
          <strong>{{ onlineLabels.labeledTargetCount }} 条 · {{ formatPercent(onlineLabels.labelCoverageRate) }}</strong>
          <small>用户对实际使用过的单条记忆提交的当前标注</small>
        </article>
        <article class="online-metric-card">
          <span>错误标注率</span>
          <strong>{{ onlineRate(onlineLabels.incorrectFeedbackRate) }}</strong>
          <small>错误 {{ onlineLabels.incorrectCount }} 条 / 已标注 {{ onlineLabels.labeledTargetCount }} 条</small>
        </article>
        <article class="online-metric-card">
          <span>不相关标注率</span>
          <strong>{{ onlineRate(onlineLabels.notRelevantFeedbackRate) }}</strong>
          <small>不相关 {{ onlineLabels.notRelevantCount }} 条 / 已标注 {{ onlineLabels.labeledTargetCount }} 条</small>
        </article>
        <article class="online-metric-card">
          <span>过期标注率</span>
          <strong>{{ onlineRate(onlineLabels.outdatedFeedbackRate) }}</strong>
          <small>过期 {{ onlineLabels.outdatedCount }} 条 / 已标注 {{ onlineLabels.labeledTargetCount }} 条</small>
        </article>
      </div>
      <p class="online-boundary">
        指标只反映用户主动评价的记忆，不是随机抽样；覆盖率和样本量会同时展示。达到 {{ onlineLabels.minimumSampleCount }} 条标注前不显示比例，达到后也不能据此推断所有用户或全部记忆使用的真实错误率。
      </p>
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
import { getAdminMemoryEvaluation, getAdminMemoryObservability, runAdminMemoryEvaluation } from '../api/adminMemoryEvaluation'

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
const onlineLoading = ref(false)
const errorMessage = ref('')
const onlineError = ref('')
const evaluation = ref(emptyEvaluation())
const onlineLabels = ref(emptyOnlineLabels())

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
const onlineStatusMessage = computed(() => {
  if (onlineLoading.value) return '正在加载最近 30 天的线上标注统计。'
  if (onlineError.value) return '线上统计暂不可用；离线评测结果不受影响。'
  if (onlineLabels.value.sampleStatus === 'NO_MEMORY_USAGE') return '最近 30 天尚无实际注入上下文的记忆条目或事件。'
  if (onlineLabels.value.sampleStatus === 'AWAITING_FEEDBACK') return `最近 30 天发生了 ${onlineLabels.value.usedTargetCount} 次记忆使用，尚无逐条用户标注。`
  if (!onlineLabels.value.sampleSufficient) return `已收集 ${onlineLabels.value.labeledTargetCount} 条主动标注，尚未达到展示比例所需的 ${onlineLabels.value.minimumSampleCount} 条。`
  return `已收集 ${onlineLabels.value.labeledTargetCount} 条主动标注，达到展示门槛；结果仍只代表自愿反馈样本。`
})

const metricCards = computed(() => {
  const metrics = evaluation.value.metrics || {}
  return [
    { key: 'extractionPrecision', label: '记忆提取精确率', value: metrics.extractionPrecision, note: '抽取结果中正确项的比例' },
    { key: 'extractionRecall', label: '记忆提取召回率', value: metrics.extractionRecall, note: '预期记忆被找回的比例' },
    { key: 'rerankRecallAt3', label: '检索 Recall@3', value: metrics.rerankRecallAt3, note: '相关记忆进入前三的比例' },
    { key: 'canonicalDedupAccuracy', label: '标签归一与去重', value: metrics.canonicalDedupAccuracy, note: '同义标签归入同一组的比例' },
    { key: 'conflictScenarioSelectionAccuracy', label: '冲突记忆召回选择', value: metrics.conflictScenarioSelectionAccuracy, note: '召回排序正确且保留长期/近期记忆' },
    { key: 'conflictAdjudicationAccuracy', label: '离线冲突裁决准确率', value: metrics.conflictAdjudicationAccuracy, note: '固定样例验证证据、场景与时间裁决，不代表线上用户准确率' },
    { key: 'staleMemoryTop3SelectionRate', label: '过期记忆 Top-3 命中', value: metrics.staleMemoryTop3SelectionRate, note: '越低越好；表示过期候选进入前三的比例' },
    { key: 'personalizationScenarioWinRate', label: '离线个性化场景胜率', value: metrics.personalizationScenarioWinRate, note: '固定场景比较，不等于真实用户胜率' }
  ]
})

onMounted(() => {
  loadEvaluation()
  loadOnlineMetrics()
})

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

async function loadOnlineMetrics() {
  onlineLoading.value = true
  onlineError.value = ''
  try {
    const response = await getAdminMemoryObservability('30d')
    onlineLabels.value = normalizeOnlineLabels(response?.data?.data?.onlineLabels)
  } catch (error) {
    onlineError.value = error?.response?.data?.message || error?.message || '线上记忆标注指标加载失败'
  } finally {
    onlineLoading.value = false
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

function emptyOnlineLabels() {
  return {
    usedTargetCount: 0,
    labeledTargetCount: 0,
    labelCoverageRate: 0,
    helpfulCount: 0,
    notRelevantCount: 0,
    incorrectCount: 0,
    outdatedCount: 0,
    notRelevantFeedbackRate: 0,
    incorrectFeedbackRate: 0,
    outdatedFeedbackRate: 0,
    minimumSampleCount: 20,
    sampleSufficient: false,
    sampleStatus: 'NO_MEMORY_USAGE'
  }
}

function normalizeOnlineLabels(value) {
  return { ...emptyOnlineLabels(), ...(value || {}) }
}

function onlineRate(value) {
  return onlineLabels.value.sampleSufficient ? formatPercent(value) : '样本不足'
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
    conflictResolutionAccuracy: '线上冲突准确率',
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
.memory-overview, .memory-feedback, .memory-metrics, .online-labels, .memory-cases, .unmeasured-panel { border: 1px solid var(--app-line); border-radius: 7px; background: var(--app-surface); }
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
.memory-metrics, .online-labels, .memory-cases, .unmeasured-panel { padding: 12px; }
.memory-section-heading { margin-bottom: 10px; }
.memory-section-heading h4 { color: var(--app-text); font-size: 14px; }
.memory-section-heading > span { color: var(--app-text-muted); font-size: 11px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 7px; }
.metric-card { display: grid; align-content: start; gap: 5px; min-width: 0; padding: 9px; border: 1px solid var(--app-line); border-radius: 5px; background: var(--app-surface-strong); }
.metric-card > span { color: var(--app-text-soft); font-size: 11px; }
.metric-card > strong { color: var(--app-text); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 19px; font-variant-numeric: tabular-nums; }
.metric-card > small { color: var(--app-text-muted); font-size: 10px; line-height: 1.4; }
.online-status { margin: 0 0 9px; padding: 8px 10px; border-radius: 5px; background: var(--app-surface-strong); color: var(--app-text-soft); font-size: 12px; line-height: 1.5; }
.online-metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 7px; }
.online-metric-card { display: grid; align-content: start; gap: 5px; min-width: 0; padding: 9px; border: 1px solid var(--app-line); border-radius: 5px; background: var(--app-surface-strong); }
.online-metric-card > span { color: var(--app-text-soft); font-size: 12px; }
.online-metric-card > strong { color: var(--app-text); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 16px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.online-metric-card > small, .online-boundary { color: var(--app-text-muted); font-size: 12px; line-height: 1.5; }
.online-boundary { margin: 9px 0 0; }
.online-labels :deep(.el-button) { min-height: 44px; }
.online-error { margin-bottom: 8px; }
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
@media (max-width: 900px) { .memory-overview { grid-template-columns: 1fr; } .metric-grid, .online-metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .memory-toolbar, .memory-feedback { align-items: flex-start; flex-direction: column; } .memory-toolbar :deep(.el-button) { width: 100%; } .memory-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); } .case-values, .online-metric-grid { grid-template-columns: 1fr; } }
</style>
