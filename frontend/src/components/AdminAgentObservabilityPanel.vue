<template>
  <section class="agent-observability-workspace" aria-labelledby="agent-observability-title">
    <header class="observability-toolbar">
      <div class="toolbar-copy">
        <p>Agent 可观测</p>
        <h2 id="agent-observability-title">看见每一次恢复、重放与幂等保护</h2>
        <span>实时指标来自当前实例，历史采样和告警状态持久化到数据库。</span>
      </div>
      <div class="toolbar-actions">
        <el-radio-group v-model="selectedRange" size="small" aria-label="历史指标时间范围" @change="loadObservability">
          <el-radio-button label="24h">近 24 小时</el-radio-button>
          <el-radio-button label="7d">近 7 天</el-radio-button>
        </el-radio-group>
        <el-button
          :loading="loading"
          aria-label="刷新 Agent 可观测数据"
          title="刷新 Agent 可观测数据"
          @click="loadObservability"
        >
          <RefreshCw :size="16" aria-hidden="true" />
          <span>刷新数据</span>
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="errorMessage"
      class="observability-error"
      type="error"
      :title="errorMessage"
      description="请检查管理员权限和后端服务状态，然后重新加载可观测数据。"
      show-icon
      :closable="false"
    >
      <template #default>
        <el-button size="small" type="danger" plain :loading="loading" @click="loadObservability">重新加载</el-button>
      </template>
    </el-alert>

    <section class="metric-grid" aria-label="Agent 核心指标" v-loading="loading">
      <article v-for="metric in metrics" :key="metric.key" class="metric-card">
        <span class="metric-icon" :class="metric.tone" aria-hidden="true">
          <component :is="metric.icon" :size="18" />
        </span>
        <div>
          <span class="metric-label">{{ metric.label }}</span>
          <strong>{{ formatNumber(metric.value) }}</strong>
          <small>{{ metric.hint }}</small>
        </div>
      </article>
    </section>

    <p class="insight-strip" role="status">
      <Activity :size="16" aria-hidden="true" />
      <span>{{ insight }}</span>
      <time v-if="observability.generatedAt" :datetime="observability.generatedAt">更新于 {{ formattedGeneratedAt }}</time>
    </p>

    <section class="alert-panel" aria-labelledby="agent-alerts-title">
      <header class="panel-header">
        <div><p>稳定性信号</p><h3 id="agent-alerts-title">Agent 告警</h3></div>
        <el-tag :type="activeAlerts.length ? 'danger' : 'success'">
          {{ activeAlerts.length ? `${activeAlerts.length} 条待处理` : '当前无告警' }}
        </el-tag>
      </header>
      <div v-if="observability.alerts.length" class="alert-list">
        <article v-for="alert in observability.alerts" :key="alert.id || alert.alertType" class="alert-item" :class="alert.status.toLowerCase()">
          <span class="alert-icon" :class="alert.status === 'OPEN' ? 'danger' : 'success'" aria-hidden="true">
            <CircleAlert v-if="alert.status === 'OPEN'" :size="17" />
            <CheckCircle2 v-else :size="17" />
          </span>
          <div class="alert-copy">
            <strong>{{ alert.title }}</strong>
            <span>{{ alert.message }}</span>
          </div>
          <div class="alert-meta">
            <el-tag size="small" :type="alert.status === 'OPEN' ? 'danger' : 'info'">
              {{ alert.status === 'OPEN' ? '待处理' : '已恢复' }}
            </el-tag>
            <time :datetime="alert.lastSeenAt || alert.firstSeenAt">{{ formatDateTime(alert.lastSeenAt || alert.firstSeenAt) }}</time>
          </div>
        </article>
      </div>
      <p v-else class="panel-empty"><CheckCircle2 :size="17" aria-hidden="true" /> 当前没有达到告警阈值的 Agent 信号。</p>
    </section>

    <div class="observability-grid">
      <section class="chart-panel" aria-labelledby="run-metrics-title" v-loading="loading">
        <header class="panel-header">
          <div><p>运行结果</p><h3 id="run-metrics-title">成功、失败与恢复</h3></div>
          <span>累计次数</span>
        </header>
        <div ref="runChartElement" class="chart-canvas" role="img" tabindex="0" :aria-label="runChartLabel" />
        <p class="sr-only">{{ runChartLabel }}</p>
      </section>

      <section class="chart-panel" aria-labelledby="event-metrics-title" v-loading="loading">
        <header class="panel-header">
          <div><p>事件可靠性</p><h3 id="event-metrics-title">持久化、重放与重复写入</h3></div>
          <span>累计次数</span>
        </header>
        <div ref="eventChartElement" class="chart-canvas" role="img" tabindex="0" :aria-label="eventChartLabel" />
        <p class="sr-only">{{ eventChartLabel }}</p>
      </section>

      <section class="rate-panel" aria-labelledby="rate-title">
        <header class="panel-header">
          <div><p>质量信号</p><h3 id="rate-title">关键比率</h3></div>
          <span>越高越稳定</span>
        </header>
        <dl class="rate-list">
          <div v-for="item in rates" :key="item.key" class="rate-row">
            <dt>{{ item.label }}</dt>
            <dd>
              <span class="rate-track" aria-hidden="true"><span :style="{ width: `${item.percent}%` }" /></span>
              <strong>{{ formatPercent(item.value) }}</strong>
            </dd>
          </div>
        </dl>
      </section>
    </div>

    <section class="history-panel" aria-labelledby="history-title" v-loading="loading">
      <header class="panel-header">
        <div><p>历史采样</p><h3 id="history-title">Agent 运行趋势</h3></div>
        <span>{{ observability.persistence.enabled ? `每 ${observability.persistence.snapshotInterval} 保存一次` : '历史持久化未启用' }}</span>
      </header>
      <div ref="historyChartElement" class="history-chart" role="img" tabindex="0" :aria-label="historyChartLabel" />
      <p v-if="!observability.history.length" class="chart-empty">暂时没有历史采样，等待下一次采样周期。</p>
      <p class="sr-only">{{ historyChartLabel }}</p>
    </section>

    <section class="config-panel" aria-labelledby="recovery-config-title">
      <header class="panel-header">
        <div><p>恢复策略</p><h3 id="recovery-config-title">当前 Agent 恢复配置</h3></div>
        <el-tag :type="observability.recovery.enabled ? 'success' : 'info'">
          {{ observability.recovery.enabled ? '恢复扫描已启用' : '恢复扫描未启用' }}
        </el-tag>
      </header>
      <dl class="config-list">
        <div><dt>状态存储</dt><dd>{{ observability.recovery.stateStore }}</dd></div>
        <div><dt>扫描间隔</dt><dd>{{ observability.recovery.scanDelay }}</dd></div>
        <div><dt>过期判定</dt><dd>{{ observability.recovery.staleAfter }}</dd></div>
        <div><dt>租约时长</dt><dd>{{ observability.recovery.leaseDuration }}</dd></div>
        <div><dt>平均运行时长</dt><dd>{{ formatDuration(observability.metrics.averageRunDurationMs) }}</dd></div>
      </dl>
    </section>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { BarChart, LineChart } from 'echarts/charts'
import { AriaComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { init, use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { Activity, CheckCircle2, CircleAlert, RefreshCw, RotateCcw } from 'lucide-vue-next'
import { getAdminAgentObservability } from '../api/adminAgentObservability'
import { normalizeAgentObservability } from '../utils/adminAgentObservability'

use([AriaComponent, BarChart, GridComponent, LegendComponent, LineChart, TooltipComponent, CanvasRenderer])

const runChartElement = ref(null)
const eventChartElement = ref(null)
const historyChartElement = ref(null)
const loading = ref(false)
const errorMessage = ref('')
const observability = ref(normalizeAgentObservability())
const selectedRange = ref('24h')

let runChart
let eventChart
let historyChart
let resizeObserver
let themeObserver

const metrics = computed(() => [
  { key: 'started', label: '启动运行', value: observability.value.metrics.runsStarted, hint: 'Agent 任务总数', icon: Activity, tone: 'blue' },
  { key: 'completed', label: '完成运行', value: observability.value.metrics.runsCompleted, hint: '正常完成任务', icon: CheckCircle2, tone: 'teal' },
  { key: 'failed', label: '失败运行', value: observability.value.metrics.runsFailed, hint: '需要关注的任务', icon: CircleAlert, tone: 'red' },
  { key: 'recovered', label: '恢复运行', value: observability.value.metrics.runsRecovered, hint: '重启后接管任务', icon: RotateCcw, tone: 'amber' }
])

const rates = computed(() => [
  { key: 'success', label: '完成率', value: observability.value.metrics.successRate, percent: observability.value.metrics.successRate * 100 },
  { key: 'recovery', label: '恢复占比', value: observability.value.metrics.recoveryRate, percent: observability.value.metrics.recoveryRate * 100 },
  { key: 'duplicate', label: '重复写入拦截率', value: observability.value.metrics.duplicateWriteRate, percent: Math.min(100, observability.value.metrics.duplicateWriteRate * 100) }
])

const insight = computed(() => {
  const { runsStarted, runsFailed, runsRecovered } = observability.value.metrics
  if (!runsStarted) return '当前实例还没有 Agent 运行样本，完成一次任务后这里会出现运行信号。'
  if (runsFailed) return `当前累计 ${formatNumber(runsFailed)} 次失败运行，建议结合异常日志继续定位。`
  if (runsRecovered) return `已观测到 ${formatNumber(runsRecovered)} 次恢复接管，恢复链路正在产生有效信号。`
  return '当前实例暂未出现失败运行，Agent 运行状态稳定。'
})

const formattedGeneratedAt = computed(() => formatDateTime(observability.value.generatedAt))
const activeAlerts = computed(() => observability.value.alerts.filter((alert) => alert.status === 'OPEN'))
const runChartLabel = computed(() => `Agent 运行结果：完成 ${observability.value.metrics.runsCompleted} 次，失败 ${observability.value.metrics.runsFailed} 次，恢复 ${observability.value.metrics.runsRecovered} 次。`)
const eventChartLabel = computed(() => `Agent 事件可靠性：持久化 ${observability.value.metrics.eventsPersisted} 次，重放 ${observability.value.metrics.eventsReplayed} 次，重复写入拦截 ${observability.value.metrics.duplicateWrites} 次。`)
const historyChartLabel = computed(() => {
  if (!observability.value.history.length) return 'Agent 历史运行趋势暂无采样数据。'
  const total = observability.value.history.reduce((sum, point) => sum + point.runsStarted, 0)
  return `Agent 历史运行趋势：当前时间范围共记录 ${total} 次运行采样。`
})

onMounted(async () => {
  await loadObservability()
  setupObservers()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  themeObserver?.disconnect()
  runChart?.dispose()
  eventChart?.dispose()
  historyChart?.dispose()
})

async function loadObservability() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await getAdminAgentObservability(selectedRange.value)
    observability.value = normalizeAgentObservability(response.data.data)
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || 'Agent 可观测数据加载失败'
    observability.value = normalizeAgentObservability()
  } finally {
    loading.value = false
    await nextTick()
    renderCharts()
  }
}

