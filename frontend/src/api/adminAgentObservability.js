import { http } from './http'

export function getAdminAgentObservability(range = '24h') {
  return http.get('/admin/dashboard/agent-observability', {
    params: { range }
  })
}
