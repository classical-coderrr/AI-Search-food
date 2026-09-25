const MEMORY_TYPE_LABELS = {
  INGREDIENT_PREFERENCE: '食材偏好',
  RECIPE_PREFERENCE: '菜谱偏好',
  DIET_GOAL: '饮食目标'
}

const PREFERENCE_LABELS = {
  LIKE: '喜欢',
  DISLIKE: '不喜欢',
  AVOID: '避免',
  PURSUE: '倾向'
}

const PREFERENCE_OPTIONS = {
  INGREDIENT_PREFERENCE: [
    { value: 'LIKE', label: '喜欢' },
    { value: 'DISLIKE', label: '不喜欢' },
    { value: 'AVOID', label: '避免' }
  ],
  RECIPE_PREFERENCE: [
    { value: 'LIKE', label: '喜欢' },
    { value: 'DISLIKE', label: '不喜欢' }
  ],
  DIET_GOAL: [
    { value: 'PURSUE', label: '倾向' },
    { value: 'AVOID', label: '避免' }
  ]
}

const SCOPE_LABELS = {
  LONG_TERM: '长期',
  RECENT: '近期',
  SESSION: '本次会话',
  TEMPORARY: '临时'
}

const TEMPORAL_TYPE_LABELS = {
  EXPLICIT_PREFERENCE: '明确表达',
  IMPLICIT_PREFERENCE: '行为推断',
  SHORT_TERM_TREND: '短期趋势',
  BEHAVIOR_PATTERN: '行为规律',
  TEMPORARY_CONTEXT: '临时情境'
}

export function memoryTypeLabel(value) {
  return MEMORY_TYPE_LABELS[value] || '个性化记忆'
}

export function memoryPreferenceLabel(value) {
  return PREFERENCE_LABELS[value] || value || '未设置'
}

export function memoryPreferenceOptions(memoryType) {
  return PREFERENCE_OPTIONS[memoryType] || []
}

export function memoryScopeLabel(value) {
  return SCOPE_LABELS[value] || value || '未分类'
}

export function memoryTemporalTypeLabel(value) {
  return TEMPORAL_TYPE_LABELS[value] || value || '未标注来源类型'
}

export function normalizeMemoryManagementResponse(data) {
  const response = data && typeof data === 'object' ? data : {}
  const personalization = response.personalization && typeof response.personalization === 'object'
    ? response.personalization
    : {}
  const memories = Array.isArray(response.memories) ? response.memories : []
  const total = Number(response.total)

  return {
    personalization: {
      enabled: personalization.enabled !== false,
      version: Number.isInteger(Number(personalization.version)) ? Number(personalization.version) : 0
    },
    total: Number.isFinite(total) && total >= 0 ? total : memories.length,
    memories
  }
}

export function toMemoryUpdatePayload(memory, form) {
  return {
    preference: String(form?.preference || '').trim().toUpperCase(),
    strength: Number(form?.strength),
    version: Number(memory?.version)
  }
}