function renderCharts() {
  renderRunChart()
  renderEventChart()
  renderHistoryChart()
}

function renderRunChart() {
  if (!runChartElement.value) return
  runChart ||= init(runChartElement.value)
  const styles = getComputedStyle(document.documentElement)
  runChart.setOption(barOption(
    ['完成', '失败', '恢复'],
    [observability.value.metrics.runsCompleted, observability.value.metrics.runsFailed, observability.value.metrics.runsRecovered],
    ['#0f766e', '#c2410c', '#b7791f'],
    styles.getPropertyValue('--app-text-muted').trim() || '#64748b',
    styles.getPropertyValue('--app-line').trim() || '#d6dee8'
  ), true)
}

function renderEventChart() {
  if (!eventChartElement.value) return
  eventChart ||= init(eventChartElement.value)
  const styles = getComputedStyle(document.documentElement)
  eventChart.setOption(barOption(
    ['持久化', '重放', '重复写入拦截'],
    [observability.value.metrics.eventsPersisted, observability.value.metrics.eventsReplayed, observability.value.metrics.duplicateWrites],
    ['#2563eb', '#7c3aed', '#b7791f'],
    styles.getPropertyValue('--app-text-muted').trim() || '#64748b',
    styles.getPropertyValue('--app-line').trim() || '#d6dee8'
  ), true)
}

function renderHistoryChart() {
  if (!historyChartElement.value) return
  historyChart ||= init(historyChartElement.value)
  const styles = getComputedStyle(document.documentElement)
  const textColor = styles.getPropertyValue('--app-text-muted').trim() || '#64748b'
  const lineColor = styles.getPropertyValue('--app-line').trim() || '#d6dee8'
  const history = observability.value.history
  historyChart.setOption({
    animationDuration: chartAnimationDuration(),
    aria: { enabled: true },
    legend: { top: 0, right: 0, textStyle: { color: textColor, fontSize: 11 } },
    grid: { top: 34, right: 18, bottom: 30, left: 42 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: history.map((point) => formatChartLabel(point.capturedAt)), axisLabel: { color: textColor, fontSize: 11, hideOverlap: true }, axisLine: { lineStyle: { color: lineColor } }, axisTick: { show: false } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: textColor, fontSize: 11 }, splitLine: { lineStyle: { color: lineColor } } },
    series: [
      lineSeries('启动', history.map((point) => point.runsStarted), '#2563eb'),
      lineSeries('完成', history.map((point) => point.runsCompleted), '#0f766e'),
      lineSeries('失败', history.map((point) => point.runsFailed), '#c2410c'),
      lineSeries('恢复', history.map((point) => point.runsRecovered), '#b7791f')
    ]
  }, true)
}

