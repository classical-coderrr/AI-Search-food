import assert from 'node:assert/strict'
import test from 'node:test'
import { normalizeAgentEvaluation } from './adminAgentEvaluation.js'

test('评测结果归一化并限制统计值在用例总数内', () => {
  const result = normalizeAgentEvaluation({
    id: 8,
    status: 'PASSED',
    totalCases: 6,
    passedCases: 99,
    failedCases: -1,
    durationMs: 42,
    cases: [{
      caseKey: 'SAVE_RECIPE_EXPLICIT',
      description: '显式保存菜谱',
      inputMessage: '请保存这道菜',
      passed: true,
      expectedTools: ['recipe_save'],
      actualTools: ['recipe_save']
    }]
  })

  assert.equal(result.status, 'PASSED')
  assert.equal(result.totalCases, 6)
  assert.equal(result.passedCases, 6)
  assert.equal(result.failedCases, 0)
  assert.equal(result.cases[0].failureReason, null)
})

test('缺少或异常的评测响应安全回退为空状态', () => {
  assert.deepEqual(normalizeAgentEvaluation(null), {
    id: null,
    status: 'NEVER',
    startedAt: null,
    finishedAt: null,
    totalCases: 0,
    passedCases: 0,
    failedCases: 0,
    durationMs: 0,
    cases: []
  })
})
