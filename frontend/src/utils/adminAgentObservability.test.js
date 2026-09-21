import assert from 'node:assert/strict'
import test from 'node:test'
import { normalizeAgentObservability } from './adminAgentObservability.js'

test('Agent 可观测数据会归一化指标、比率和恢复配置', () => {
  const result = normalizeAgentObservability({
    generatedAt: '2026-09-21T08:00:00Z',
    metrics: {
      runsStarted: 4,
      runsCompleted: 3,
      averageRunDurationMs: 420.5,
      successRate: 0.75,
      recoveryRate: 2
    },
    recovery: {
      enabled: true,
      scanDelay: 'PT2S',
      stateStore: 'redis'
    },
    persistence: {
      enabled: true,
      snapshotInterval: 'PT1M',
      minimumAlertSamples: 8
    },
    history: [{
      capturedAt: '2026-09-21T08:00:00Z',
      runsStarted: 2,
      runsFailed: 1
    }],
    alerts: [{
      alertType: 'FAILURE_RATE',
      status: 'OPEN',
      title: 'Agent 失败率过高'
    }]
  })

  assert.equal(result.metrics.runsStarted, 4)
  assert.equal(result.metrics.runsCompleted, 3)
  assert.equal(result.metrics.averageRunDurationMs, 420.5)
  assert.equal(result.metrics.successRate, 0.75)
  assert.equal(result.metrics.recoveryRate, 1)
  assert.equal(result.metrics.runsFailed, 0)
  assert.equal(result.recovery.enabled, true)
  assert.equal(result.recovery.scanDelay, 'PT2S')
  assert.equal(result.recovery.staleAfter, 'PT5M')
  assert.equal(result.persistence.snapshotInterval, 'PT1M')
  assert.equal(result.persistence.minimumAlertSamples, 8)
  assert.equal(result.history.length, 1)
  assert.equal(result.history[0].runsFailed, 1)
  assert.equal(result.alerts[0].status, 'OPEN')
})
