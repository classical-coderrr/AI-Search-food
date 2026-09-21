import assert from 'node:assert/strict'
import test from 'node:test'
import {
  ADMIN_PANEL_NAVIGATION,
  buildAdminPanelQuery,
  getHotIngredientNavigation,
  isAdminPanelActive,
  shouldShowSavedRecipesNavigation,
  resolveAdminPanel
} from './hotIngredientNavigation.js'

test('普通用户进入热门食材栏目', () => {
  assert.deepEqual(getHotIngredientNavigation(), {
    label: '热门食材',
    to: { name: 'hot-ingredients' }
  })
})

test('管理员侧边栏按固定顺序显示全部面板', () => {
  assert.deepEqual(ADMIN_PANEL_NAVIGATION.map(({ panel }) => panel), [
    'overview',
    'settings',
    'hot-ingredients',
    'operation-logs',
    'error-logs',
    'user-management',
    'feature-suggestions',
    'agent-observability',
    'agent-evaluation'
  ])
})

test('只有普通用户显示我的菜谱入口', () => {
  assert.equal(shouldShowSavedRecipesNavigation('USER'), true)
  assert.equal(shouldShowSavedRecipesNavigation('ADMIN'), false)
  assert.equal(shouldShowSavedRecipesNavigation(''), false)
  assert.equal(shouldShowSavedRecipesNavigation(null), false)
  assert.equal(shouldShowSavedRecipesNavigation(undefined), false)
})

test('管理员面板默认值、非法值和当前项匹配正确', () => {
  assert.equal(resolveAdminPanel('hot-ingredients'), 'hot-ingredients')
  assert.equal(resolveAdminPanel(['hot-ingredients']), 'hot-ingredients')
  assert.equal(resolveAdminPanel('settings'), 'settings')
  assert.equal(resolveAdminPanel('operation-logs'), 'operation-logs')
  assert.equal(resolveAdminPanel('error-logs'), 'error-logs')
  assert.equal(resolveAdminPanel('user-management'), 'user-management')
  assert.equal(resolveAdminPanel('agent-observability'), 'agent-observability')
  assert.equal(resolveAdminPanel('agent-evaluation'), 'agent-evaluation')
  assert.equal(resolveAdminPanel(undefined), 'overview')
  assert.equal(resolveAdminPanel('unknown'), 'overview')
  assert.equal(isAdminPanelActive('settings', 'settings'), true)
  assert.equal(isAdminPanelActive('settings', 'overview'), false)
  assert.equal(isAdminPanelActive('unknown', 'overview'), true)
})

test('面板 query 保留现有参数并清理 overview', () => {
  assert.deepEqual(buildAdminPanelQuery({ source: 'sidebar' }, 'hot-ingredients'), {
    source: 'sidebar',
    panel: 'hot-ingredients'
  })
  assert.deepEqual(buildAdminPanelQuery({ source: 'sidebar', panel: 'hot-ingredients' }, 'settings'), {
    source: 'sidebar',
    panel: 'settings'
  })
  assert.deepEqual(buildAdminPanelQuery({ source: 'sidebar', panel: 'settings' }, 'overview'), {
    source: 'sidebar'
  })
})
