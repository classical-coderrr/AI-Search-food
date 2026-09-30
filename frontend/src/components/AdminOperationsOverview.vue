<template>
  <section class="operations-workspace" :class="{ 'has-error': errorMessage }" aria-labelledby="operations-title">
    <header class="operations-toolbar">
      <div class="toolbar-copy">
        <p>运营概览</p>
        <h2 id="operations-title">看清菜谱从生成到保存的路径</h2>
        <span>统计范围覆盖 {{ periodLabel }}，仅展示汇总数据。</span>
      </div>
      <div class="toolbar-actions">
        <el-radio-group v-model="period" size="small" aria-label="运营统计周期" @change="loadOverview">
          <el-radio-button v-for="option in DASHBOARD_PERIOD_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
        <el-button
          :loading="loading"
          circle
          aria-label="刷新运营数据"
          title="刷新运营数据"
          @click="loadOverview"
        >
          <RefreshCw :size="16" aria-hidden="true" />
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="errorMessage"
      class="operations-error"
      type="error"
      :title="errorMessage"
      description="请检查服务是否启动，随后重试加载运营数据。"
      show-icon
      :closable="false"
    >
      <template #default>
        <el-button size="small" type="danger" plain :loading="loading" @click="loadOverview">重新加载</el-button>
      </template>
    </el-alert>

    <section class="metric-grid" aria-label="核心运营指标" v-loading="loading">
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
      <time v-if="overview.generatedAt" :datetime="overview.generatedAt">更新于 {{ formattedGeneratedAt }}</time>
    </p>

    <div class="operations-grid">
      <section class="chart-panel trend-panel" aria-labelledby="trend-title" v-loading="loading">
        <header class="panel-header">
          <div>
            <p>行为趋势</p>
            <h3 id="trend-title">每日生成与保存</h3>
          </div>
          <span>{{ periodLabel }}</span>
        </header>
        <div
          ref="trendChartElement"
          class="chart-canvas"
          role="img"
          tabindex="0"
          :aria-label="trendChartLabel"
        />
        <el-empty v-if="!loading && !hasTrendData" class="chart-empty" description="当前周期暂无生成或保存记录" />
        <p class="sr-only">{{ trendChartLabel }}</p>
      </section>

      <section class="chart-panel source-panel" aria-labelledby="source-title" v-loading="loading">
        <header class="panel-header">
          <div>
            <p>输入渠道</p>
            <h3 id="source-title">菜谱生成来源</h3>
          </div>
          <span>次数</span>
        </header>
        <div
          ref="sourceChartElement"
          class="chart-canvas source-chart"
          role="img"
          tabindex="0"
          :aria-label="sourceChartLabel"
        />
        <el-empty v-if="!loading && !hasSourceData" class="chart-empty" description="当前周期暂无来源统计" />
        <dl v-if="hasSourceData" class="source-summary" aria-label="输入来源数据明细">
          <div v-for="item in overview.inputSources" :key="item.inputType">
            <dt>{{ item.label }}</dt>
            <dd>{{ formatNumber(item.count) }} 次</dd>
          </div>
        </dl>
        <p class="sr-only">{{ sourceChartLabel }}</p>
      </section>

      <section class="ingredients-panel" aria-labelledby="ingredients-title" v-loading="loading">
        <header class="panel-header">
          <div>
            <p>食材热度</p>
            <h3 id="ingredients-title">Top 5 热门食材</h3>
          </div>
          <RouterLink class="panel-link" :to="{ name: 'admin', query: { panel: 'hot-ingredients' } }">
            <span>完整分析</span>
            <ArrowUpRight :size="15" aria-hidden="true" />
          </RouterLink>
        </header>

        <ol v-if="overview.hotIngredients.length" class="ingredient-list">
          <li v-for="(item, index) in overview.hotIngredients" :key="item.name">
            <span class="ingredient-rank">{{ String(index + 1).padStart(2, '0') }}</span>
            <strong>{{ item.name }}</strong>
            <span>{{ formatNumber(item.count) }} 次</span>
          </li>
        </ol>
        <el-empty v-else-if="!loading" description="当前周期暂无食材热度数据" :image-size="56" />
      </section>
    </div>

    <section class="hot-details-panel" aria-labelledby="hot-details-title" v-loading="loading">
      <header class="panel-header">
        <div>
          <p>全量排行</p>
          <h3 id="hot-details-title">热门食材详细数据</h3>
        </div>
        <span>{{ hotStats.items.length }} 项</span>
      </header>

      <el-table
        v-if="hotStats.items.length"
        :data="hotStats.items"
        size="small"
        row-key="name"
        aria-label="热门食材完整排行"
      >
        <el-table-column prop="rank" label="排名" width="72" align="center" />
        <el-table-column prop="name" label="食材" min-width="150" show-overflow-tooltip />
        <el-table-column prop="searchCount" label="搜索次数" width="110" align="right" />
        <el-table-column label="占比" width="100" align="right">
          <template #default="scope">{{ formatShare(scope.row.share) }}</template>
        </el-table-column>
        <el-table-column label="最近搜索" min-width="160">
          <template #default="scope">{{ formatDateTime(scope.row.latestSearchAt) || '暂无' }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="!loading" description="当前周期暂无热门食材明细" :image-size="56" />
    </section>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { BarChart, LineChart } from 'echarts/charts'
import { AriaComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { init, use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { Activity, ArrowUpRight, ChefHat, CircleUserRound, FileText, RefreshCw, Star } from 'lucide-vue-next'
import { getAdminDashboardOverview } from '../api/adminDashboard'
import { getHotIngredients } from '../api/stats'
import {
  DASHBOARD_PERIOD_OPTIONS,
  dashboardInsight,
  normalizeDashboardOverview,
  normalizeHotIngredientStats
} from '../utils/adminDashboard'

use([AriaComponent, BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const trendChartElement = ref(null)
const sourceChartElement = ref(null)
const loading = ref(false)
const errorMessage = ref('')
const period = ref('7d')
const overview = ref(normalizeDashboardOverview())
const hotStats = ref(normalizeHotIngredientStats())

let trendChart
let sourceChart
let resizeObserver
let themeObserver

const periodLabel = computed(
  () => DASHBOARD_PERIOD_OPTIONS.find((option) => option.value === period.value)?.label || '近 7 天'
)
const metrics = computed(() => [
  {
    key: 'new-users',
    label: '新增用户',
    value: overview.value.metrics.newUserCount,
    hint: `${periodLabel.value}完成注册`,
    icon: CircleUserRound,
    tone: 'blue'
  },
  {
    key: 'generations',
    label: '菜谱生成',
    value: overview.value.metrics.generationCount,
    hint: '仅统计成功生成',
    icon: FileText,
    tone: 'amber'
  },
  {
    key: 'saved-recipes',
    label: '保存菜谱',
    value: overview.value.metrics.savedRecipeCount,
    hint: '用户主动保存',
    icon: ChefHat,
    tone: 'teal'
  },
  {
    key: 'reviews',
    label: '成品评价',
    value: overview.value.metrics.reviewCount,
    hint: '已生成评价报告',
    icon: Star,
    tone: 'violet'
  }
])
const insight = computed(() => dashboardInsight(overview.value))
const hasTrendData = computed(() => overview.value.dailyTrend.some(
  (item) => item.generationCount || item.savedRecipeCount
))
const hasSourceData = computed(() => overview.value.inputSources.some((item) => item.count))
const formattedGeneratedAt = computed(() => formatDateTime(overview.value.generatedAt))
const trendChartLabel = computed(() => {
  if (!hasTrendData.value) {
    return `${periodLabel.value}每日生成与保存趋势图，当前暂无数据。`
  }
  return `${periodLabel.value}每日生成与保存趋势图。${insight.value}`
})
const sourceChartLabel = computed(() => {
  if (!hasSourceData.value) {
    return `${periodLabel.value}菜谱生成来源图，当前暂无数据。`
  }
  return `${periodLabel.value}菜谱生成来源图：${overview.value.inputSources
    .map((item) => `${item.label}${item.count}次`)
    .join('，')}。`
})

onMounted(async () => {
  await loadOverview()
  setupObservers()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  themeObserver?.disconnect()
  trendChart?.dispose()
  sourceChart?.dispose()
})

async function loadOverview() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await getAdminDashboardOverview(period.value)
    overview.value = normalizeDashboardOverview(response.data.data)
    period.value = overview.value.period
    try {
      const hotResponse = await getHotIngredients(period.value, 20)
      hotStats.value = normalizeHotIngredientStats(hotResponse.data.data)
    } catch {
      hotStats.value = normalizeHotIngredientStats()
    }
  } catch (error) {
    errorMessage.value = error?.response?.data?.message || error?.message || '运营数据加载失败'
    overview.value = normalizeDashboardOverview({ period: period.value })
    hotStats.value = normalizeHotIngredientStats()
  } finally {
    loading.value = false
    await nextTick()
    renderCharts()
  }
}

function renderCharts() {
  renderTrendChart()
  renderSourceChart()
}

function renderTrendChart() {
  if (!trendChartElement.value) {
    return
  }
  trendChart ||= init(trendChartElement.value)
  const styles = getComputedStyle(document.documentElement)
  const textColor = styles.getPropertyValue('--app-text-muted').trim() || '#64748b'
  const lineColor = styles.getPropertyValue('--app-line').trim() || '#d6dee8'
  const primaryColor = styles.getPropertyValue('--app-accent').trim() || '#d97706'
  const secondaryColor = styles.getPropertyValue('--app-text-muted').trim() || '#3b82f6'
  const trend = overview.value.dailyTrend

  trendChart.setOption({
    animationDuration: 280,
    aria: { enabled: true },
    color: [primaryColor, secondaryColor],
    legend: {
      top: 4,
      right: 8,
      textStyle: { color: textColor, fontSize: 12 }
    },
    grid: { top: 48, right: 18, bottom: 28, left: 42 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: trend.map((item) => formatShortDate(item.date)),
      axisLabel: { color: textColor, fontSize: 11 },
      axisLine: { lineStyle: { color: lineColor } },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: textColor, fontSize: 11 },
      splitLine: { lineStyle: { color: lineColor } }
    },
    series: [
      {
        name: '菜谱生成',
        type: 'line',
        smooth: true,
        symbolSize: 6,
        data: trend.map((item) => item.generationCount),
        lineStyle: { width: 3 },
        areaStyle: { opacity: 0.08 }
      },
      {
        name: '保存菜谱',
        type: 'line',
        smooth: true,
        symbol: 'rect',
        symbolSize: 6,
        data: trend.map((item) => item.savedRecipeCount),
        lineStyle: { type: 'dashed', width: 2 }
      }
    ]
  }, true)
}

function renderSourceChart() {
  if (!sourceChartElement.value) {
    return
  }
  sourceChart ||= init(sourceChartElement.value)
  const styles = getComputedStyle(document.documentElement)
  const textColor = styles.getPropertyValue('--app-text-muted').trim() || '#64748b'
  const lineColor = styles.getPropertyValue('--app-line').trim() || '#d6dee8'
  const primaryColor = styles.getPropertyValue('--app-accent').trim() || '#d97706'
  const sources = overview.value.inputSources

  sourceChart.setOption({
    animationDuration: 280,
    aria: { enabled: true },
    grid: { top: 18, right: 18, bottom: 26, left: 62 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: textColor, fontSize: 11 },
      splitLine: { lineStyle: { color: lineColor } }
    },
    yAxis: {
      type: 'category',
      data: sources.map((item) => item.label).reverse(),
      axisLabel: { color: textColor, fontSize: 12 },
      axisLine: { lineStyle: { color: lineColor } },
      axisTick: { show: false }
    },
    series: [{
      name: '生成次数',
      type: 'bar',
      data: sources.map((item) => item.count).reverse(),
      barMaxWidth: 22,
      itemStyle: { color: primaryColor, borderRadius: [0, 4, 4, 0] },
      label: { show: true, position: 'right', color: textColor, fontWeight: 700 }
    }]
  }, true)
}

