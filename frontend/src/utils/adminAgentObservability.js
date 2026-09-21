export function normalizeAgentObservability(value) {
  const source = value && typeof value === 'object' ? value : {}
  const metrics = source.metrics && typeof source.metrics === 'object' ? source.metrics : {}
  const recovery = source.recovery && typeof source.recovery === 'object' ? source.recovery : {}

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
    }
  }
}

function nonNegativeNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : 0
}

function ratio(value) {
  return Math.min(1, nonNegativeNumber(value))
}

function stringValue(value, fallback) {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback
}
