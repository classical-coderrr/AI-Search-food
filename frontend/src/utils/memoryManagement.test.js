import assert from 'node:assert/strict'
import test from 'node:test'
import {
  memoryPreferenceLabel,
  memoryPreferenceOptions,
  memoryScopeLabel,
  memoryTemporalTypeLabel,
  memoryTypeLabel,
  normalizeMemoryManagementResponse,
  toMemoryUpdatePayload
} from './memoryManagement.js'

test('memory management labels are readable Chinese with a safe fallback', () => {
  assert.equal(memoryTypeLabel('INGREDIENT_PREFERENCE'), '食材偏好')
  assert.equal(memoryPreferenceLabel('DISLIKE'), '不喜欢')
  assert.equal(memoryScopeLabel('LONG_TERM'), '长期')
  assert.equal(memoryTemporalTypeLabel('EXPLICIT_PREFERENCE'), '明确表达')
  assert.equal(memoryTypeLabel('UNKNOWN'), '个性化记忆')
  assert.equal(memoryPreferenceLabel('CUSTOM'), 'CUSTOM')
})

test('editable preference options match each supported memory type', () => {
  assert.deepEqual(memoryPreferenceOptions('INGREDIENT_PREFERENCE').map(({ value }) => value), [
    'LIKE', 'DISLIKE', 'AVOID'
  ])
  assert.deepEqual(memoryPreferenceOptions('RECIPE_PREFERENCE').map(({ value }) => value), [
    'LIKE', 'DISLIKE'
  ])
  assert.deepEqual(memoryPreferenceOptions('DIET_GOAL').map(({ value }) => value), [
    'PURSUE', 'AVOID'
  ])
  assert.deepEqual(memoryPreferenceOptions('SHORT_TERM_TREND'), [])
})

test('management responses normalize missing collections and preserve opt-out/version state', () => {
  assert.deepEqual(normalizeMemoryManagementResponse({
    personalization: { enabled: false, version: 3 },
    total: '1',
    memories: [{ id: 8 }]
  }), {
    personalization: { enabled: false, version: 3 },
    total: 1,
    memories: [{ id: 8 }]
  })
  assert.deepEqual(normalizeMemoryManagementResponse({}), {
    personalization: { enabled: true, version: 0 },
    total: 0,
    memories: []
  })
})

test('editing payload uses the item version for optimistic concurrency', () => {
  assert.deepEqual(toMemoryUpdatePayload({ version: 5 }, {
    preference: ' dislike ',
    strength: 0.75
  }), {
    preference: 'DISLIKE',
    strength: 0.75,
    version: 5
  })
})
