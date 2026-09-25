import { http } from './http.js'

export function getPendingMemoryConfirmations(limit = 100) {
  return http.get('/memory/confirmations/pending', { params: { limit } })
}

export function decideMemoryConfirmation(candidateId, payload) {
  return http.post(`/memory/confirmations/${encodeURIComponent(candidateId)}/decision`, payload)
}
