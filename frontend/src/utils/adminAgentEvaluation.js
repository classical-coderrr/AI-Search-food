const EVALUATION_STATUSES = new Set(['PASSED', 'FAILED', 'NEVER'])

export function normalizeAgentEvaluation(value) {
  const source = value && typeof value === 'object' ? value : {}
  const totalCases = nonNegativeInteger(source.totalCases)
  const passedCases = Math.min(totalCases, nonNegativeInteger(source.passedCases))
  const failedCases = Math.min(totalCases, nonNegativeInteger(source.failedCases))

  return {
    id: source.id ?? null,
    status: EVALUATION_STATUSES.has(source.status) ? source.status : 'NEVER',
    startedAt: stringValue(source.startedAt),
    finishedAt: stringValue(source.finishedAt),
    totalCases,
    passedCases,
    failedCases,
    durationMs: nonNegativeNumber(source.durationMs),
    cases: normalizeCases(source.cases)
  }
}

function normalizeCases(value) {
  if (!Array.isArray(value)) return []
  return value
    .filter((item) => item && typeof item === 'object')
    .map((item) => ({
      caseKey: stringValue(item.caseKey, 'UNKNOWN_CASE'),
      description: stringValue(item.description, '未命名评测用例'),
      inputMessage: stringValue(item.inputMessage, '—'),
      passed: item.passed === true,
      expectedTools: stringArray(item.expectedTools),
      actualTools: stringArray(item.actualTools),
      failureReason: stringValue(item.failureReason)
    }))
}

function stringArray(value) {
  return Array.isArray(value) ? value.filter((item) => typeof item === 'string' && item.trim()).map((item) => item.trim()) : []
}

function stringValue(value, fallback = null) {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback
}

function nonNegativeInteger(value) {
  const number = Number(value)
  return Number.isInteger(number) && number > 0 ? number : 0
}

function nonNegativeNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : 0
}
