import { http } from './http'

export function getAdminAgentObservability() {
  return http.get('/admin/dashboard/agent-observability')
}
