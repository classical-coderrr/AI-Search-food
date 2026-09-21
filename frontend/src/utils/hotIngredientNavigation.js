const OVERVIEW_PANEL = 'overview'
const HOT_INGREDIENT_PANEL = 'hot-ingredients'
const SETTINGS_PANEL = 'settings'
const OPERATION_LOGS_PANEL = 'operation-logs'
const ERROR_LOGS_PANEL = 'error-logs'
const USER_MANAGEMENT_PANEL = 'user-management'
const FEATURE_SUGGESTIONS_PANEL = 'feature-suggestions'
const AGENT_OBSERVABILITY_PANEL = 'agent-observability'
const AGENT_EVALUATION_PANEL = 'agent-evaluation'

export const ADMIN_PANEL_NAVIGATION = Object.freeze([
  { panel: OVERVIEW_PANEL, label: '运营概览' },
  { panel: SETTINGS_PANEL, label: '系统设置' },
  { panel: HOT_INGREDIENT_PANEL, label: '热门分析' },
  { panel: OPERATION_LOGS_PANEL, label: '操作日志' },
  { panel: ERROR_LOGS_PANEL, label: '异常日志' },
  { panel: USER_MANAGEMENT_PANEL, label: '用户管理' },
  { panel: FEATURE_SUGGESTIONS_PANEL, label: '功能建议' },
  { panel: AGENT_OBSERVABILITY_PANEL, label: 'Agent 可观测' },
  { panel: AGENT_EVALUATION_PANEL, label: 'Agent 评测' }
])

const ADMIN_PANEL_VALUES = ADMIN_PANEL_NAVIGATION.map(({ panel }) => panel)

export function shouldShowSavedRecipesNavigation(role) {
  return role === 'USER'
}

export function getHotIngredientNavigation() {
  return {
    label: '热门食材',
    to: { name: 'hot-ingredients' }
  }
}

export function getNutritionTargetNavigation() {
  return {
    label: '营养目标',
    to: { name: 'nutrition-targets' }
  }
}

export function resolveAdminPanel(panel) {
  const value = Array.isArray(panel) ? panel[0] : panel
  return ADMIN_PANEL_VALUES.includes(value) ? value : OVERVIEW_PANEL
}

export function isAdminPanelActive(currentPanel, panel) {
  return resolveAdminPanel(currentPanel) === panel
}

export function buildAdminPanelQuery(currentQuery, panel) {
  const query = { ...currentQuery }
  const resolvedPanel = resolveAdminPanel(panel)
  if (resolvedPanel === OVERVIEW_PANEL) {
    delete query.panel
  } else {
    query.panel = resolvedPanel
  }
  return query
}
