import assert from 'node:assert/strict'
import test from 'node:test'
import { http } from './http.js'
import {
  clearManagedMemories,
  deleteManagedMemory,
  getMemoryFeedbackStatus,
  getMemoryManagement,
  submitMemoryFeedback,
  updateManagedMemory,
  updateMemoryPersonalization
} from './memory.js'

test('memory management actions call their authenticated API endpoints with version guards', async () => {
  const originalMethods = {
    get: http.get,
    put: http.put,
    patch: http.patch,
    post: http.post,
    delete: http.delete
  }
  const calls = []

  http.get = (...args) => { calls.push(['get', ...args]); return Promise.resolve({}) }
  http.put = (...args) => { calls.push(['put', ...args]); return Promise.resolve({}) }
  http.patch = (...args) => { calls.push(['patch', ...args]); return Promise.resolve({}) }
  http.post = (...args) => { calls.push(['post', ...args]); return Promise.resolve({}) }
  http.delete = (...args) => { calls.push(['delete', ...args]); return Promise.resolve({}) }

  try {
    await getMemoryManagement()
    await updateMemoryPersonalization({ enabled: false, version: 2 })
    await updateManagedMemory(17, { preference: 'DISLIKE', strength: 0.75, version: 4 })
    await deleteManagedMemory(17, 5)
    await clearManagedMemories()
    await getMemoryFeedbackStatus('run/with spaces')
    await submitMemoryFeedback('run-1', 'HELPFUL')
  } finally {
    http.get = originalMethods.get
    http.put = originalMethods.put
    http.patch = originalMethods.patch
    http.post = originalMethods.post
    http.delete = originalMethods.delete
  }

  assert.deepEqual(calls, [
    ['get', '/memory/management'],
    ['put', '/memory/management/personalization', { enabled: false, version: 2 }],
    ['patch', '/memory/management/items/17', { preference: 'DISLIKE', strength: 0.75, version: 4 }],
    ['delete', '/memory/management/items/17', { params: { version: 5 } }],
    ['delete', '/memory/management/all'],
    ['get', '/memory/feedback/run%2Fwith%20spaces'],
    ['post', '/memory/feedback', { traceId: 'run-1', feedbackType: 'HELPFUL' }]
  ])
})
