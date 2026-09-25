import { http } from './http.js'

export function getPendingMemoryConfirmations(limit = 100) {
  return http.get('/memory/confirmations/pending', { params: { limit } })
}

export function decideMemoryConfirmation(candidateId, payload) {
  return http.post(`/memory/confirmations/${encodeURIComponent(candidateId)}/decision`, payload)
}

export function getMemoryManagement() {
  return http.get('/memory/management')
}

export function updateMemoryPersonalization(payload) {
  return http.put('/memory/management/personalization', payload)
}

export function updateManagedMemory(memoryId, payload) {
  return http.patch(`/memory/management/items/${encodeURIComponent(memoryId)}`, payload)
}

export function deleteManagedMemory(memoryId, version) {
  return http.delete(`/memory/management/items/${encodeURIComponent(memoryId)}`, { params: { version } })
}

export function clearManagedMemories() {
  return http.delete('/memory/management/all')
}