function setupObservers() {
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => {
      trendChart?.resize()
      sourceChart?.resize()
    })
    resizeObserver.observe(trendChartElement.value)
    resizeObserver.observe(sourceChartElement.value)
  }
  if (typeof MutationObserver !== 'undefined') {
    themeObserver = new MutationObserver(renderCharts)
    themeObserver.observe(document.body, { attributes: true, attributeFilter: ['data-theme'] })
  }
}

function formatNumber(value) {
  return new Intl.NumberFormat('zh-CN').format(Number(value) || 0)
}

function formatShare(value) {
  return `${((Number(value) || 0) * 100).toFixed(1)}%`
}

function formatShortDate(value) {
  return typeof value === 'string' && value.length >= 10 ? value.slice(5, 10).replace('-', '/') : value
}

function formatDateTime(value) {
  if (!value) {
    return ''
  }
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(new Date(value))
}
</script>

<style scoped>
.operations-workspace {
  display: grid;
  grid-auto-rows: max-content;
  align-content: start;
  gap: 12px;
  height: 100%;
  min-height: 0;
  padding-right: 2px;
  overflow: auto;
}

.operations-toolbar,
.toolbar-actions,
.panel-header,
.insight-strip,
.source-summary div,
.ingredient-list li {
  display: flex;
  align-items: center;
}

