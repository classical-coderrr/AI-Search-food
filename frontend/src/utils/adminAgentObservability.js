export function normalizeAgentObservability(value) {
  const source = value && typeof value === 'object' ? value : {}
  const metrics = source.metrics && typeof source.metrics === 'object' ? source.metrics : {}
  const recovery = source.recovery && typeof source.recovery === 'object' ? source.recovery : {}
  const persistence = source.persistence && typeof source.persistence === 'object' ? source.persistence : {}

  return {
    generatedAt: typeof source.generatedAt === 'string' ? source.generatedAt : null,
    metrics: {
      runsStarted: nonNegativeNumber(metrics.runsStarted),
      runsCompleted: nonNegativeNumber(metrics.runsCompleted),
      runsFailed: nonNegativeNumber(metrics.runsFailed),
      runsRecovered: nonNegativeNumber(metrics.runsRecovered),
      eventsPersisted: nonNegativeNumber(metrics.eventsPersisted),
      eventsReplayed: nonNegativeNumber(metrics.eventsReplayed),
      duplicateWrites: nonNegativeNumber(metrics.duplicateWrites),
      durationSamples: nonNegativeNumber(metrics.durationSamples),
      averageRunDurationMs: nonNegativeNumber(metrics.averageRunDurationMs),
      successRate: ratio(metrics.successRate),
      recoveryRate: ratio(metrics.recoveryRate),
      duplicateWriteRate: ratio(metrics.duplicateWriteRate)
    },
    recovery: {
      enabled: recovery.enabled === true,
      scanDelay: stringValue(recovery.scanDelay, 'PT30S'),
      staleAfter: stringValue(recovery.staleAfter, 'PT5M'),
      leaseDuration: stringValue(recovery.leaseDuration, 'PT10M'),
      stateStore: stringValue(recovery.stateStore, 'redis')
    },
    persistence: {
      enabled: persistence.enabled !== false,
      snapshotInterval: stringValue(persistence.snapshotInterval, 'PT5M'),
      retention: stringValue(persistence.retention, 'P30D'),
      minimumAlertSamples: positiveInteger(persistence.minimumAlertSamples, 5),
      failureRateThreshold: ratioOrFallback(persistence.failureRateThreshold, 0.2),
      recoveryRateThreshold: ratioOrFallback(persistence.recoveryRateThreshold, 0.2),
      duplicateWriteRateThreshold: ratioOrFallback(persistence.duplicateWriteRateThreshold, 0.1)
    },
    history: normalizeHistory(source.history),
    alerts: normalizeAlerts(source.alerts)
  }
}

function normalizeHistory(value) {
  if (!Array.isArray(value)) return []
  return value
    .filter((item) => item && typeof item === 'object')
    .map((item) => ({
      capturedAt: typeof item.capturedAt === 'string' ? item.capturedAt : null,
      instanceId: stringValue(item.instanceId, ''),
      runsStarted: nonNegativeNumber(item.runsStarted),
      runsCompleted: nonNegativeNumber(item.runsCompleted),
      runsFailed: nonNegativeNumber(item.runsFailed),
      runsRecovered: nonNegativeNumber(item.runsRecovered),
      eventsPersisted: nonNegativeNumber(item.eventsPersisted),
      eventsReplayed: nonNegativeNumber(item.eventsReplayed),
      duplicateWrites: nonNegativeNumber(item.duplicateWrites),
      durationSamples: nonNegativeNumber(item.durationSamples),
      averageRunDurationMs: nonNegativeNumber(item.averageRunDurationMs)
    }))
}

function normalizeAlerts(value) {
  if (!Array.isArray(value)) return []
  return value
    .filter((item) => item && typeof item === 'object')
    .map((item) => ({
      id: item.id ?? null,
      alertType: stringValue(item.alertType, 'AGENT_OBSERVABILITY'),
      severity: stringValue(item.severity, 'WARNING'),
      status: item.status === 'OPEN' ? 'OPEN' : 'RESOLVED',
      title: stringValue(item.title, 'Agent 可观测告警'),
      message: stringValue(item.message, '请检查 Agent 运行状态。'),
      metricValue: nonNegativeNumber(item.metricValue),
      thresholdValue: nonNegativeNumber(item.thresholdValue),
      firstSeenAt: typeof item.firstSeenAt === 'string' ? item.firstSeenAt : null,
      lastSeenAt: typeof item.lastSeenAt === 'string' ? item.lastSeenAt : null,
      resolvedAt: typeof item.resolvedAt === 'string' ? item.resolvedAt : null
    }))
}

function nonNegativeNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : 0
}

function ratio(value) {
  return Math.min(1, nonNegativeNumber(value))
}

function ratioOrFallback(value, fallback) {
  return value === undefined || value === null || value === '' ? fallback : ratio(value)
}

function positiveInteger(value, fallback) {
  const number = Number(value)
  return Number.isInteger(number) && number > 0 ? number : fallback
}

function stringValue(value, fallback) {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback
}
