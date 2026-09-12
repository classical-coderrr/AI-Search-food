export const WEEKLY_MEAL_OPTIONS = Object.freeze([
  { value: 'BREAKFAST', label: '早餐' },
  { value: 'LUNCH', label: '午餐' },
  { value: 'DINNER', label: '晚餐' }
])

export const WEEKDAY_LABELS = Object.freeze(['周一', '周二', '周三', '周四', '周五', '周六', '周日'])

const MEAL_VALUES = new Set(WEEKLY_MEAL_OPTIONS.map((option) => option.value))

export function getWeekStart(value = new Date()) {
  const date = parseDate(value) || new Date()
  const day = date.getDay()
  const offset = day === 0 ? -6 : 1 - day
  date.setDate(date.getDate() + offset)
  return startOfDay(date)
}

export function parseDate(value) {
  if (value instanceof Date && !Number.isNaN(value.getTime())) {
    return new Date(value.getTime())
  }
  if (typeof value !== 'string' || !value.trim()) {
    return null
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value.trim())
  if (!match) {
    return null
  }
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  return Number.isNaN(date.getTime()) ? null : date
}

export function toDateString(value) {
  const date = parseDate(value)
  if (!date) {
    return ''
  }
  return [date.getFullYear(), date.getMonth() + 1, date.getDate()]
    .map((part, index) => index === 0 ? String(part).padStart(4, '0') : String(part).padStart(2, '0'))
    .join('-')
}

export function addDays(value, days) {
  const date = parseDate(value) || new Date()
  date.setDate(date.getDate() + Number(days || 0))
  return date
}

export function buildWeekDays(weekStart) {
  const monday = getWeekStart(weekStart)
  return WEEKDAY_LABELS.map((weekday, index) => {
    const date = addDays(monday, index)
    return {
      weekday,
      date: toDateString(date),
      dateLabel: `${date.getMonth() + 1}月${date.getDate()}日`
    }
  })
}

export function weeklySlotKey(menuDate, mealType) {
  return `${toDateString(menuDate)}|${String(mealType || '').trim().toUpperCase()}`
}

export function buildWeeklyMenuPayload(weekStart, selections = {}) {
  const items = Object.entries(selections)
    .filter(([slot, recipeId]) => {
      const [, mealType] = slot.split('|')
      return Boolean(recipeId) && MEAL_VALUES.has(mealType)
    })
    .map(([slot, recipeId]) => {
      const [menuDate, mealType] = slot.split('|')
      return {
        menuDate,
        mealType,
        recipeId: Number(recipeId)
      }
    })
    .filter((item) => item.menuDate && Number.isInteger(item.recipeId) && item.recipeId > 0)

  return {
    weekStart: toDateString(getWeekStart(weekStart)),
    items
  }
}

export function normalizeWeeklyMenu(value = {}) {
  const weekStart = toDateString(getWeekStart(value?.weekStart))
  const items = (Array.isArray(value?.items) ? value.items : [])
    .map((item) => ({
      id: item?.id ?? null,
      menuDate: toDateString(item?.menuDate),
      mealType: String(item?.mealType || '').trim().toUpperCase(),
      recipeId: Number(item?.recipeId),
      recipeTitle: String(item?.recipeTitle || '').trim()
    }))
    .filter((item) => item.menuDate && MEAL_VALUES.has(item.mealType) && Number.isInteger(item.recipeId))

  return {
    id: value?.id ?? null,
    weekStart,
    weekEnd: toDateString(value?.weekEnd) || toDateString(addDays(weekStart, 6)),
    items,
    shoppingItems: Array.isArray(value?.shoppingItems) ? value.shoppingItems : [],
    nutritionSummary: value?.nutritionSummary || null
  }
}

export function mealLabel(mealType) {
  return WEEKLY_MEAL_OPTIONS.find((option) => option.value === mealType)?.label || '餐次'
}

export function prioritizeRecipesForMeal(recipes = [], mealType) {
  const desiredMealType = String(mealType || '').trim().toLowerCase()
  return recipes
    .map((recipe, index) => {
      const recipeMealType = String(recipe?.mealType || '').trim().toLowerCase()
      const priority = recipeMealType === desiredMealType
        ? 0
        : (!recipeMealType || recipeMealType === 'any' ? 1 : 2)
      return { recipe, index, priority }
    })
    .sort((left, right) => left.priority - right.priority || left.index - right.index)
    .map(({ recipe }) => recipe)
}

function startOfDay(date) {
  date.setHours(0, 0, 0, 0)
  return date
}