.operations-toolbar,
.panel-header {
  justify-content: space-between;
  gap: 14px;
}

.toolbar-copy {
  display: grid;
  gap: 3px;
}

.toolbar-copy p,
.toolbar-copy h2,
.toolbar-copy span,
.panel-header p,
.panel-header h3 {
  margin: 0;
}

.toolbar-copy p,
.panel-header p,
.metric-label,
.metric-card small,
.source-summary dt,
.ingredient-rank {
  color: var(--app-text-muted);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 11px;
  font-weight: 800;
}

.toolbar-copy h2 {
  color: var(--app-text);
  font-size: clamp(20px, 2vw, 26px);
  line-height: 1.2;
}

.toolbar-copy span {
  color: var(--app-text-muted);
  font-size: 13px;
}

.toolbar-actions {
  flex: 0 0 auto;
  gap: 8px;
}

.operations-error {
  margin: 0;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.metric-card,
.chart-panel,
.ingredients-panel,
.hot-details-panel,
.insight-strip {
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
  box-shadow: inset 0 1px 0 var(--app-grid-line-strong);
}

.metric-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 11px;
  min-height: 112px;
  padding: 14px;
}

.metric-card > div {
  display: grid;
  align-content: space-between;
  gap: 4px;
  min-width: 0;
}

.metric-icon {
  display: inline-grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  color: var(--app-text);
  background: var(--app-surface-strong);
}

