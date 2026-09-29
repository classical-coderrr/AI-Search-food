import { http } from './http'

export function getAdminMemoryEvaluation() {
  return http.get('/admin/dashboard/memory-evaluation')
}

export function runAdminMemoryEvaluation() {
  return http.post('/admin/dashboard/memory-evaluation/run')
}

export function getAdminMemoryObservability(range = '30d') {
  return http.get('/admin/dashboard/memory-observability', { params: { range } })
}