function lineSeries(name, data, color) {
  return { name, type: 'line', data, smooth: true, symbol: 'circle', symbolSize: 5, itemStyle: { color }, lineStyle: { color, width: 2 }, emphasis: { focus: 'series' } }
}

function barOption(labels, values, colors, textColor, lineColor) {
  return {
    animationDuration: chartAnimationDuration(),
    aria: { enabled: true },
    grid: { top: 16, right: 18, bottom: 30, left: 42 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'category', data: labels, axisLabel: { color: textColor, fontSize: 11, interval: 0 }, axisLine: { lineStyle: { color: lineColor } }, axisTick: { show: false } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: textColor, fontSize: 11 }, splitLine: { lineStyle: { color: lineColor } } },
    series: [{ type: 'bar', data: values.map((value, index) => ({ value, itemStyle: { color: colors[index] } })), barMaxWidth: 32, label: { show: true, position: 'top', color: textColor, fontWeight: 700 } }]
  }
}

function chartAnimationDuration() {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 0 : 280
}

function setupObservers() {
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => { runChart?.resize(); eventChart?.resize(); historyChart?.resize() })
    resizeObserver.observe(runChartElement.value)
    resizeObserver.observe(eventChartElement.value)
    resizeObserver.observe(historyChartElement.value)
  }
  if (typeof MutationObserver !== 'undefined') {
    themeObserver = new MutationObserver(renderCharts)
    themeObserver.observe(document.body, { attributes: true, attributeFilter: ['data-theme'] })
  }
}

function formatNumber(value) { return new Intl.NumberFormat('zh-CN').format(Number(value) || 0) }
function formatPercent(value) { return `${((Number(value) || 0) * 100).toFixed(1)}%` }
function formatDuration(value) {
  const number = Number(value) || 0
  return number < 1000 ? `${number.toFixed(0)} ms` : `${(number / 1000).toFixed(2)} s`
}
function formatDateTime(value) {
  if (!value) return ''
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
}
function formatChartLabel(value) {
  if (!value) return '--'
  const date = new Date(value)
  return selectedRange.value === '7d'
    ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(date)
    : new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}
</script>