.metric-icon.blue { color: #2563eb; }
.metric-icon.amber { color: #b7791f; }
.metric-icon.teal { color: #0f766e; }
.metric-icon.violet { color: #7c3aed; }

.metric-card strong {
  overflow: hidden;
  color: var(--app-text);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: clamp(22px, 2.2vw, 30px);
  font-variant-numeric: tabular-nums;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-card small {
  font-size: 10px;
}

.insight-strip {
  gap: 8px;
  min-height: 40px;
  padding: 9px 12px;
  color: var(--app-text-soft);
  font-size: 13px;
}

.insight-strip svg {
  flex: 0 0 auto;
  color: var(--app-accent);
}

.insight-strip time {
  margin-left: auto;
  color: var(--app-text-muted);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 11px;
  white-space: nowrap;
}

.operations-grid {
  display: grid;
  grid-template-columns: minmax(360px, 1.35fr) minmax(290px, 0.8fr);
  gap: 12px;
  min-height: 0;
}

.chart-panel,
.ingredients-panel {
  position: relative;
  min-height: 292px;
  padding: 14px;
  overflow: hidden;
}

.hot-details-panel {
  padding: 14px;
  overflow: hidden;
}

.hot-details-panel :deep(.el-table) {
  width: 100%;
  margin-top: 8px;
}

.trend-panel {
  grid-row: span 2;
  min-height: 0;
}

.panel-header {
  min-height: 34px;
}

.panel-header h3 {
  color: var(--app-text);
  font-size: 16px;
}

.panel-header > span,
.panel-header > svg {
  color: var(--app-text-muted);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 11px;
  font-weight: 800;
}

.panel-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 32px;
  padding: 0 8px;
  border: 1px solid var(--app-line);
  border-radius: 6px;
  color: var(--app-text-soft);
  font-size: 12px;
  font-weight: 700;
  text-decoration: none;
  transition: color 160ms ease, border-color 160ms ease, background-color 160ms ease;
}

.panel-link:hover,
.panel-link:focus-visible {
  border-color: var(--app-accent);
  color: var(--app-accent);
  background: var(--app-surface-strong);
}

.panel-link:focus-visible {
  outline: none;
  box-shadow: var(--app-focus-shadow);
}

.chart-canvas {
  width: 100%;
  height: calc(100% - 38px);
  min-height: 230px;
  margin-top: 8px;
  outline: none;
}

.chart-canvas:focus-visible {
  box-shadow: var(--app-focus-shadow);
}

.source-chart {
  min-height: 148px;
  height: 156px;
}

.chart-empty {
  position: absolute;
  inset: 62px 14px 14px;
  background: var(--app-surface);
}

.source-summary {
  display: grid;
  gap: 5px;
  margin: 8px 0 0;
}

.source-summary div {
  justify-content: space-between;
  gap: 10px;
}

.source-summary dd {
  margin: 0;
  color: var(--app-text-soft);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.ingredient-list {
  display: grid;
  gap: 6px;
  padding: 0;
  margin: 12px 0 0;
  list-style: none;
}

.ingredient-list li {
  gap: 10px;
  min-height: 35px;
  padding: 0 8px;
  border-bottom: 1px solid var(--app-line);
}

.ingredient-list li:last-child {
  border-bottom: 0;
}

.ingredient-rank {
  width: 20px;
  color: var(--app-accent);
}

.ingredient-list strong {
  min-width: 0;
  overflow: hidden;
  color: var(--app-text);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ingredient-list li > span:last-child {
  margin-left: auto;
  color: var(--app-text-muted);
  font-family: "Cascadia Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (max-width: 1040px) {
  .operations-workspace {
    height: auto;
    overflow: visible;
  }

  .operations-grid {
    grid-template-columns: 1fr;
  }

  .trend-panel {
    grid-row: auto;
    min-height: 340px;
  }
}

@media (max-width: 760px) {
  .operations-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .toolbar-actions {
    width: 100%;
    justify-content: space-between;
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .metric-card {
    min-height: 104px;
  }

  .insight-strip {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .insight-strip time {
    width: 100%;
    margin-left: 24px;
  }
}

@media (max-width: 430px) {
  .metric-grid {
    grid-template-columns: 1fr;
  }

  .toolbar-actions :deep(.el-radio-group) {
    max-width: calc(100% - 46px);
    overflow-x: auto;
  }
}

@media (prefers-reduced-motion: reduce) {
  .operations-workspace :deep(*) {
    scroll-behavior: auto !important;
    transition-duration: 0.01ms !important;
    animation-duration: 0.01ms !important;
  }
}
</style>
