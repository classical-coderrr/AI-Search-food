import assert from 'node:assert/strict'
import test from 'node:test'
import {
  addDays,
  buildWeekDays,
  buildWeeklyMenuPayload,
  getWeekStart,
  mealLabel,
  normalizeWeeklyMenu,
  prioritizeRecipesForMeal,
  toDateString,
  weeklySlotKey
} from './weeklyMenu.js'

test('周起始日始终规范为周一', () => {
  assert.equal(toDateString(getWeekStart('2026-08-30')), '2026-08-24')
  assert.equal(toDateString(getWeekStart('2026-08-31')), '2026-08-31')
})

test('周日期包含连续七天和中文星期标签', () => {
  assert.deepEqual(buildWeekDays('2026-08-31').map((day) => [day.weekday, day.date]), [
    ['周一', '2026-08-31'],
    ['周二', '2026-09-01'],
    ['周三', '2026-09-02'],
    ['周四', '2026-09-03'],
    ['周五', '2026-09-04'],
    ['周六', '2026-09-05'],
    ['周日', '2026-09-06']
  ])
})

test('菜单选择会转换为最多一个餐次一条的保存请求', () => {
  const payload = buildWeeklyMenuPayload('2026-08-31', {
    [weeklySlotKey('2026-08-31', 'BREAKFAST')]: '7',
    [weeklySlotKey('2026-09-01', 'DINNER')]: 8,
    '2026-09-02|SNACK': 9,
    'invalid': 10
  })

  assert.deepEqual(payload, {
    weekStart: '2026-08-31',
    items: [
      { menuDate: '2026-08-31', mealType: 'BREAKFAST', recipeId: 7 },
      { menuDate: '2026-09-01', mealType: 'DINNER', recipeId: 8 }
    ]
  })
})

test('服务端周菜单响应会过滤无效餐次并补齐周范围', () => {
  const normalized = normalizeWeeklyMenu({
    id: 3,
    weekStart: '2026-08-31',
    items: [
      { id: 1, menuDate: '2026-08-31', mealType: 'DINNER', recipeId: 7, recipeTitle: '番茄炒蛋' },
      { id: 2, menuDate: '2026-09-01', mealType: 'SNACK', recipeId: 8 }
    ],
    shoppingItems: [{ ingredientName: '鸡蛋', status: 'PENDING' }],
    nutritionSummary: { assignedMealCount: 1, validEstimateMealCount: 1 }
  })

  assert.equal(normalized.weekStart, '2026-08-31')
  assert.equal(normalized.weekEnd, '2026-09-06')
  assert.equal(normalized.items.length, 1)
  assert.equal(normalized.shoppingItems.length, 1)
  assert.equal(normalized.nutritionSummary.assignedMealCount, 1)
  assert.equal(mealLabel('DINNER'), '晚餐')
  assert.equal(toDateString(addDays('2026-08-31', 6)), '2026-09-06')
})

test('菜单下拉会优先展示当前餐次的菜谱', () => {
  const recipes = [
    { id: 1, title: '蒜蓉炒茄子', mealType: 'dinner' },
    { id: 2, title: '鲜肉包子', mealType: 'breakfast' },
    { id: 3, title: '家常面条', mealType: 'any' }
  ]

  assert.deepEqual(prioritizeRecipesForMeal(recipes, 'BREAKFAST').map((recipe) => recipe.title), [
    '鲜肉包子',
    '家常面条',
    '蒜蓉炒茄子'
  ])
})