<style scoped>
.agent-observability-workspace { display: grid; align-content: start; gap: 12px; height: 100%; min-height: 0; padding-right: 2px; overflow: auto; }
.observability-toolbar, .panel-header, .insight-strip, .config-list div, .rate-row dd { display: flex; align-items: center; }
.observability-toolbar, .panel-header { justify-content: space-between; gap: 14px; }
.toolbar-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; flex-wrap: wrap; }
.toolbar-copy { display: grid; gap: 3px; }
.toolbar-copy p, .toolbar-copy h2, .toolbar-copy span, .panel-header p, .panel-header h3 { margin: 0; }
.toolbar-copy p, .panel-header p, .metric-label, .metric-card small, .config-list dt, .rate-row dt { color: var(--app-text-muted); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 11px; font-weight: 800; }
.toolbar-copy h2 { color: var(--app-text); font-size: clamp(20px, 2vw, 26px); line-height: 1.2; }
.toolbar-copy span { color: var(--app-text-muted); font-size: 13px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.metric-card, .chart-panel, .rate-panel, .config-panel, .insight-strip { border: 1px solid var(--app-line); border-radius: 8px; background: var(--app-surface); box-shadow: inset 0 1px 0 var(--app-grid-line-strong); }
.metric-card { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 11px; min-height: 112px; padding: 14px; }
.metric-card > div { display: grid; align-content: space-between; gap: 4px; min-width: 0; }
.metric-icon { display: inline-grid; width: 38px; height: 38px; place-items: center; border: 1px solid var(--app-line); border-radius: 8px; background: var(--app-surface-strong); }
.metric-icon.blue { color: #2563eb; } .metric-icon.teal { color: #0f766e; } .metric-icon.red { color: #c2410c; } .metric-icon.amber { color: #b7791f; }
.metric-card strong { overflow: hidden; color: var(--app-text); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: clamp(22px, 2.2vw, 30px); font-variant-numeric: tabular-nums; line-height: 1; text-overflow: ellipsis; white-space: nowrap; }
.metric-card small { font-size: 10px; }
.insight-strip { gap: 8px; min-height: 40px; padding: 9px 12px; color: var(--app-text-soft); font-size: 13px; }
.insight-strip svg { flex: 0 0 auto; color: var(--app-accent); } .insight-strip time { margin-left: auto; color: var(--app-text-muted); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 11px; white-space: nowrap; }
.observability-grid { display: grid; grid-template-columns: minmax(300px, 1fr) minmax(300px, 1fr); gap: 12px; }
.chart-panel, .rate-panel, .config-panel { min-height: 0; padding: 14px; overflow: hidden; }
.rate-panel { grid-column: 1 / -1; } .panel-header { min-height: 34px; } .panel-header h3 { color: var(--app-text); font-size: 16px; } .panel-header > span { color: var(--app-text-muted); font-size: 12px; }
.chart-canvas { width: 100%; height: 228px; }
.alert-panel, .history-panel { min-height: 0; padding: 14px; border: 1px solid var(--app-line); border-radius: 8px; background: var(--app-surface); box-shadow: inset 0 1px 0 var(--app-grid-line-strong); }
.alert-list { display: grid; gap: 8px; margin-top: 12px; }
.alert-item { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 10px; border: 1px solid var(--app-line); border-radius: 6px; background: var(--app-surface-strong); }
.alert-item.open { border-color: color-mix(in srgb, #c2410c 45%, var(--app-line)); }
.alert-icon { display: inline-grid; width: 32px; height: 32px; place-items: center; border-radius: 7px; }
.alert-icon.danger { color: #c2410c; background: color-mix(in srgb, #c2410c 12%, var(--app-surface)); }
.alert-icon.success { color: #0f766e; background: color-mix(in srgb, #0f766e 12%, var(--app-surface)); }
.alert-copy { display: grid; gap: 3px; min-width: 0; }
.alert-copy strong { color: var(--app-text); font-size: 13px; }
.alert-copy span { overflow-wrap: anywhere; color: var(--app-text-muted); font-size: 12px; line-height: 1.5; }
.alert-meta { display: grid; justify-items: end; gap: 4px; color: var(--app-text-muted); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 10px; white-space: nowrap; }
.panel-empty, .chart-empty { display: flex; align-items: center; gap: 7px; margin: 16px 0 2px; color: var(--app-text-muted); font-size: 13px; }
.panel-empty svg { color: #0f766e; }
.history-chart { width: 100%; height: 280px; margin-top: 8px; }
.rate-list, .config-list { display: grid; gap: 12px; margin: 16px 0 0; }
.rate-row { display: grid; grid-template-columns: 130px minmax(0, 1fr); align-items: center; gap: 14px; } .rate-row dt, .rate-row dd, .config-list dt, .config-list dd { margin: 0; } .rate-row dd { gap: 12px; }
.rate-track { flex: 1; height: 8px; overflow: hidden; border-radius: 999px; background: var(--app-surface-strong); } .rate-track span { display: block; height: 100%; border-radius: inherit; background: var(--app-accent); transition: width 180ms ease-out; }
.rate-row strong, .config-list dd { color: var(--app-text); font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace; font-size: 13px; font-variant-numeric: tabular-nums; } .rate-row strong { min-width: 52px; text-align: right; }
.config-list { grid-template-columns: repeat(5, minmax(0, 1fr)); } .config-list div { justify-content: space-between; gap: 10px; min-height: 40px; padding: 8px 10px; border: 1px solid var(--app-line); border-radius: 6px; background: var(--app-surface-strong); }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 980px) { .metric-grid, .config-list { grid-template-columns: repeat(2, minmax(0, 1fr)); } .rate-panel { grid-column: auto; } }
@media (max-width: 720px) { .observability-toolbar { align-items: flex-start; flex-direction: column; } .toolbar-actions { width: 100%; justify-content: flex-start; } .metric-grid, .observability-grid, .config-list { grid-template-columns: 1fr; } .rate-panel { grid-column: auto; } .rate-row { grid-template-columns: 1fr; gap: 6px; } .alert-item { grid-template-columns: auto minmax(0, 1fr); } .alert-meta { grid-column: 2; justify-items: start; grid-auto-flow: column; align-items: center; } .insight-strip { align-items: flex-start; flex-wrap: wrap; } .insight-strip time { width: 100%; margin-left: 24px; } }
@media (prefers-reduced-motion: reduce) { .rate-track span { transition: none; } }
</style>
