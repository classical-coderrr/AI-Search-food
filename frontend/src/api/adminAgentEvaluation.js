import { http } from './http'

export function getAdminAgentEvaluation() {
  return http.get('/admin/dashboard/agent-evaluation')
}

export function runAdminAgentEvaluation() {
  return http.post('/admin/dashboard/agent-evaluation/run')
}
